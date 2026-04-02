#!/system/bin/sh
# 自动生成的项目构建脚本 (Android 无头屏幕采集: 对象池复用 + CQ视频编码 + 音画时基同步)
# 生成时间: 2026-04-01
set -e

PROJECT_DIR="${1:-ZeroRecorderJava}"
printf "[*] 准备生成屏幕采集项目至目录: $PROJECT_DIR\n"
rm -rf "$PROJECT_DIR" && mkdir -p "$PROJECT_DIR" && cd "$PROJECT_DIR"

# ---------------------------------------------------
# 生成: app/build.gradle
# ---------------------------------------------------
mkdir -p "app"
cat > "app/build.gradle" <<'EOF'
plugins { id 'com.android.application' version '8.5.0' }
android {
    namespace 'com.zero.recorder'
    compileSdk 33
    defaultConfig { applicationId "com.zero.recorder"; minSdk 26; targetSdk 33 }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
}
EOF

# ---------------------------------------------------
# 生成: app/src/main/AndroidManifest.xml
# ---------------------------------------------------
mkdir -p "app/src/main"
cat > "app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:glEsVersion="0x00030002" android:required="true" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_ROUTING" />
    <application android:hasCode="true" />
</manifest>
EOF

mkdir -p "app/src/main/java/com/zero/recorder/core"
mkdir -p "app/src/main/java/com/zero/recorder/io"
mkdir -p "app/src/main/java/com/zero/recorder/video"
mkdir -p "app/src/main/java/com/zero/recorder/audio"
mkdir -p "app/src/main/java/com/zero/recorder/utils"

# ---------------------------------------------------
# 生成: utils/SystemPrivilegeUtils.java
# ---------------------------------------------------
cat > "app/src/main/java/com/zero/recorder/utils/SystemPrivilegeUtils.java" <<'EOF'
package com.zero.recorder.utils;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import java.lang.reflect.Field;

public class SystemPrivilegeUtils {
    public static void bypassHiddenApi() {
        try { 
            Object rt = Class.forName("dalvik.system.VMRuntime").getDeclaredMethod("getRuntime").invoke(null);
            Class.forName("dalvik.system.VMRuntime").getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(rt, new Object[]{new String[]{"L"}});
        } catch (Exception ignored) {}
    }

    @SuppressLint("PrivateApi")
    public static Context getShellContext() {
        try { 
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Object at = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            Context ctx = (Context) Class.forName("android.app.ActivityThread").getMethod("getSystemContext").invoke(at);
            return new ShellContextWrapper(ctx);
        } catch (Exception e) { throw new RuntimeException("获取 Shell 上下文失败", e); }
    }

    private static class ShellContextWrapper extends ContextWrapper {
        public ShellContextWrapper(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @SuppressLint("NewApi") @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        }
        @Override public Object getSystemService(String name) {
            Object service = super.getSystemService(name);
            if (service != null && Context.DISPLAY_SERVICE.equals(name)) {
                try { Field f = service.getClass().getDeclaredField("mContext"); f.setAccessible(true); f.set(service, this); } catch (Exception ignored) {}
            }
            return service;
        }
    }
}
EOF

# ---------------------------------------------------
# 生成: io/AsyncMediaMuxer.java
# ---------------------------------------------------
cat > "app/src/main/java/com/zero/recorder/io/AsyncMediaMuxer.java" <<'EOF'
package com.zero.recorder.io;

