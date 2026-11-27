package io.welinklab.p4car;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class NativeControlView extends FrameLayout {
    interface Actions {
        void fullscreen();
        void speaker();
        void microphone();
        void communication();
        void standard(boolean enabled);
        void orientation(boolean horizontal, boolean vertical);
        void steer(int position);
        void throttle(int position);
        void headlight();
        void retry();
    }

    private static final int INK = 0xff15312d;
    private static final int MUTED = 0xff52686d;
    private static final int GREEN = 0xff086b56;
    private static final int LIGHT = 0xffe0efe9;
    private static final int VIDEO_BG = 0xff0a1919;

    private final NativeVideoView video;
    private final TextView headline;
    private final TextView status;
    private final LinearLayout waiting;
    private final ConnectionAnimation connectionAnimation;
    private final TextView placeholder;
    private final TextView resolution;
    private final FrameLayout panel;
    private final LinearLayout tools;
    private final TextView pictureButton;
    private final TextView headlightButton;
    private final TextView fullButton;
    private final LinearLayout audio;
    private final TextView speakerButton;
    private final TextView microphoneButton;
    private final TextView communicationButton;
    private final Pad throttle;
    private final Pad steering;
    private final Actions actions;
    private boolean fullscreen;
    private RtcClient.Connection connectionState = RtcClient.Connection.IDLE;
    private boolean hasFrame;
    private boolean standard;
    private boolean canSwitch = true;
    private boolean horizontal;
    private boolean vertical;
    private boolean sound;
    private boolean lightsReady;
    private boolean communicationEnded;
    private RtcClient.Microphone mic;
    private String connectionMessage = "正在等待连接";
    private boolean compactTools;
    private int insetLeft, insetTop, insetRight, insetBottom;

    NativeControlView(Context context, Actions actions, boolean horizontal, boolean vertical) {
        super(context);
        this.actions = actions;
        this.horizontal = horizontal;
        this.vertical = vertical;
        headline = label("实况画面", 22, INK, true);
        status = label("正在等待连接", 13, MUTED, false);
        status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        panel = new FrameLayout(context);
        panel.setClipToOutline(true);
        panel.setBackground(shape(VIDEO_BG, 18));
        resolution = label("", 12, Color.WHITE, true);
        video = new NativeVideoView(context, (width, height) -> {
            hasFrame = true;
            resolution.setText(width + "×" + height);
            updateVideoStatus();
        });
        video.setOrientation(horizontal, vertical);
        panel.addView(video, new FrameLayout.LayoutParams(-1, -1));
        waiting = new LinearLayout(context);
        waiting.setOrientation(LinearLayout.VERTICAL);
        waiting.setGravity(Gravity.CENTER);
        waiting.setBackgroundColor(VIDEO_BG);
        waiting.setOnClickListener(v -> actions.retry());
        connectionAnimation = new ConnectionAnimation(context);
        connectionAnimation.setVisibility(GONE);
        waiting.addView(connectionAnimation, new LinearLayout.LayoutParams(-1, dp(78)));
        placeholder = label("正在等待连接", 15, 0xffd2e9e3, true);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setOnClickListener(v -> actions.retry());
        waiting.addView(placeholder, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(waiting, new FrameLayout.LayoutParams(-1, -1));
        resolution.setBackground(shape(0x99152e2c, 8));
        resolution.setPadding(dp(9), dp(4), dp(9), dp(4));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        badge.bottomMargin = dp(9);
        panel.addView(resolution, badge);
        resolution.setVisibility(GONE);

        tools = new LinearLayout(context);
        headlightButton = button("大灯", R.drawable.ic_headlight, true, actions::headlight);
        pictureButton = button("画面", R.drawable.ic_picture, true, this::showPictureMenu);
        fullButton = button("全屏", R.drawable.ic_fullscreen, true, actions::fullscreen);
        tools.addView(headlightButton, new LinearLayout.LayoutParams(-2, -1));
        LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(-2, -1);
        picture.leftMargin = dp(6);
        tools.addView(pictureButton, picture);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-2, -1);
        full.leftMargin = dp(6);
        tools.addView(fullButton, full);

        audio = new LinearLayout(context);
        speakerButton = button("开启声音", R.drawable.ic_sound, false, actions::speaker);
        microphoneButton = button("开启麦克风", R.drawable.ic_mic, false, actions::microphone);
        communicationButton = button("结束通讯", R.drawable.ic_disconnect, false, actions::communication);
        for (TextView control : new TextView[] {speakerButton, microphoneButton, communicationButton}) {
            control.setPadding(dp(7), 0, dp(7), 0);
        }
        audio.addView(speakerButton, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(0, -1, 1.18f);
        micParams.leftMargin = dp(6);
        audio.addView(microphoneButton, micParams);
        LinearLayout.LayoutParams communicationParams = new LinearLayout.LayoutParams(0, -1, 1);
        communicationParams.leftMargin = dp(6);
        audio.addView(communicationButton, communicationParams);

        throttle = new Pad("前进 / 后退", "停止 · 仅预览", true);
        steering = new Pad("转向", "等待转向连接", false);
        steering.joystick.setEnabled(false);
        addView(panel);
        addView(headline);
        addView(status);
        addView(tools);
        addView(audio);
        addView(throttle);
        addView(steering);
        setCommunicationEnded(false);
        lights(false, false, false);
        setAudio(false, false, RtcClient.Microphone.OFF);
        updateVideoStatus();
    }

    NativeVideoView video() { return video; }

    void connection(String message, RtcClient.Connection state) {
        connectionState = state;
        connectionMessage = message;
        updateStatusLabel();
        status.setTextColor(fullscreen ? Color.WHITE : isLive() ? GREEN : MUTED);
        if (!isLive()) hasFrame = false;
        updateVideoStatus();
    }

    private boolean isLive() { return connectionState == RtcClient.Connection.LIVE; }

    private void updateVideoStatus() {
        boolean showing = isLive() && hasFrame;
        boolean loading = !showing && connectionState != RtcClient.Connection.IDLE;
        placeholder.setText(isLive() ? "正在等待摄像头画面" : connectionMessage);
        waiting.setPadding(dp(14), loading && !fullscreen ? dp(24) : 0, dp(14), 0);
        connectionAnimation.setVisibility(loading ? VISIBLE : GONE);
        waiting.setVisibility(showing ? GONE : VISIBLE);
        resolution.setVisibility(showing && !fullscreen ? VISIBLE : GONE);
    }

    void steering(boolean enabled) {
        if (!enabled) steering.joystick.release();
        steering.joystick.setEnabled(enabled);
        steering.hint.setText(enabled ? "左右拖动，松手回中" : "等待转向连接");
    }

    void lights(boolean ready, boolean headlight, boolean pending) {
        boolean wasReady = lightsReady;
        lightsReady = ready;
        if (!ready && wasReady) throttle.joystick.release();
        throttle.hint.setText(ready ? "后拉点亮倒车灯" : "停止 · 仅预览");
        headlightButton.setEnabled(ready && !pending);
        headlightButton.setSelected(headlight);
        headlightButton.setAlpha(ready ? (pending ? .72f : 1f) : .55f);
        headlightButton.setContentDescription(pending ? "正在切换前大灯" : headlight ? "关闭前大灯" : "开启前大灯");
        int color = headlight ? 0xff593e05 : Color.WHITE;
        headlightButton.setTextColor(color);
        headlightButton.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        headlightButton.setBackground(ripple(headlight ? 0xfff5d48c : 0xd5263d39, 11));
    }

    void setAudio(boolean ready, boolean sound, RtcClient.Microphone mic) {
        this.sound = sound;
        this.mic = mic;
        styleAudio(speakerButton, ready, sound, sound ? "关闭声音" : "开启声音");
        boolean starting = mic == RtcClient.Microphone.STARTING;
        boolean active = mic == RtcClient.Microphone.ON;
        styleAudio(microphoneButton, ready, active, active ? "关闭麦克风" : starting ? "取消开启麦克风" : "开启麦克风");
        if (starting) setButtonLabel(microphoneButton, "开启中");
    }

    void setCommunicationEnded(boolean ended) {
        communicationEnded = ended;
        String label = ended ? "重新连接" : "结束通讯";
        communicationButton.setText(label);
        communicationButton.setContentDescription(label);
        communicationButton.setCompoundDrawablesWithIntrinsicBounds(ended ? R.drawable.ic_connect : R.drawable.ic_disconnect, 0, 0, 0);
        int color = ended ? GREEN : 0xff9a3939;
        communicationButton.setTextColor(color);
        communicationButton.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        communicationButton.setBackground(ripple(ended ? LIGHT : 0xfff8eae8, 12));
        waiting.setClickable(!ended);
        placeholder.setClickable(!ended);
        if (ended) {
            setAudio(false, false, RtcClient.Microphone.OFF);
            steering(false);
            lights(false, false, false);
            connection("通讯已结束", RtcClient.Connection.IDLE);
        }
    }

    private void styleAudio(TextView button, boolean enabled, boolean active, String name) {
        button.setContentDescription(name);
        button.setSelected(active);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .55f);
        int color = fullscreen || active ? Color.WHITE : GREEN;
        button.setTextColor(color);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        button.setBackground(ripple(active ? GREEN : fullscreen ? 0xd5243c38 : LIGHT, 12));
        setButtonLabel(button, fullscreen ? (button == speakerButton ? "声音" : "麦克风") : name);
    }

    void quality(boolean standard, boolean usable) { this.standard = standard; canSwitch = usable; }

    void setFullscreen(boolean enabled) {
        releaseControls();
        fullscreen = enabled;
        headline.setVisibility(enabled ? GONE : VISIBLE);
        communicationButton.setVisibility(enabled ? GONE : VISIBLE);
        fullButton.setContentDescription(enabled ? "退出全屏" : "全屏");
        panel.setBackground(shape(VIDEO_BG, enabled ? 0 : 18));
        status.setTextColor(enabled ? Color.WHITE : isLive() ? GREEN : MUTED);
        status.setBackground(enabled ? shape(0x96203330, 9) : null);
        status.setPadding(enabled ? dp(9) : 0, 0, enabled ? dp(9) : 0, 0);
        throttle.style(enabled);
        steering.style(enabled);
        LinearLayout.LayoutParams animationSize = (LinearLayout.LayoutParams) connectionAnimation.getLayoutParams();
        animationSize.height = dp(enabled ? 112 : 78);
        connectionAnimation.setLayoutParams(animationSize);
        updateStatusLabel();
        setAudio(microphoneButton.isEnabled(), sound, mic);
        updateVideoStatus();
        requestLayout();
    }

    private void updateStatusLabel() {
        status.setContentDescription(connectionMessage);
        if (!fullscreen) status.setText(connectionMessage);
        else if (isLive()) status.setText(connectionMessage.endsWith("P2P") ? "P2P"
                : connectionMessage.endsWith("TURN") ? "TURN" : "已连接");
        else status.setText(communicationEnded ? "已结束" : "连接中");
    }

    void releaseControls() {
        throttle.joystick.release();
        steering.joystick.release();
    }

    void setFullscreenInsets(int left, int top, int right, int bottom) {
        if (left == insetLeft && top == insetTop && right == insetRight && bottom == insetBottom) return;
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        insetBottom = bottom;
        if (fullscreen) requestLayout();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != oldWidth || height != oldHeight) releaseControls();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Size the video and controls before the children are measured during rotation.
        arrange(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec, heightSpec);
    }

    private void arrange(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (fullscreen) {
            arrangeFullscreen(width, height);
            return;
        }
        compactTools = false;
        setButtonLabel(headlightButton, "大灯");
        setButtonLabel(pictureButton, "画面");
        setButtonLabel(fullButton, "全屏");
        int margin = dp(15);
        int content = width - margin * 2;
        int top = dp(47);
        int available = Math.max(dp(150), height - top - dp(183));
        int playerHeight = Math.min(content * 9 / 16, available);
        place(headline, content, dp(37), margin, dp(5));
        placeEnd(status, Math.max(dp(120), content / 2), dp(37), margin, dp(5));
        place(panel, content, playerHeight, margin, top);
        placeEnd(tools, -2, dp(38), margin + dp(8), top + dp(8));
        int padWidth = (content - dp(10)) / 2;
        int padHeight = Math.max(dp(100), Math.min(dp(176), height - top - playerHeight - dp(76)));
        int padTop = top + playerHeight + dp(10);
        place(throttle, padWidth, padHeight, margin, padTop);
        place(steering, padWidth, padHeight, margin + padWidth + dp(10), padTop);
        place(audio, content, dp(47), margin, padTop + padHeight + dp(10));
    }

    private void arrangeFullscreen(int width, int height) {
        place(panel, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 0, 0);
        int left = insetLeft + dp(12);
        int right = insetRight + dp(12);
        int top = insetTop + dp(7);
        int safeWidth = Math.max(1, width - left - right);
        int fullAudioWidth = 2 * Math.max(buttonWidth(speakerButton, "声音"), buttonWidth(microphoneButton, "麦克风")) + dp(6);
        int fullToolsWidth = buttonWidth(headlightButton, "大灯") + buttonWidth(pictureButton, "画面")
                + buttonWidth(fullButton, "退出全屏") + dp(12);
        compactTools = fullAudioWidth + 2 * (fullToolsWidth + dp(8)) > safeWidth;
        setButtonLabel(headlightButton, "大灯");
        setButtonLabel(pictureButton, "画面");
        setButtonLabel(fullButton, "退出全屏");
        setButtonLabel(speakerButton, "声音");
        setButtonLabel(microphoneButton, mic == RtcClient.Microphone.STARTING ? "开启中" : "麦克风");
        int audioWidth = compactTools ? Math.min(dp(104), safeWidth / 3) : fullAudioWidth;
        int sideWidth = Math.max(0, (safeWidth - audioWidth) / 2 - dp(8));
        int toolsWidth = Math.min(sideWidth, compactTools ? dp(146) : fullToolsWidth);
        placeEnd(tools, toolsWidth, dp(44), right, top);
        place(audio, audioWidth, dp(44), left + (safeWidth - audioWidth) / 2, top);
        place(status, Math.min(sideWidth, (int) Math.ceil(status.getPaint().measureText(status.getText().toString()))
                + status.getPaddingLeft() + status.getPaddingRight() + dp(1)), dp(32), left, top + dp(6));

        int padSize = Math.min(dp(158), (int) ((height - insetTop - insetBottom) * .49f));
        padSize = Math.max(0, Math.min(padSize, (safeWidth - dp(12)) / 2));
        int padsTop = Math.max(top + dp(44), height - insetBottom - padSize - dp(10));
        place(throttle, padSize, padSize, left, padsTop);
        place(steering, padSize, padSize, width - right - padSize, padsTop);
    }

    private int buttonWidth(TextView button, String text) {
        int iconWidth = button.getCompoundDrawables()[0].getIntrinsicWidth();
        return Math.max(dp(48), button.getPaddingLeft() + iconWidth + dp(4) + button.getPaddingRight()
                + (int) Math.ceil(button.getPaint().measureText(text)) + dp(1));
    }

    private void setButtonLabel(TextView button, String text) {
        String label = fullscreen && compactTools ? "" : text;
        if (!TextUtils.equals(button.getText(), label)) button.setText(label);
        int gap = label.isEmpty() ? 0 : dp(4);
        if (button.getCompoundDrawablePadding() != gap) button.setCompoundDrawablePadding(gap);
    }

    private void place(View view, int width, int height, int left, int top) {
        setBounds(view, width, height, Gravity.LEFT, left, 0, top);
    }

    private void placeEnd(View view, int width, int height, int right, int top) {
        setBounds(view, width, height, Gravity.RIGHT, 0, right, top);
    }

    private void setBounds(View view, int width, int height, int side, int left, int right, int top) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.width = width;
        params.height = height;
        params.gravity = Gravity.TOP | side;
        params.leftMargin = left;
        params.rightMargin = right;
        params.topMargin = top;
    }

    private void showPictureMenu() {
        ControlDialog controls = new ControlDialog(getContext(), "画面设置", R.drawable.ic_picture);
        controls.section("清晰度", 12);
        LinearLayout qualityRow = new LinearLayout(getContext());
        ControlDialog.Choice hd = controls.new Choice(0, 0, "高清", "1280 × 720", false);
        ControlDialog.Choice sd = controls.new Choice(0, 0, "标清", canSwitch ? "640 × 360" : "设备不支持", false);
        hd.check(!standard);
        sd.check(standard);
        hd.setOnClickListener(view -> {
            controls.dismiss();
            actions.standard(false);
        });
        sd.setOnClickListener(view -> {
            controls.dismiss();
            actions.standard(true);
        });
        sd.setEnabled(canSwitch);
        sd.setAlpha(canSwitch ? 1f : .55f);
        qualityRow.addView(hd, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams sdParams = new LinearLayout.LayoutParams(0, -1, 1f);
        sdParams.leftMargin = controls.dp(8);
        qualityRow.addView(sd, sdParams);
        controls.add(qualityRow, 6);

        controls.section("画面方向", 12);
        ControlDialog.Choice flipX = controls.new Choice(R.drawable.ic_flip, 0, "水平翻转", "", true);
        ControlDialog.Choice flipY = controls.new Choice(R.drawable.ic_flip, 90, "垂直翻转", "", true);
        flipX.check(horizontal);
        flipY.check(vertical);
        flipX.setOnClickListener(view -> {
            horizontal = !horizontal;
            flipX.check(horizontal);
            video.setOrientation(horizontal, vertical);
            actions.orientation(horizontal, vertical);
        });
        flipY.setOnClickListener(view -> {
            vertical = !vertical;
            flipY.check(vertical);
            video.setOrientation(horizontal, vertical);
            actions.orientation(horizontal, vertical);
        });
        controls.add(flipX, 6);
        controls.add(flipY, 6);
        controls.show(pictureButton, fullscreen);
    }

    private TextView button(String name, int icon, boolean overlay, Runnable click) {
        int color = overlay ? Color.WHITE : GREEN;
        TextView button = label(name, 13, color, true);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setContentDescription(name);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        button.setCompoundDrawablePadding(dp(4));
        button.setBackground(ripple(overlay ? 0xd5263d39 : LIGHT, 11));
        button.setOnClickListener(view -> click.run());
        button.setFocusable(true);
        return button;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable ripple(int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x287abdaa), shape(fill, radius), null);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Pad extends LinearLayout {
        final JoystickView joystick;
        final TextView title;
        final TextView hint;

        Pad(String name, String instruction, boolean vertical) {
            super(NativeControlView.this.getContext());
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(8), dp(8), dp(8), dp(8));
            title = label(name, 15, INK, true);
            hint = label(instruction, 11, MUTED, false);
            joystick = new JoystickView(getContext(), vertical, name, position -> {
                if (vertical) {
                    if (lightsReady) {
                        hint.setText(position < 0 ? "倒车灯亮" : "后拉点亮倒车灯");
                        actions.throttle(position);
                    } else {
                        hint.setText(position == 0 ? instruction : (position > 0 ? "前进" : "后退") + " · 仅预览");
                    }
                } else actions.steer(position);
            });
            addView(title, new LinearLayout.LayoutParams(-2, -2));
            addView(joystick, new LinearLayout.LayoutParams(-1, 0, 1));
            addView(hint, new LinearLayout.LayoutParams(-2, -2));
            style(false);
        }

        void style(boolean fullscreen) {
            setBackground(fullscreen ? null : shape(Color.WHITE, 15));
            title.setTextColor(fullscreen ? Color.WHITE : INK);
            hint.setTextColor(fullscreen ? 0xffdcf1eb : MUTED);
            joystick.setTranslucent(fullscreen);
        }
    }
}
