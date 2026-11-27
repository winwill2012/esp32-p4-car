package io.welinklab.p4car;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.util.concurrent.CountDownLatch;

final class NativeVideoView extends TextureView implements TextureView.SurfaceTextureListener, VideoSink {
    interface FrameObserver { void onSize(int width, int height); }

    private final EglRenderer renderer = new EglRenderer("P4 video");
    private volatile boolean initialized;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private final FrameObserver observer;

    NativeVideoView(Context context, FrameObserver observer) {
        super(context);
        this.observer = observer;
        setSurfaceTextureListener(this);
    }

    void init(EglBase.Context context) {
        renderer.init(context, EglBase.CONFIG_PLAIN, new GlRectDrawer());
        initialized = true;
        if (isAvailable()) onSurfaceTextureAvailable(getSurfaceTexture(), getWidth(), getHeight());
    }

    void setOrientation(boolean horizontal, boolean vertical) {
        renderer.setMirror(horizontal);
        renderer.setMirrorVertically(vertical);
    }

    void clear() {
        frameWidth = 0;
        frameHeight = 0;
        renderer.clearImage();
    }

    void release() {
        initialized = false;
        renderer.release();
    }

    @Override public void onFrame(VideoFrame frame) {
        if (!initialized) return;
        int width = frame.getRotatedWidth();
        int height = frame.getRotatedHeight();
        if (width != frameWidth || height != frameHeight) {
            frameWidth = width;
            frameHeight = height;
            post(() -> { if (frameWidth == width && frameHeight == height) observer.onSize(width, height); });
        }
        renderer.onFrame(frame);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (!initialized) return;
        renderer.createEglSurface(surface);
        onSurfaceTextureSizeChanged(surface, width, height);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (height > 0) renderer.setLayoutAspectRatio((float) width / height);
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (initialized) {
            CountDownLatch released = new CountDownLatch(1);
            renderer.releaseEglSurface(released::countDown);
            ThreadUtils.awaitUninterruptibly(released);
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
}