import android.media.MediaCodec;
import android.media.MediaMuxer;
import android.os.Process;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncMediaMuxer {
    private final MediaMuxer mediaMuxer;
    private final LinkedBlockingQueue<EncodedMediaFrame> frameQueue = new LinkedBlockingQueue<>(500);
    // 优化：DirectByteBuffer 对象池，减少高频写入时的 GC 停顿
    private final LinkedBlockingQueue<ByteBuffer> bufferPool = new LinkedBlockingQueue<>();
    
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isMuxerStarted = new AtomicBoolean(false);
    private final AtomicInteger addedTracksCount = new AtomicInteger(0);
    private final int expectedTracksCount;
    private Thread workerThread;

    public AsyncMediaMuxer(String outputPath, int expectedTracks) throws Exception {
        this.mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        this.expectedTracksCount = expectedTracks;
    }

    public synchronized int addTrack(android.media.MediaFormat format) {
        int trackIndex = mediaMuxer.addTrack(format);
        if (addedTracksCount.incrementAndGet() == expectedTracksCount) {
            mediaMuxer.start();
            isMuxerStarted.set(true);
        }
        return trackIndex;
    }

    public void start() {
        isRunning.set(true);
        workerThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (isRunning.get() || !frameQueue.isEmpty()) {
                try {
                    EncodedMediaFrame frame = frameQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        if (frame.info.size > 0 && isMuxerStarted.get()) {
                            mediaMuxer.writeSampleData(frame.trackIndex, frame.buffer, frame.info);
                        }
                        // 消费完毕，将堆外内存清洗并放回池中循环利用
                        bufferPool.offer(frame.buffer);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "AsyncMuxerThread");
        workerThread.start();
    }

    public void writeSampleData(int trackIndex, ByteBuffer codecBuffer, MediaCodec.BufferInfo info) {
        if (!isMuxerStarted.get()) return;
        
        // 从对象池获取 Buffer，减少运行时的内存分配
        ByteBuffer newBuf = bufferPool.poll();
        if (newBuf == null || newBuf.capacity() < info.size) {
            // 最低分配 512KB 防止小帧导致的大量重分配，最高按需扩展
            newBuf = ByteBuffer.allocateDirect(Math.max(info.size + 10240, 512 * 1024)); 
        }
        newBuf.clear();
        
        codecBuffer.position(info.offset);
        codecBuffer.limit(info.offset + info.size);
        newBuf.put(codecBuffer);
        newBuf.flip();

        MediaCodec.BufferInfo newInfo = new MediaCodec.BufferInfo();
        newInfo.set(0, info.size, info.presentationTimeUs, info.flags);
        frameQueue.offer(new EncodedMediaFrame(trackIndex, newBuf, newInfo));
    }

    public void stopAndRelease() {
        isRunning.set(false);
        if (workerThread != null) { try { workerThread.join(5000); } catch (InterruptedException ignored) {} }
        if (isMuxerStarted.get()) { mediaMuxer.stop(); mediaMuxer.release(); }
        bufferPool.clear();
    }

    private static class EncodedMediaFrame {
        final int trackIndex; final ByteBuffer buffer; final MediaCodec.BufferInfo info;
        EncodedMediaFrame(int trackIdx, ByteBuffer buf, MediaCodec.BufferInfo inf) {
            this.trackIndex = trackIdx; this.buffer = buf; this.info = inf;
        }
    }
}
EOF

# ---------------------------------------------------
# 生成: video/DisplayCapturer.java
# ---------------------------------------------------
cat > "app/src/main/java/com/zero/recorder/video/DisplayCapturer.java" <<'EOF'
package com.zero.recorder.video;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.IBinder;
import android.util.Pair;
import android.view.Surface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class DisplayCapturer {
    private final Object displayManagerGlobal;
    public volatile int logicalWidth = 0, logicalHeight = 0, rotation = 0, layerStack = 0;
    private int lastWidth = 0, lastHeight = 0, lastRotation = -1;
    public volatile boolean needsRebind = false;
    private IBinder surfaceControlToken; private VirtualDisplay virtualDisplay;

    public DisplayCapturer() throws Exception {
        this.displayManagerGlobal = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        pollDisplayConfiguration();
    }

    public void pollDisplayConfiguration() {
        try {
            Object displayInfo = displayManagerGlobal.getClass().getMethod("getDisplayInfo", int.class).invoke(displayManagerGlobal, 0);
            Field fW = displayInfo.getClass().getDeclaredField("logicalWidth"); fW.setAccessible(true); logicalWidth = fW.getInt(displayInfo);
            Field fH = displayInfo.getClass().getDeclaredField("logicalHeight"); fH.setAccessible(true); logicalHeight = fH.getInt(displayInfo);
            Field fR = displayInfo.getClass().getDeclaredField("rotation"); fR.setAccessible(true); rotation = fR.getInt(displayInfo);
            Field fL = displayInfo.getClass().getDeclaredField("layerStack"); fL.setAccessible(true); layerStack = fL.getInt(displayInfo);
            if (logicalWidth != lastWidth || logicalHeight != lastHeight || rotation != lastRotation) {
                if (lastRotation != -1) needsRebind = true;
                lastWidth = logicalWidth; lastHeight = logicalHeight; lastRotation = rotation;
            }
        } catch (Exception ignored) {}
    }

    public int getRefreshRate() {
        try {
            Object displayInfo = displayManagerGlobal.getClass().getMethod("getDisplayInfo", int.class).invoke(displayManagerGlobal, 0);
            Object mode = displayInfo.getClass().getMethod("getMode").invoke(displayInfo);
            return Math.min(Math.round((Float) mode.getClass().getMethod("getRefreshRate").invoke(mode)), 60);
        } catch (Exception e) { return 60; }
    }

    public Pair<Integer, Integer> calculateTargetSize(int limitResolution, boolean isLongEdge) {
        int maxEdge = Math.max(logicalWidth, logicalHeight), minEdge = Math.min(logicalWidth, logicalHeight);
        float ratio = 1.0f;
        if (isLongEdge) { if (maxEdge > limitResolution) ratio = (float) limitResolution / maxEdge; } 
        else { if (minEdge > limitResolution) ratio = (float) limitResolution / minEdge; }
        return new Pair<>((Math.round(logicalWidth * ratio) & ~15), (Math.round(logicalHeight * ratio) & ~15));
    }

    public void createCaptureDisplay(Context ctx, Surface surface, int targetWidth, int targetHeight) {
        surfaceControlToken = tryCreateSurfaceControlDisplay("ZeroCapture", surface, logicalWidth, logicalHeight, targetWidth, targetHeight, layerStack);
        if (surfaceControlToken == null) {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            virtualDisplay = dm.createVirtualDisplay("ZeroCapture", logicalWidth, logicalHeight, 480, surface, 3);
        }
    }

    public void resizeDisplay(Surface surface, int targetWidth, int targetHeight) {
        if (surfaceControlToken != null) {
            try {
                Class<?> tClass = Class.forName("android.view.SurfaceControl$Transaction"); Object t = tClass.getConstructor().newInstance();
                tClass.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                      .invoke(t, surfaceControlToken, 0, new Rect(0, 0, logicalWidth, logicalHeight), new Rect(0, 0, targetWidth, targetHeight));
                tClass.getMethod("apply").invoke(t);
            } catch (Exception ignored) {}
        } else if (virtualDisplay != null) {
            virtualDisplay.setSurface(surface); virtualDisplay.resize(logicalWidth, logicalHeight, 480);
        }
    }

    public void registerDisplayListener(Handler handler) {
        try {
            Class<?> lClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            Object listener = Proxy.newProxyInstance(lClass.getClassLoader(), new Class<?>[]{lClass}, (proxy, method, args) -> {
                if ("onDisplayChanged".equals(method.getName())) pollDisplayConfiguration(); return null;
            });
            for (Method m : displayManagerGlobal.getClass().getDeclaredMethods()) {
                if ("registerDisplayListener".equals(m.getName())) {
                    int pCount = m.getParameterTypes().length;
                    if (pCount == 2) m.invoke(displayManagerGlobal, listener, handler);
                    else if (pCount == 3) m.invoke(displayManagerGlobal, listener, handler, 7L);
                    else if (pCount == 4) m.invoke(displayManagerGlobal, listener, handler, 7L, "com.android.shell");
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    public void release() {
        if (surfaceControlToken != null) {
            try { try { Class.forName("android.window.DisplayControl").getMethod("destroyDisplay", IBinder.class).invoke(null, surfaceControlToken); } 
                  catch (Exception e) { Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay", IBinder.class).invoke(null, surfaceControlToken); }
            } catch (Exception ignored) {}
        }
        if (virtualDisplay != null) virtualDisplay.release();
    }

    private IBinder tryCreateSurfaceControlDisplay(String name, Surface surface, int w, int h, int encW, int encH, int layer) {
        try {
            IBinder token = null;
            try { token = (IBinder) Class.forName("android.window.DisplayControl").getMethod("createDisplay", String.class, boolean.class).invoke(null, name, false); } 
            catch (Throwable ignored) {
                try { token = (IBinder) Class.forName("android.view.SurfaceControl").getMethod("createDisplay", String.class, boolean.class, String.class).invoke(null, name, false, "zero-layer"); } 
                catch (Throwable e2) { token = (IBinder) Class.forName("android.view.SurfaceControl").getMethod("createDisplay", String.class, boolean.class).invoke(null, name, false); }
            }
            if (token == null) return null;
            Class<?> tClass = Class.forName("android.view.SurfaceControl$Transaction"); Object t = tClass.getConstructor().newInstance();
            tClass.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(t, token, surface);
            tClass.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class).invoke(t, token, 0, new Rect(0, 0, w, h), new Rect(0, 0, encW, encH));
            tClass.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(t, token, layer); tClass.getMethod("apply").invoke(t);
            return token;
        } catch (Exception e) { return null; }
    }
}
EOF

# ---------------------------------------------------
# 生成: video/SurfaceTextureRenderer.java
# ---------------------------------------------------
cat > "app/src/main/java/com/zero/recorder/video/SurfaceTextureRenderer.java" <<'EOF'
package com.zero.recorder.video;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class SurfaceTextureRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final int TIMEOUT_WAIT_FRAME_MS = 500;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY; private EGLContext eglContext = EGL14.EGL_NO_CONTEXT; private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int programId = 0, textureId = 0; private SurfaceTexture surfaceTexture; public Surface windowSurface;
    private final AtomicBoolean isFrameAvailable = new AtomicBoolean(false); private final Object frameSyncLock = new Object();
    private final HandlerThread renderThread; private final FloatBuffer positionBuffer, textureCoordBuffer;
    private int sourceWidth, sourceHeight, targetWidth, targetHeight;
    private final float[] cachedMvpMatrix = new float[16]; private int lastRotationDiff = -1, lastSourceWidth = -1, lastSourceHeight = -1;
    public volatile boolean isRunning = true;

    public SurfaceTextureRenderer(int initW, int initH) {
        this.sourceWidth = initW; this.sourceHeight = initH; this.targetWidth = initW; this.targetHeight = initH;
        renderThread = new HandlerThread("GLRenderThread", Process.THREAD_PRIORITY_DISPLAY); renderThread.start();
        positionBuffer = allocateBuffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}); textureCoordBuffer = allocateBuffer(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
        Matrix.setIdentityM(cachedMvpMatrix, 0);
    }

    public void initialize(Surface encoderSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); int[] version = new int[2]; EGL14.eglInitialize(eglDisplay, version, 0, version, 1);
        int[] configAttributes = { EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1]; int[] numConfig = new int[1]; EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, numConfig, 0);
        int[] contextAttributes = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        String vertexShader = "#version 320 es\nuniform mat4 uMVPMatrix;\nuniform mat4 uSTMatrix;\nin vec4 aPos;\nin vec4 aTex;\nout vec2 vTex;\nvoid main() {\n  gl_Position = uMVPMatrix * aPos;\n  vTex = (uSTMatrix * aTex).xy;\n}";
        String fragmentShader = "#version 320 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float;\nin vec2 vTex;\nout vec4 fragColor;\nuniform samplerExternalOES s;\nvoid main() { fragColor = texture(s, vTex); }";

        programId = GLES32.glCreateProgram();
        int vs = compileShader(GLES32.GL_VERTEX_SHADER, vertexShader); int fs = compileShader(GLES32.GL_FRAGMENT_SHADER, fragmentShader);
        GLES32.glAttachShader(programId, vs); GLES32.glAttachShader(programId, fs); GLES32.glLinkProgram(programId);

        int[] textures = new int[1]; GLES32.glGenTextures(1, textures, 0); textureId = textures[0]; GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        surfaceTexture = new SurfaceTexture(textureId); surfaceTexture.setOnFrameAvailableListener(this, new Handler(renderThread.getLooper()));
        windowSurface = new Surface(surfaceTexture); GLES32.glViewport(0, 0, targetWidth, targetHeight);
    }

    private int compileShader(int type, String source) {
        int shader = GLES32.glCreateShader(type); GLES32.glShaderSource(shader, source); GLES32.glCompileShader(shader); return shader;
    }

    public void updateSourceSize(int width, int height) { sourceWidth = width; sourceHeight = height; if (surfaceTexture != null) surfaceTexture.setDefaultBufferSize(width, height); }

    @Override public void onFrameAvailable(SurfaceTexture st) { synchronized (frameSyncLock) { isFrameAvailable.set(true); frameSyncLock.notifyAll(); } }

    public void awaitNewFrameAndRender(int currentRotation, int initialRotation) {
        synchronized (frameSyncLock) {
            while (!isFrameAvailable.get() && isRunning) { try { frameSyncLock.wait(TIMEOUT_WAIT_FRAME_MS); } catch (InterruptedException ignored) {} }
            if (!isRunning) return; isFrameAvailable.set(false);
        }
        try { surfaceTexture.updateTexImage(); } catch (Exception e) { return; }
        
        long presentationTimeNanos = surfaceTexture.getTimestamp();
        float[] transformMatrix = new float[16]; surfaceTexture.getTransformMatrix(transformMatrix); GLES32.glUseProgram(programId);
        
        int rotationDiff = (currentRotation - initialRotation + 4) % 4;
        if (rotationDiff != lastRotationDiff || sourceWidth != lastSourceWidth || sourceHeight != lastSourceHeight) {
            float rotDegrees = rotationDiff * -90f; boolean isRotated = (rotationDiff == 1 || rotationDiff == 3);
            float effectiveSrcW = isRotated ? (float) sourceHeight : (float) sourceWidth;
            float effectiveSrcH = isRotated ? (float) sourceWidth : (float) sourceHeight;
            float aspectSrc = effectiveSrcW / effectiveSrcH, aspectEnc = (float) targetWidth / targetHeight;
            float scaleX = 1f, scaleY = 1f;
            if (aspectSrc > aspectEnc) scaleY = aspectEnc / aspectSrc; else scaleX = aspectSrc / aspectEnc;
            
            Matrix.setIdentityM(cachedMvpMatrix, 0); Matrix.scaleM(cachedMvpMatrix, 0, scaleX, scaleY, 1f);
            if (rotDegrees != 0f) Matrix.rotateM(cachedMvpMatrix, 0, rotDegrees, 0f, 0f, 1f);
            lastRotationDiff = rotationDiff; lastSourceWidth = sourceWidth; lastSourceHeight = sourceHeight;
        }
        
        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(programId, "uMVPMatrix"), 1, false, cachedMvpMatrix, 0);
        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(programId, "uSTMatrix"), 1, false, transformMatrix, 0);
        int posHandle = GLES32.glGetAttribLocation(programId, "aPos"); GLES32.glEnableVertexAttribArray(posHandle); GLES32.glVertexAttribPointer(posHandle, 2, GLES32.GL_FLOAT, false, 8, positionBuffer);
        int texHandle = GLES32.glGetAttribLocation(programId, "aTex"); GLES32.glEnableVertexAttribArray(texHandle); GLES32.glVertexAttribPointer(texHandle, 2, GLES32.GL_FLOAT, false, 8, textureCoordBuffer);
        
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4);
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNanos);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private FloatBuffer allocateBuffer(float[] array) { return (FloatBuffer) ByteBuffer.allocateDirect(array.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(array).position(0); }
    public void release() {
        isRunning = false; synchronized (frameSyncLock) { frameSyncLock.notifyAll(); }
        try { if (surfaceTexture != null) surfaceTexture.release(); if (windowSurface != null) windowSurface.release(); if (renderThread != null) renderThread.quitSafely();
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) { EGL14.eglDestroySurface(eglDisplay, eglSurface); EGL14.eglDestroyContext(eglDisplay, eglContext); }
        } catch (Exception ignored) {}
    }
}
EOF

