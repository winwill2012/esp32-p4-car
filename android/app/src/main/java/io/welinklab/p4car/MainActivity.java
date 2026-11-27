package io.welinklab.p4car;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class MainActivity extends Activity {
    private static final int BG = 0xfff3f7f7;
    private static final int INK = 0xff152e2b;
    private static final int MUTED = 0xff52686d;
    private static final int ACCENT = 0xff086b56;
    private static final int TINT = 0xffe0f0ea;
    private static final int LINE = 0xffdce7e3;
    private static final int MATCH = -1;
    private static final int WRAP = -2;
    private static final int MIC_PERMISSION = 10;

    private FrameLayout outer;
    private LinearLayout shell;
    private LinearLayout toolbar;
    private LinearLayout navigation;
    private NativeControlView drive;
    private ScrollView settings;
    private TextView title;
    private TextView carSwitch;
    private LinearLayout driveTab;
    private LinearLayout settingsTab;
    private TextView fieldError;
    private EditText serverField;
    private Switch directOnly;
    private ServerProfile profile;
    private RtcClient rtc;
    private boolean settingsVisible;
    private boolean foreground;
    private boolean fullscreen;
    private boolean enableAfterPermission;
    private boolean communicationEnded;
    private final OkHttpClient directoryClient = new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build();
    private Call directoryRequest;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        profile = loadProfile();
        buildScreen();
        applySystemBars();
        if (profile == null) showSettings();
        else showDrive();
    }

    private void buildScreen() {
        outer = new FrameLayout(this);
        outer.setBackgroundColor(BG);
        shell = column();
        outer.addView(shell, new FrameLayout.LayoutParams(MATCH, MATCH));
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        outer.setOnApplyWindowInsetsListener(this::setInsets);

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(17), 0, dp(17), 0);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_app);
        toolbar.addView(logo, new LinearLayout.LayoutParams(dp(31), dp(31)));
        title = label("4G 遥控车", 20, INK, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, WRAP, 1);
        titleParams.leftMargin = dp(9);
        toolbar.addView(title, titleParams);
        carSwitch = action("切换小车", false, this::showCarSelection);
        carSwitch.setSingleLine(true);
        carSwitch.setPadding(dp(11), 0, dp(10), 0);
        carSwitch.setMinHeight(0);
        carSwitch.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_car, 0, R.drawable.ic_chevron_down, 0);
        carSwitch.setCompoundDrawablePadding(dp(5));
        carSwitch.setBackground(new RippleDrawable(ColorStateList.valueOf(0x27086b56),
                shape(0xffe3f2eb, 22, 0xffbcded1), null));
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(WRAP, dp(44));
        switchParams.leftMargin = dp(8);
        toolbar.addView(carSwitch, switchParams);
        shell.addView(toolbar, new LinearLayout.LayoutParams(MATCH, dp(55)));

        FrameLayout content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        drive = new NativeControlView(this, new NativeControlView.Actions() {
            @Override public void fullscreen() { setFullscreen(!fullscreen); }
            @Override public void speaker() { if (rtc != null) rtc.toggleSpeaker(); }
            @Override public void microphone() { toggleMicrophone(); }
            @Override public void communication() { toggleCommunication(); }
            @Override public void standard(boolean enabled) {
                prefs.edit().putBoolean("standard", enabled).apply();
                if (rtc != null) rtc.setStandard(enabled);
            }
            @Override public void orientation(boolean h, boolean v) {
                prefs.edit().putBoolean("flipX", h).putBoolean("flipY", v).apply();
            }
            @Override public void steer(int position) { if (rtc != null) rtc.sendSteering(position); }
            @Override public void throttle(int position) { if (rtc != null) rtc.sendThrottle(position); }
            @Override public void headlight() { if (rtc != null) rtc.toggleHeadlight(); }
            @Override public void retry() { if (rtc != null) rtc.reconnect(); }
        }, prefs.getBoolean("flipX", false), prefs.getBoolean("flipY", true));
        content.addView(drive, new FrameLayout.LayoutParams(MATCH, MATCH));
        settings = buildSettings();
        content.addView(settings, new FrameLayout.LayoutParams(MATCH, MATCH));

        navigation = new LinearLayout(this);
        navigation.setBackgroundColor(Color.WHITE);
        navigation.setElevation(dp(2));
        driveTab = tab("控制", this::showDrive);
        settingsTab = tab("设置", this::showSettings);
        navigation.addView(driveTab, new LinearLayout.LayoutParams(0, dp(50), 1));
        navigation.addView(settingsTab, new LinearLayout.LayoutParams(0, dp(50), 1));
        shell.addView(navigation, new LinearLayout.LayoutParams(MATCH, WRAP));
        setContentView(outer);
    }

    private WindowInsets setInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            if (fullscreen) {
                shell.setPadding(0, 0, 0, 0);
                Insets safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                drive.setFullscreenInsets(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                shell.setPadding(bars.left, bars.top, bars.right, Math.max(0, ime - bars.bottom));
                navigation.setPadding(0, 0, 0, bars.bottom);
            }
        } else if (fullscreen) {
            shell.setPadding(0, 0, 0, 0);
            drive.setFullscreenInsets(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                    insets.getStableInsetRight(), insets.getStableInsetBottom());
        } else {
            shell.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), 0);
            navigation.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
        }
        return insets;
    }

    private ScrollView buildSettings() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout section = column();
        section.setPadding(dp(16), dp(10), dp(16), dp(15));
        scroll.addView(section, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout card = column();
        card.setPadding(dp(17), dp(16), dp(17), dp(16));
        card.setBackground(shape(Color.WHITE, 18, LINE));
        addRow(section, card, 0);
        addRow(card, label("服务器配置", 21, INK, true), 0);
        addRow(card, label("服务器公网 IPv4", 14, INK, true), 15);
        serverField = new EditText(this);
        serverField.setSingleLine(true);
        serverField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverField.setHint("填写公网 IP 或粘贴 HTTPS 地址");
        serverField.setTextColor(INK);
        serverField.setHintTextColor(MUTED);
        serverField.setTextSize(16);
        serverField.setPadding(dp(12), 0, dp(12), 0);
        serverField.setMinHeight(dp(50));
        serverField.setBackground(shape(BG, 10, LINE));
        addRow(card, serverField, 7);
        addRow(card, action("粘贴服务器地址", false, this::pasteAddress), 9);
        directOnly = new Switch(this);
        directOnly.setText("仅 P2P 直连");
        directOnly.setTextColor(INK);
        directOnly.setTextSize(15);
        directOnly.setMinHeight(dp(48));
        addRow(card, directOnly, 10);
        addRow(card, label("默认同时探测直连和 TURN 中继。", 13, MUTED, false), 0);
        fieldError = label("", 14, 0xffa32e2b, false);
        fieldError.setVisibility(View.GONE);
        fieldError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addRow(card, fieldError, 10);
        addRow(card, action("保存并连接", true, this::saveAndConnect), 14);
        if (profile != null) {
            serverField.setText(profile.ip);
            directOnly.setChecked(profile.directOnly);
        }
        return scroll;
    }

    private void pasteAddress() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
            setFieldError("剪贴板里没有服务器地址。");
            return;
        }
        try {
            ServerProfile value = new ServerProfile(clip.getItemAt(0).getText().toString(), directOnly.isChecked());
            serverField.setText(value.ip);
            setFieldError(null);
        } catch (IllegalArgumentException error) { setFieldError(error.getMessage()); }
    }

    private void saveAndConnect() {
        try {
            ServerProfile next = new ServerProfile(serverField.getText().toString(), directOnly.isChecked());
            if (profile != null && profile.ip.equals(next.ip)) {
                next = new ServerProfile(next.ip, next.directOnly, profile.carId, profile.carName);
            }
            profile = next;
            saveProfile();
            serverField.setText(profile.ip);
            setFieldError(null);
            startCommunication();
        } catch (IllegalArgumentException error) { setFieldError(error.getMessage()); }
    }

    private ServerProfile loadProfile() {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.contains("key")) prefs.edit().remove("key").apply();
        try { return new ServerProfile(prefs.getString("ip", ""), prefs.getBoolean("direct", false),
                prefs.getString("carId", ServerProfile.DEFAULT_CAR), prefs.getString("carName", "")); }
        catch (IllegalArgumentException error) { return null; }
    }

    private void setFieldError(String message) {
        fieldError.setText(message);
        fieldError.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void saveProfile() {
        getPreferences(MODE_PRIVATE).edit().putString("ip", profile.ip).putBoolean("direct", profile.directOnly)
                .putString("carId", profile.carId).putString("carName", profile.carName).remove("key").apply();
    }

    private void showSettings() {
        settingsVisible = true;
        cancelCarDirectory();
        carSwitch.setVisibility(View.GONE);
        updateForeground();
        drive.setVisibility(View.INVISIBLE);
        settings.setVisibility(View.VISIBLE);
        title.setText("设置");
        updateTabs();
    }

    private void showDrive() {
        if (profile == null) {
            showSettings();
            serverField.requestFocus();
            return;
        }
        settingsVisible = false;
        InputMethodManager input = getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(outer.getWindowToken(), 0);
        serverField.clearFocus();
        settings.setVisibility(View.GONE);
        drive.setVisibility(View.VISIBLE);
        title.setText(profile.carName.isEmpty() ? "4G 遥控车" : profile.carName);
        carSwitch.setVisibility(View.VISIBLE);
        updateTabs();
        drive.setCommunicationEnded(communicationEnded);
        if (communicationEnded) return;
        if (rtc == null) {
            rtc = new RtcClient(this, drive.video(), new RtcClient.Listener() {
                @Override public void connection(String message, RtcClient.Connection state) { drive.connection(message, state); }
                @Override public void steering(boolean enabled) { drive.steering(enabled); }
                @Override public void lights(boolean ready, boolean headlight, boolean pending) {
                    drive.lights(ready, headlight, pending);
                }
                @Override public void audio(boolean ready, boolean sound, RtcClient.Microphone mic) { drive.setAudio(ready, sound, mic); }
                @Override public void quality(boolean standard, boolean canSwitch) { drive.quality(standard, canSwitch); }
                @Override public void microphoneError(boolean resetAudio) { handleMicrophoneError(resetAudio); }
            });
            rtc.setStandard(getPreferences(MODE_PRIVATE).getBoolean("standard", false));
        }
        rtc.connect(profile);
        updateForeground();
    }

    private record Car(String id, String name, boolean online) {}

    private void showCarSelection() {
        if (profile == null || directoryRequest != null) return;
        ServerProfile current = profile;
        carSwitch.setEnabled(false);
        directoryRequest = directoryClient.newCall(new Request.Builder().url(current.carsUrl()).build());
        directoryRequest.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                runOnUiThread(() -> showCarDirectory(call, current, Collections.emptyList(), "读取小车列表失败，请重试。"));
            }

            @Override public void onResponse(Call call, Response response) {
                List<Car> cars = new ArrayList<>();
                String error = "读取小车列表失败，请重试。";
                try (response) {
                    if (response.code() == 404) error = "这个服务器版本仅支持连接默认小车。";
                    else if (response.isSuccessful() && response.body() != null) {
                        JSONArray rows = new JSONObject(response.body().string()).getJSONArray("cars");
                        for (int i = 0; i < rows.length(); ++i) {
                            JSONObject row = rows.getJSONObject(i);
                            String id = row.getString("id");
                            String name = row.getString("name").trim();
                            if (ServerProfile.validCarId(id) && !name.isEmpty() && name.length() <= 24) {
                                cars.add(new Car(id, name, row.optBoolean("online")));
                            }
                        }
                        error = "服务器暂无登记的小车。";
                    }
                } catch (IOException | JSONException invalid) {
                    cars.clear();
                }
                String message = error;
                runOnUiThread(() -> showCarDirectory(call, current, cars, message));
            }
        });
    }

    private void showCarDirectory(Call call, ServerProfile current, List<Car> cars, String error) {
        if (directoryRequest != call) return;
        directoryRequest = null;
        carSwitch.setEnabled(true);
        if (profile != current || settingsVisible || isFinishing()) return;
        if (cars.isEmpty()) {
            toast(error);
            return;
        }
        ControlDialog picker = new ControlDialog(this, "切换小车", R.drawable.ic_car);
        for (Car car : cars) {
            ControlDialog.Choice row = picker.new Choice(R.drawable.ic_car, 0, car.name(),
                    car.online() ? "在线" : "离线", false);
            row.statusBadge(car.online());
            row.check(car.id().equals(current.carId));
            row.setOnClickListener(view -> {
                picker.dismiss();
                if (profile != current || settingsVisible || car.id().equals(current.carId)) return;
                drive.releaseControls();
                enableAfterPermission = false;
                if (rtc != null) rtc.endCommunication();
                profile = new ServerProfile(current.ip, current.directOnly, car.id(), car.name());
                saveProfile();
                startCommunication();
            });
            picker.add(row, 8);
        }
        picker.show(carSwitch, false);
    }

    private void cancelCarDirectory() {
        if (directoryRequest == null) return;
        directoryRequest.cancel();
        directoryRequest = null;
        carSwitch.setEnabled(true);
    }

    private void toggleCommunication() {
        if (communicationEnded) {
            startCommunication();
            return;
        }
        communicationEnded = true;
        enableAfterPermission = false;
        drive.releaseControls();
        if (rtc != null) rtc.endCommunication();
        drive.setCommunicationEnded(true);
    }

    private void startCommunication() {
        communicationEnded = false;
        showDrive();
    }

    private void toggleMicrophone() {
        if (rtc == null) return;
        if (rtc.isMicrophoneRequested()) {
            rtc.setMicrophone(false);
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            rtc.setMicrophone(true);
        } else if (getPreferences(MODE_PRIVATE).getBoolean("micAsked", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this).setTitle("麦克风权限")
                    .setMessage("在 Android 的应用权限中允许使用麦克风后，即可向小车说话。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("打开系统设置", (dialog, which) -> startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .show();
        } else {
            getPreferences(MODE_PRIVATE).edit().putBoolean("micAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != MIC_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            enableAfterPermission = true;
            applyMicrophonePermission();
        } else toast("麦克风未开启。允许 Android 的录音权限后可以对讲。");
    }

    private void applyMicrophonePermission() {
        if (enableAfterPermission && foreground) {
            enableAfterPermission = false;
            if (!communicationEnded && !settingsVisible && rtc != null) rtc.setMicrophone(true);
        }
    }

    private void handleMicrophoneError(boolean resetAudio) {
        toast("系统麦克风无法录音，请检查系统麦克风开关后重试。");
        if (!resetAudio || rtc == null) return;
        boolean speakerEnabled = rtc.isSpeakerEnabled();
        rtc.release();
        rtc = null;
        if (!settingsVisible && profile != null) {
            showDrive();
            if (speakerEnabled) rtc.toggleSpeaker();
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private void setFullscreen(boolean enabled) {
        if (fullscreen == enabled) return;
        fullscreen = enabled;
        drive.setFullscreen(enabled);
        toolbar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        navigation.setVisibility(enabled ? View.GONE : View.VISIBLE);
        outer.setBackgroundColor(enabled ? 0xff0a1919 : BG);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = enabled ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            getWindow().setAttributes(params);
        }
        setRequestedOrientation(enabled ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        applySystemBars();
        outer.requestApplyInsets();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        outer.post(() -> {
            applySystemBars();
            outer.requestApplyInsets();
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreen) applySystemBars();
    }

    @SuppressWarnings("deprecation")
    private void applySystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(fullscreen ? 0 : light, light);
            if (fullscreen) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else controller.show(WindowInsets.Type.systemBars());
        } else {
            int layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            getWindow().getDecorView().setSystemUiVisibility(layout | (fullscreen
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        updateForeground();
        applyMicrophonePermission();
    }

    @Override protected void onPause() {
        foreground = false;
        updateForeground();
        super.onPause();
    }

    private void updateForeground() {
        boolean active = foreground && !settingsVisible && !communicationEnded;
        if (!active) drive.releaseControls();
        if (rtc != null) rtc.setForeground(active);
    }

    @Override public void onBackPressed() {
        if (fullscreen) setFullscreen(false);
        else if (settingsVisible && profile != null) showDrive();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cancelCarDirectory();
        if (rtc != null) rtc.release();
        super.onDestroy();
    }

    private void updateTabs() {
        styleTab(driveTab, !settingsVisible, R.drawable.tab_drive_active, R.drawable.tab_drive_inactive);
        styleTab(settingsTab, settingsVisible, R.drawable.tab_profile_active, R.drawable.tab_profile_inactive);
    }

    private void styleTab(LinearLayout tab, boolean selected, int active, int inactive) {
        tab.setSelected(selected);
        ((ImageView) tab.getChildAt(0)).setImageResource(selected ? active : inactive);
        ((TextView) tab.getChildAt(1)).setTextColor(selected ? ACCENT : MUTED);
    }

    private LinearLayout tab(String text, Runnable activate) {
        LinearLayout tab = column();
        tab.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        tab.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView name = label(text, 11, MUTED, false);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(WRAP, WRAP);
        margin.topMargin = dp(2);
        tab.addView(name, margin);
        tab.setBackground(new RippleDrawable(ColorStateList.valueOf(0x19086b56), null, shape(Color.WHITE, 0, 0)));
        tab.setContentDescription(text);
        tab.setFocusable(true);
        tab.setOnClickListener(v -> activate.run());
        return tab;
    }

    private TextView action(String text, boolean primary, Runnable click) {
        TextView button = label(text, 15, primary ? Color.WHITE : ACCENT, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(46));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x5084ae9e),
                shape(primary ? ACCENT : TINT, 12, 0), null));
        button.setFocusable(true);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private void addRow(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private GradientDrawable shape(int fill, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
