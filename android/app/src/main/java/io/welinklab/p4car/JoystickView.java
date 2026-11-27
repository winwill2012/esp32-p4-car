package io.welinklab.p4car;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.function.IntConsumer;

final class JoystickView extends View {
    private static final int HEARTBEAT_MS = 75;
    private static final float DEAD_ZONE = .06f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final Path chevron = new Path();
    private final boolean vertical;
    private final int accent;
    private final IntConsumer change;
    private final Runnable pulse = this::sendPulse;
    private int pointer = MotionEvent.INVALID_POINTER_ID;
    private float displacement;
    private int position;
    private boolean translucent;
    private RadialGradient thumbShade;
    private ValueAnimator recenter;
    private float releaseGlow;

    JoystickView(Context context, boolean vertical, String description, IntConsumer change) {
        super(context);
        this.vertical = vertical;
        this.change = change;
        accent = vertical ? 0xfff5bb70 : 0xff82efe3;
        setContentDescription(description);
        setClickable(true);
    }

    void setTranslucent(boolean next) {
        translucent = next;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float radius = Math.min(width, height) / 2f;
        thumbShade = radius > 0 ? new RadialGradient(-radius * .075f, -radius * .08f, radius * .51f,
                new int[] {tint(155), 0xb5224447, 0xdb0c1c23}, null, Shader.TileMode.CLAMP) : null;
        if (recenter != null) recenter.cancel();
        displacement = 0;
        releaseGlow = 0;
    }