# ---------------------------------------------------
# 生成: video/VideoEncoderFactory.java
# ---------------------------------------------------
cat > "app/src/main/java/com/zero/recorder/video/VideoEncoderFactory.java" <<'EOF'
package com.zero.recorder.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Pair;
import android.view.Surface;

public class VideoEncoderFactory {
    public static Pair<MediaCodec, Surface> create(String mimeType, int width, int height, int fps) throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType(mimeType);
        try { 
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height); 
            format.setInteger(MediaFormat.KEY_PRIORITY, 0); 
            
            // 优化：使用 CQ（恒定质量）模式，优先保障视频帧清晰度
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ); 
            format.setInteger("quality", 85); // 质量因子 0-100，平衡清晰度与文件体积
            
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); 
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); 
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps); 
            format.setInteger("vendor.mtk-venc-low-latency", 1); 
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); 
            Surface surface = codec.createInputSurface(); 
            codec.start(); 
            return new Pair<>(codec, surface); 
        } catch (Exception e) { 
            codec.release(); throw e; 
        }
    }
}
EOF

# ---------------------------------------------------
# 生成: audio/AudioCaptureFactory.java
# ---------------------------------------------------
cat > "app/src/main/java/com/zero/recorder/audio/AudioCaptureFactory.java" <<'EOF'
package com.zero.recorder.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import java.lang.reflect.Method;

