package io.welinklab.p4car;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class ControlDialog {
    private static final int INK = 0xff173630;
    private static final int SECONDARY = 0xff4d6661;
    private static final int ACCENT = 0xff086b56;
    private static final int ACTIVE_FILL = 0xffe5f4ee;
    private static final int ACTIVE_LINE = 0xff9ccdbb;
    private static final int LINE = 0xffdae7e1;
    private static final int FIELD = 0xfff4f8f6;
    private final Context context;
    private final Dialog dialog;
    private final LinearLayout content;

    ControlDialog(Context context, String title, int icon) {
        this.context = context;
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout surface = new LinearLayout(context);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(dp(17), dp(14), dp(17), dp(17));
        surface.setBackground(shape(0xfffcfefd, 24, LINE));
        surface.setClipToOutline(true);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView emblem = icon(icon, ACCENT, 9);
        emblem.setBackground(shape(ACTIVE_FILL, 13, 0));
        header.addView(emblem, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView heading = text(title, 20, INK, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1f);
        headingParams.leftMargin = dp(11);
        header.addView(heading, headingParams);
        ImageView close = icon(R.drawable.ic_close, SECONDARY, 12);
        close.setContentDescription("关闭");
        close.setBackground(ripple(FIELD, 22, 0));
        close.setOnClickListener(v -> dismiss());
        close.setFocusable(true);
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        surface.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        surface.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1f));
        dialog.setContentView(surface);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setTitle(title);
    }

    void add(View view, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        content.addView(view, params);
    }

    void section(String title, int topMargin) {
        TextView heading = text(title, 13, SECONDARY, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        add(heading, topMargin);
    }

    void show(View anchor, boolean immersive) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(immersive ? .26f : .32f);
        if (immersive) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.show();
        int width = anchor.getRootView().getWidth();
        if (width <= 0) width = context.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(dp(386), Math.max(1, width - dp(28))), WindowManager.LayoutParams.WRAP_CONTENT);
        if (immersive) {
            keepSystemBarsHidden(window);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    @SuppressWarnings("deprecation")
    private void keepSystemBarsHidden(Window window) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController insets = window.getInsetsController();
            if (insets != null) {
                insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                insets.hide(WindowInsets.Type.systemBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    void dismiss() { dialog.dismiss(); }

    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color, boolean emphasis) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (emphasis) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private ImageView icon(int resource, int color, int padding) {
        ImageView image = new ImageView(context);
        image.setImageResource(resource);
        image.setImageTintList(ColorStateList.valueOf(color));
        image.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return image;
    }

    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    private RippleDrawable ripple(int color, int radius, int border) {
        return new RippleDrawable(ColorStateList.valueOf(0x23086b56), shape(color, radius, border), null);
    }

    final class Choice extends LinearLayout {
        private final boolean checkbox;
        private final Mark mark;
        private TextView detailLabel;
        private boolean checked;

        Choice(int icon, int rotation, String title, String detail, boolean checkbox) {
            super(context);
            this.checkbox = checkbox;
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(11), dp(8), dp(11), dp(8));
            setMinimumHeight(dp(55));
            setFocusable(true);

            if (icon != 0) {
                ImageView emblem = icon(icon, ACCENT, 8);
                emblem.setBackground(shape(0xffe4f0eb, 11, 0));
                emblem.setRotation(rotation);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(36));
                params.rightMargin = dp(10);
                addView(emblem, params);
            }
            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            TextView label = text(title, 15, INK, true);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(label);
            String description = title;
            if (!detail.isEmpty()) {
                detailLabel = text(detail, 12, SECONDARY, false);
                words.addView(detailLabel, new LinearLayout.LayoutParams(-2, -2));
                description += "，" + detail;
            }
            LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, -2, 1f);
            wordParams.rightMargin = dp(7);
            addView(words, wordParams);
            mark = new Mark();
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));
            setContentDescription(description);
        }

        void statusBadge(boolean online) {
            detailLabel.setTextColor(online ? 0xff09654e : 0xff52635d);
            detailLabel.setPadding(dp(6), dp(1), dp(6), dp(1));
            detailLabel.setBackground(shape(online ? 0xffd5eee3 : 0xffe9eeeb, 8, 0));
        }

        void check(boolean value) {
            checked = value;
            setBackground(ripple(value ? ACTIVE_FILL : FIELD, 14, value ? ACTIVE_LINE : LINE));
            mark.invalidate();
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(checkbox ? "android.widget.CheckBox" : "android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(checked);
        }

        private final class Mark extends View {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            Mark() { super(context); }

            @Override protected void onDraw(Canvas canvas) {
                float center = getWidth() / 2f;
                float radius = dp(9);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(dp(1));
                paint.setColor(checked ? ACCENT : 0xff7f9d93);
                paint.setStyle(checked && checkbox ? Paint.Style.FILL : Paint.Style.STROKE);
                if (checkbox) {
                    canvas.drawRoundRect(center - radius, center - radius, center + radius, center + radius,
                            dp(4), dp(4), paint);
                    if (checked) {
                        paint.setColor(Color.WHITE);
                        paint.setStrokeWidth(dp(2));
                        canvas.drawLine(center - dp(4), center, center - dp(1), center + dp(3), paint);
                        canvas.drawLine(center - dp(1), center + dp(3), center + dp(4), center - dp(3), paint);
                    }
                } else {
                    canvas.drawCircle(center, center, radius, paint);
                    if (checked) {
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(center, center, dp(4), paint);
                    }
                }
            }
        }
    }
}