    @Override protected void onDraw(Canvas canvas) {
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        float radius = Math.min(x, y) - 2f;
        if (radius <= 0) return;
        if (translucent) {
            canvas.save();
            canvas.translate(x, y);
            if (vertical) canvas.rotate(-90f);
            drawFullscreen(canvas, radius);
            canvas.restore();
            return;
        }
        int fill = 0xff23443e;
        int border = 0xff5a7d72;
        if (!isEnabled()) { fill = 0xffe7eeeb; border = 0xffafc0bb; }
        circle(canvas, x, y, radius, fill);
        circle(canvas, x, y, radius * .78f, isEnabled() ? 0xff19352f : 0xffdfe9e4);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, radius * .028f));
        paint.setColor(border);
        canvas.drawCircle(x, y, radius * .97f, paint);
        canvas.drawCircle(x, y, radius * .69f, paint);
        if (vertical) canvas.drawLine(x, y - radius * .66f, x, y + radius * .66f, paint);
        else canvas.drawLine(x - radius * .66f, y, x + radius * .66f, y, paint);
        paint.setStyle(Paint.Style.FILL);
        float travel = displacement * radius * .57f;
        float knobX = x + (vertical ? 0 : travel);
        float knobY = y + (vertical ? -travel : 0);
        float knob = radius * .34f;
        int highlight = isEnabled() ? 0xffa9dfca : 0xfff1f5f3;
        int shade = isEnabled() ? 0xff4e8672 : 0xffc4d4ce;
        paint.setShader(new RadialGradient(knobX - knob * .30f, knobY - knob * .37f,
                knob * 1.4f, highlight, shade, Shader.TileMode.CLAMP));
        canvas.drawCircle(knobX, knobY, knob, paint);
        paint.setShader(null);
    }

    private void drawFullscreen(Canvas canvas, float radius) {
        float strength = Math.abs(displacement);
        float glow = isPressed() ? .4f + strength * .6f : releaseGlow;
        float thumb = displacement * radius * .49f;
        float ring = radius * .88f;
        boolean enabled = isEnabled();
        int idleAlpha = enabled ? 102 : 52;
        paint.setStyle(Paint.Style.FILL);
        circle(canvas, 0, 0, radius * .95f, 0x31101c23);
        circle(canvas, 0, 0, radius * .61f, 0x26101c23);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        arcBounds.set(-ring, -ring, ring, ring);
        paint.setStrokeWidth(radius * .047f);
        paint.setColor(0x72111c22);
        canvas.drawArc(arcBounds, 35f, 110f, false, paint);
        canvas.drawArc(arcBounds, 215f, 110f, false, paint);
        paint.setStrokeWidth(radius * .014f);
        paint.setColor(tint(idleAlpha));
        canvas.drawArc(arcBounds, 35f, 110f, false, paint);
        canvas.drawArc(arcBounds, 215f, 110f, false, paint);

        for (int step = -2; step <= 2; step++) {
            float angle = step * 14f;
            for (int side = 0; side < 2; side++) {
                boolean lit = strength > .06f && (displacement > 0f) == (side == 0)
                        && strength >= Math.abs(step) * .28f;
                paint.setStrokeWidth(radius * (lit ? .052f : .024f));
                paint.setColor(tint(lit ? (int) (110 + 115 * glow) : idleAlpha));
                canvas.drawArc(arcBounds, angle + side * 180f - 3f, 6f, false, paint);
            }
        }

        float rail = radius * .64f;
        paint.setStrokeWidth(radius * .023f);
        paint.setColor(0x835b7477);
        canvas.drawLine(-rail, 0, rail, 0, paint);
        paint.setColor(tint((int) (65 + 153 * glow)));
        paint.setStrokeWidth(radius * .041f);
        canvas.drawLine(0, 0, thumb, 0, paint);
        for (int side = -1; side <= 1; side += 2) {
            float tip = rail * side;
            float back = (rail - radius * .075f) * side;
            chevron.rewind();
            chevron.moveTo(back, -radius * .064f);
            chevron.lineTo(tip, 0);
            chevron.lineTo(back, radius * .064f);
            paint.setColor(tint(displacement * side > .06f ? (int) (120 + 100 * glow) : idleAlpha));
            canvas.drawPath(chevron, paint);
        }

        canvas.save();
        canvas.translate(thumb, 0);
        paint.setStyle(Paint.Style.FILL);
        float knob = radius * .285f;
        if (glow > 0) circle(canvas, 0, 0, knob + radius * .078f, tint((int) (54 * glow)));
        paint.setShader(thumbShade);
        paint.setColor(0xffffffff);
        paint.setAlpha(enabled ? (int) (172 + 83 * glow) : 95);
        canvas.drawCircle(0, 0, knob, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(tint(enabled ? (int) (119 + 124 * glow) : 62));
        paint.setStrokeWidth(radius * .026f);
        canvas.drawCircle(0, 0, knob, paint);
        paint.setStrokeWidth(radius * .018f);
        paint.setColor(tint((int) (idleAlpha + 88 * glow)));
        for (int step = -1; step <= 1; step++) {
            float offset = step * radius * .071f;
            canvas.drawLine(-radius * .085f, offset, radius * .085f, offset, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private int tint(int alpha) { return (alpha << 24) | (accent & 0xffffff); }

    private void circle(Canvas canvas, float x, float y, float radius, int color) {
        paint.setColor(color);
        canvas.drawCircle(x, y, radius, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (recenter != null) recenter.cancel();
                releaseGlow = 0;
                setPressed(true);
                getParent().requestDisallowInterceptTouchEvent(true);
                pointer = event.getPointerId(0);
                update(event, 0);
                postDelayed(pulse, HEARTBEAT_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index >= 0) update(event, index);
                else release();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) release();
                return true;
            case MotionEvent.ACTION_UP:
                release();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default: return true;
        }
    }

    private void update(MotionEvent event, int index) {
        float delta = vertical ? getHeight() / 2f - event.getY(index) : event.getX(index) - getWidth() / 2f;
        float scale = Math.max(1, Math.min(getHeight(), getWidth()) * .31f);
        displacement = Math.max(-1f, Math.min(1f, delta / scale));
        float magnitude = Math.abs(displacement);
        int next = magnitude <= DEAD_ZONE ? 0 : Math.round(Math.copySign(
                (magnitude - DEAD_ZONE) / (1f - DEAD_ZONE), displacement) * 100);
        if (next != position) {
            position = next;
            change.accept(position);
        }
        invalidate();
    }

    private void sendPulse() {
        if (pointer == MotionEvent.INVALID_POINTER_ID) return;
        change.accept(position);
        postDelayed(pulse, HEARTBEAT_MS);
    }

    void release() {
        boolean active = pointer != MotionEvent.INVALID_POINTER_ID || position != 0;
        pointer = MotionEvent.INVALID_POINTER_ID;
        position = 0;
        setPressed(false);
        removeCallbacks(pulse);
        if (active) change.accept(0);
        if (active && translucent && isShown()) {
            // Animate the knob only. The neutral command has already been sent.
            float start = displacement;
            recenter = ValueAnimator.ofFloat(1f, 0f);
            recenter.setDuration(165);
            recenter.setInterpolator(new DecelerateInterpolator());
            recenter.addUpdateListener(animation -> {
                float remaining = (float) animation.getAnimatedValue();
                displacement = start * remaining;
                releaseGlow = .65f * remaining;
                invalidate();
            });
            recenter.start();
        } else {
            if (recenter != null) recenter.cancel();
            displacement = 0;
            releaseGlow = 0;
        }
        invalidate();
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