public class AudioCaptureFactory {
    // 3 = ROUTE_FLAG_LOOP_BACK_AND_RENDER: 允许内录的同时保留扬声器外放
    private static final int ROUTE_FLAG_LOOP_BACK_AND_RENDER = 3;

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    public static AudioRecord createBypassAudioRecord(Context ctx, int sampleRate, int channelMask, int encoding) throws Exception {
        AudioAttributes attr = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
        Class<?> mixRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object ruleBuilder = mixRuleBuilderClass.newInstance();
        mixRuleBuilderClass.getMethod("addRule", AudioAttributes.class, int.class).invoke(ruleBuilder, attr, 1);
        Object rule = mixRuleBuilderClass.getMethod("build").invoke(ruleBuilder);

        AudioFormat format = new AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(channelMask).build();
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mixBuilder = mixBuilderClass.getConstructor(Class.forName("android.media.audiopolicy.AudioMixingRule")).newInstance(rule);
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK_AND_RENDER); 
        Object audioMix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(ctx);
        policyBuilderClass.getMethod("addMix", Class.forName("android.media.audiopolicy.AudioMix")).invoke(policyBuilder, audioMix);
        Object audioPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int result = (Integer) AudioManager.class.getMethod("registerAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy")).invoke(am, audioPolicy);
        if (result != 0) throw new RuntimeException("AudioPolicy 注册失败");

        AudioRecord record = (AudioRecord) audioPolicy.getClass().getMethod("createAudioRecordSink", Class.forName("android.media.audiopolicy.AudioMix")).invoke(audioPolicy, audioMix);
        if (record == null) throw new RuntimeException("创建 AudioRecordSink 失败");
        return record;
    }
}
EOF

