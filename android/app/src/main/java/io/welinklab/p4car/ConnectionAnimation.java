package io.welinklab.p4car;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class ConnectionAnimation extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private final RadialGradient glow = new RadialGradient(0, 0, 1f,
            new int[] {0x553cbea9, 0x22369783, 0x00298272},
            new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);
    private final SweepGradient sweep = new SweepGradient(0, 0,
            new int[] {0x003bafa1, 0x1a3bafa1, 0x8843cdb9, 0xffa5f9e4, 0x003bafa1},
            new float[] {0f, .66f, .88f, .98f, 1f});
    private final ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f);
    private float phase = .18f;

    ConnectionAnimation(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        animation.setDuration(2400);
        animation.setRepeatCount(ValueAnimator.INFINITE);
        animation.setInterpolator(new LinearInterpolator());
        animation.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && ValueAnimator.areAnimatorsEnabled()) {
            if (!animation.isStarted()) animation.start();
        } else {
            animation.cancel();
        }
    }

    @Override protected void onDetachedFromWindow() {
        animation.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float radius = Math.min(getHeight() * .43f, getWidth() * .18f);
        if (radius <= 0) return;
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(radius, radius);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffe4fff7);
        paint.setShader(glow);
        canvas.save();
        canvas.scale(1.9f, 1.12f);
        canvas.drawCircle(0, 0, 1f, paint);
        canvas.restore();
        paint.setShader(null);

        for (int column = 0; column < 5; column++) {
            float x = 1.28f + column * .18f;
            paint.setColor((0x57 - column * 11) << 24 | 0x63c4b4);
            for (int row = -2; row <= 2; row++) {
                float y = row * .19f;
                canvas.drawCircle(x, y, .015f, paint);
                canvas.drawCircle(-x, y, .015f, paint);
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(.018f);
        paint.setColor(0x435ed0be);
        canvas.drawCircle(0, 0, .82f, paint);
        paint.setColor(0x2e8fe7d6);
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            arc(canvas, 1f, 18f + 90f * quadrant, 53f);
        }
        canvas.save();
        canvas.rotate(phase * 360f);
        paint.setColor(0xffffffff);
        paint.setShader(sweep);
        paint.setStrokeWidth(.056f);
        canvas.drawCircle(0, 0, .82f, paint);
        paint.setShader(null);
        paint.setColor(0xcdeac586);
        paint.setStrokeWidth(.031f);
        arc(canvas, 1f, 148f, 34f);
        canvas.restore();

        paint.setColor(0xffb4f1e4);
        paint.setStrokeWidth(.045f);
        canvas.drawRoundRect(-.31f, -.21f, .18f, .21f, .09f, .09f, paint);
        canvas.drawLine(.18f, -.07f, .35f, -.17f, paint);
        canvas.drawLine(.35f, -.17f, .35f, .17f, paint);
        canvas.drawLine(.35f, .17f, .18f, .07f, paint);

        float scanY = -.43f + .86f * phase;
        float scanWidth = (float) Math.sqrt(.53f * .53f - scanY * scanY);
        int alpha = (int) (108 * Math.sin(Math.PI * phase));
        paint.setColor(alpha << 24 | 0x71f3d3);
        paint.setStrokeWidth(.021f);
        canvas.drawLine(-scanWidth, scanY, scanWidth, scanY, paint);
        canvas.restore();
    }

    private void arc(Canvas canvas, float radius, float start, float length) {
        ring.set(-radius, -radius, radius, radius);
        canvas.drawArc(ring, start, length, false, paint);
    }
}
