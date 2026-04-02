package com.zero.recorder.gl;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;
import com.zero.recorder.RecorderLog;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GlFrameRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "ZR.GL";
    private static final long STALE_FRAME_WARNING_MS = 1000L;
    private static final float[] FULLSCREEN_QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] FULLSCREEN_UV = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int programId;
    private int textureId;
    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;

    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private final Object frameLock = new Object();
    private final HandlerThread renderThread;
    private boolean hasReceivedFrame;

    private final FloatBuffer positionBuffer;
    private final FloatBuffer textureBuffer;

    private int sourceWidth;
    private int sourceHeight;
    private int encoderWidth;
    private int encoderHeight;
    private long frameCount;
    private long lastFrameTimestampMs;
    private long lastStaleWarningMs;

    public GlFrameRenderer(int initialWidth, int initialHeight) {
        sourceWidth = initialWidth;
        sourceHeight = initialHeight;
        encoderWidth = initialWidth;
        encoderHeight = initialHeight;

        renderThread = new HandlerThread("GLRenderThread", Process.THREAD_PRIORITY_DISPLAY);
        renderThread.start();

        positionBuffer = toBuffer(FULLSCREEN_QUAD);
        textureBuffer = toBuffer(FULLSCREEN_UV);
    }

    public void initialize(Surface encoderInputSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        EGLConfig[] configs = new EGLConfig[1];
        int[] configAttributes = {
                EGL14.EGL_RENDERABLE_TYPE, 4,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        int[] configCount = new int[1];
        EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0);

        eglContext = EGL14.eglCreateContext(
                eglDisplay,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE},
                0
        );
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderInputSurface, new int[]{EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER,
                "uniform mat4 uMVPMatrix;\n"
                        + "uniform mat4 uSTMatrix;\n"
                        + "attribute vec4 aPos;\n"
                        + "attribute vec4 aTex;\n"
                        + "varying vec2 vTex;\n"
                        + "void main() {\n"
                        + "  gl_Position = uMVPMatrix * aPos;\n"
                        + "  vTex = (uSTMatrix * aTex).xy;\n"
                        + "}");
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;\n"
                        + "varying vec2 vTex;\n"
                        + "uniform samplerExternalOES s;\n"
                        + "void main() {\n"
                        + "  gl_FragColor = texture2D(s, vTex);\n"
                        + "}");

        programId = GLES20.glCreateProgram();
        GLES20.glAttachShader(programId, vertexShader);
        GLES20.glAttachShader(programId, fragmentShader);
        GLES20.glLinkProgram(programId);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this, new Handler(renderThread.getLooper()));
        inputSurface = new Surface(surfaceTexture);
        GLES20.glViewport(0, 0, encoderWidth, encoderHeight);
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public void setEncoderSize(int width, int height) {
        encoderWidth = width;
        encoderHeight = height;
        GLES20.glViewport(0, 0, encoderWidth, encoderHeight);
    }

    public void updateSourceSize(int width, int height) {
        sourceWidth = width;
        sourceHeight = height;
        surfaceTexture.setDefaultBufferSize(width, height);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (frameLock) {
            frameAvailable.set(true);
            frameLock.notifyAll();
        }
        frameCount++;
        lastFrameTimestampMs = System.currentTimeMillis();
        if (frameCount == 1) {
            RecorderLog.i(TAG, "First source frame arrived");
        }
    }

    public boolean awaitAndDraw(float rotationDegrees, int targetFps) {
        long waitTimeoutMs = 1000L / Math.max(targetFps, 1);
        boolean hasNewFrame = false;

        synchronized (frameLock) {
            if (!frameAvailable.get()) {
                try {
                    frameLock.wait(waitTimeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (frameAvailable.get()) {
                hasNewFrame = true;
                frameAvailable.set(false);
            }
        }

        if (hasNewFrame) {
            try {
                surfaceTexture.updateTexImage();
                hasReceivedFrame = true;
            } catch (Exception ignored) {
                return false;
            }
        }

        if (!hasReceivedFrame) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (!hasNewFrame && lastFrameTimestampMs > 0 && now - lastFrameTimestampMs >= STALE_FRAME_WARNING_MS
                && now - lastStaleWarningMs >= STALE_FRAME_WARNING_MS) {
            lastStaleWarningMs = now;
            RecorderLog.w(TAG, "No new source frame for " + (now - lastFrameTimestampMs) + "ms; reusing previous frame");
        }

        float[] textureMatrix = new float[16];
        surfaceTexture.getTransformMatrix(textureMatrix);
        GLES20.glUseProgram(programId);

        float effectiveWidth = (rotationDegrees == 90f || rotationDegrees == 270f) ? sourceHeight : sourceWidth;
        float effectiveHeight = (rotationDegrees == 90f || rotationDegrees == 270f) ? sourceWidth : sourceHeight;

        float sourceAspect = effectiveWidth / effectiveHeight;
        float encoderAspect = (float) encoderWidth / encoderHeight;
        float scaleX = 1f;
        float scaleY = 1f;

        // Center-crop so the output surface is always filled without black borders.
        if (sourceAspect > encoderAspect) {
            scaleX = sourceAspect / encoderAspect;
        } else {
            scaleY = encoderAspect / sourceAspect;
        }

        float[] mvpMatrix = new float[16];
        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f);
        if (rotationDegrees != 0f) {
            Matrix.rotateM(mvpMatrix, 0, rotationDegrees, 0f, 0f, 1f);
        }

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, "uMVPMatrix"), 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, "uSTMatrix"), 1, false, textureMatrix, 0);

        int positionHandle = GLES20.glGetAttribLocation(programId, "aPos");
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, positionBuffer);

        int textureHandle = GLES20.glGetAttribLocation(programId, "aTex");
        GLES20.glEnableVertexAttribArray(textureHandle);
        GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime());
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        return hasNewFrame;
    }

    public void release() {
        try {
            if (surfaceTexture != null) {
                surfaceTexture.release();
            }
            if (inputSurface != null) {
                inputSurface.release();
            }
            renderThread.quitSafely();
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
        } catch (Exception ignored) {
        }
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private static FloatBuffer toBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }
}