# ---------------------------------------------------
# 生成: core/HeadlessScreenRecorder.java
# ---------------------------------------------------
cat > "app/src/main/java/com/zero/recorder/core/HeadlessScreenRecorder.java" <<'EOF'
package com.zero.recorder.core;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Pair;
import android.view.Surface;
import com.zero.recorder.audio.AudioCaptureFactory;
import com.zero.recorder.io.AsyncMediaMuxer;
import com.zero.recorder.utils.SystemPrivilegeUtils;
import com.zero.recorder.video.DisplayCapturer;
import com.zero.recorder.video.SurfaceTextureRenderer;
import com.zero.recorder.video.VideoEncoderFactory;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HeadlessScreenRecorder {
    private static final int CODEC_DEQUEUE_TIMEOUT_US = 5_000;
    private static final long RECORDING_DURATION_MS = 20_000L;
    
    private static final int AUDIO_SAMPLE_RATE_HZ = 48000;
    private static final int AUDIO_BITRATE_BPS = 192000;
    private static final int AUDIO_MAX_INPUT_SIZE = 16384;

    // 优化：统一全局时间基准，同步音画时间戳
    private static volatile long globalBaseTimeUs = -1L;

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO); 
        Binder.clearCallingIdentity();

        System.out.println("\n[▶] ZeroRecorder 启动 (启用内存池/CQ编码/时基同步)...");
        SystemPrivilegeUtils.bypassHiddenApi();

        try {
            final Context ctx = SystemPrivilegeUtils.getShellContext();
            final DisplayCapturer display = new DisplayCapturer();
            final int initialRotation = display.rotation;
            new Thread(() -> startRecordingTask(ctx, display, initialRotation)).start();
            display.registerDisplayListener(new Handler(Looper.getMainLooper()));
            Looper.loop();
        } catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    private static void normalizeTimeBase(MediaCodec.BufferInfo info) {
        if (globalBaseTimeUs == -1L) {
            synchronized (HeadlessScreenRecorder.class) {
                if (globalBaseTimeUs == -1L) globalBaseTimeUs = info.presentationTimeUs;
            }
        }
        info.presentationTimeUs -= globalBaseTimeUs;
    }

    private static void startRecordingTask(Context ctx, DisplayCapturer display, int initialRotation) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File("/sdcard/Movies/ZeroRecorder"); if (!dir.exists()) dir.mkdirs();
        String outputPath = dir.getAbsolutePath() + "/Rec_" + timestamp + ".mp4";

        int targetFps = display.getRefreshRate();
        Pair<Integer, Integer> targetSize = null;
        Pair<MediaCodec, Surface> videoComponent = null;
        int[][] resolutions = { {0,0}, {1440,0}, {1080,0}, {1920,1} };
        String[] mimeTypes = { MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC };
        
        outer: for (String mime : mimeTypes) {
            for (int[] cap : resolutions) {
                targetSize = (cap[0] == 0) ? new Pair<>(display.logicalWidth & ~15, display.logicalHeight & ~15) : display.calculateTargetSize(cap[0], cap[1]==1);
                try { videoComponent = VideoEncoderFactory.create(mime, targetSize.first, targetSize.second, targetFps); break outer; } catch (Exception ignored) {}
            }
        }
        if (videoComponent == null) { System.err.println("[-] 硬件拒绝视频握手"); System.exit(1); return; }

        AudioRecord audioRecord = null; MediaCodec audioCodec = null; int expectedTracks = 1;
        try {
            audioRecord = AudioCaptureFactory.createBypassAudioRecord(ctx, AUDIO_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE_HZ, 2);
            aFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE_BPS);
            aFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            aFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_SIZE);
            audioCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioCodec.start();
            expectedTracks = 2;
            System.out.println("[*] 音频录制通道初始化成功。");
        } catch (Exception e) { System.err.println("[-] 音频通道初始化失败，降级为纯视频模式: " + e.getMessage()); }

        try {
            MediaCodec videoCodec = videoComponent.first;
            SurfaceTextureRenderer renderer = new SurfaceTextureRenderer(targetSize.first, targetSize.second);
            renderer.initialize(videoComponent.second); renderer.updateSourceSize(display.logicalWidth, display.logicalHeight);
            display.createCaptureDisplay(ctx, renderer.windowSurface, targetSize.first, targetSize.second);
            
            AsyncMediaMuxer asyncMuxer = new AsyncMediaMuxer(outputPath, expectedTracks);
            asyncMuxer.start();

            final AudioRecord fRecord = audioRecord; final MediaCodec fAudioCodec = audioCodec;
            Thread audioThread = null;
            if (fRecord != null && fAudioCodec != null) {
                audioThread = new Thread(() -> runAudioLoop(fRecord, fAudioCodec, asyncMuxer, renderer)); audioThread.start();
            }

            runVideoLoop(videoCodec, display, renderer, asyncMuxer, initialRotation, targetSize.first, targetSize.second);

            renderer.release(); display.release();
            if (audioThread != null) { try { audioThread.join(2000); fAudioCodec.stop(); fAudioCodec.release(); fRecord.stop(); fRecord.release(); } catch (Exception ignored) {} }
            asyncMuxer.stopAndRelease();
            System.out.println("[✔] 保存成功: " + outputPath); System.exit(0);

        } catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    private static void runVideoLoop(MediaCodec codec, DisplayCapturer display, SurfaceTextureRenderer renderer, AsyncMediaMuxer muxer, int initialRotation, int tW, int tH) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long stopTime = System.currentTimeMillis() + RECORDING_DURATION_MS;
        int trackIdx = -1;

        while (System.currentTimeMillis() < stopTime) {
            if (display.needsRebind) { display.needsRebind = false; renderer.updateSourceSize(display.logicalWidth, display.logicalHeight); display.resizeDisplay(renderer.windowSurface, tW, tH); }
            renderer.awaitNewFrameAndRender(display.rotation, initialRotation);

            try {
                while (true) {
                    int idx = codec.dequeueOutputBuffer(info, CODEC_DEQUEUE_TIMEOUT_US);
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { trackIdx = muxer.addTrack(codec.getOutputFormat());
                    } else if (idx >= 0) {
                        if (info.size > 0) {
                            normalizeTimeBase(info); // 将时间戳平移至相对 0 时刻
                            muxer.writeSampleData(trackIdx, codec.getOutputBuffer(idx), info);
                        }
                        codec.releaseOutputBuffer(idx, false);
                    } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) { break; } else break;
                }
            } catch (IllegalStateException e) { break; }
        }
        try { codec.signalEndOfInputStream(); } catch (Exception ignored) {}
        drainEncoder(codec, info, muxer, trackIdx);
        codec.stop(); codec.release();
    }

    private static void runAudioLoop(AudioRecord record, MediaCodec codec, AsyncMediaMuxer muxer, SurfaceTextureRenderer renderer) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); record.startRecording();
        byte[] buf = new byte[4096]; MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); int trackIdx = -1;

        while (renderer.isRunning) {
            int read = record.read(buf, 0, buf.length);
            if (read > 0) {
                int inIdx = codec.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    if (inBuf != null) { inBuf.clear(); inBuf.put(buf, 0, read); codec.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000L, 0); }
                }
            }
            while (true) {
                int outIdx = codec.dequeueOutputBuffer(info, 0);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { trackIdx = muxer.addTrack(codec.getOutputFormat());
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        normalizeTimeBase(info); // 将时间戳平移至相对 0 时刻
                        muxer.writeSampleData(trackIdx, codec.getOutputBuffer(outIdx), info);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                } else break;
            }
        }
    }

    private static void drainEncoder(MediaCodec codec, MediaCodec.BufferInfo info, AsyncMediaMuxer muxer, int trackIdx) {
        long start = System.currentTimeMillis();
        try {
            while (System.currentTimeMillis() - start < 2000) {
                int idx = codec.dequeueOutputBuffer(info, CODEC_DEQUEUE_TIMEOUT_US);
                if (idx >= 0) {
                    if (info.size > 0) {
                        normalizeTimeBase(info); 
                        muxer.writeSampleData(trackIdx, codec.getOutputBuffer(idx), info);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    codec.releaseOutputBuffer(idx, false);
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            }
        } catch (Exception ignored) {}
    }
}
EOF

# ---------------------------------------------------
# 生成: 编译执行脚本与 Gradle 配置
# ---------------------------------------------------
cat > "build_and_run.sh" <<'EOF'
#!/system/bin/sh
set +e
IDE_HOME="/data/user/0/com.tom.rv2ide/files"
RV2_USR="$IDE_HOME/usr"
SDK_HOME="$IDE_HOME/home/android-sdk"
export JAVA_HOME="$RV2_USR"
export PATH="$IDE_HOME/home/.gradle/wrapper/dists/gradle-9.0.0-bin/d6wjpkvcgsg3oed0qlfss3wgl/gradle-9.0.0/bin:$RV2_USR/bin:$SDK_HOME/build-tools/35.0.1:/system/bin:$PATH"
printf "\033[36m[1/2]\033[0m 正在编译项目...\n"
if ! gradle :app:assembleDebug --daemon --parallel -x lint -q; then printf "\033[31m[错误]\033[0m 编译失败！\n"; exit 1; fi
export CLASSPATH="./app/build/outputs/apk/debug/app-debug.apk"
printf "\033[36m[2/2]\033[0m 启动录制进程...\n"
app_process / "com.zero.recorder.core.HeadlessScreenRecorder" "$@"
EOF

cat > "gradle.properties" <<'EOF'
android.aapt2FromMavenOverride=/data/user/0/com.tom.rv2ide/files/home/android-sdk/build-tools/35.0.1/aapt2
org.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8
android.useAndroidX=true
EOF
cat > "local.properties" <<'EOF'
sdk.dir=/data/user/0/com.tom.rv2ide/files/home/android-sdk
EOF
cat > "settings.gradle" <<'EOF'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "ZeroRecorder"
include ':app'
EOF

