package com.zero.recorder.capture;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.IBinder;
import android.util.Pair;
import android.view.Surface;
import com.zero.recorder.RecorderLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DisplayCaptureController {
    private static final String TAG = "ZR.Display";
    private final Object displayManagerGlobal;
    private final AtomicBoolean needsRebind = new AtomicBoolean(false);

    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile int rotation;
    private volatile int layerStack;

    private int lastWidth;
    private int lastHeight;
    private int lastRotation = -1;

    private IBinder displayToken;
    private VirtualDisplay virtualDisplay;

    public DisplayCaptureController(Object displayManagerGlobal) {
        this.displayManagerGlobal = displayManagerGlobal;
    }

    public void refreshDisplayInfo() {
        try {
            Object displayInfo = displayManagerGlobal.getClass().getMethod("getDisplayInfo", int.class).invoke(displayManagerGlobal, 0);
            screenWidth = readIntField(displayInfo, "logicalWidth");
            screenHeight = readIntField(displayInfo, "logicalHeight");
            rotation = readIntField(displayInfo, "rotation");
            layerStack = readIntField(displayInfo, "layerStack");

            if (screenWidth != lastWidth || screenHeight != lastHeight || rotation != lastRotation) {
                if (lastRotation != -1) {
                    needsRebind.set(true);
                    RecorderLog.i(TAG, "Display changed to " + screenWidth + "x" + screenHeight + " rotation=" + rotation);
                }
                lastWidth = screenWidth;
                lastHeight = screenHeight;
                lastRotation = rotation;
            }
        } catch (Exception ignored) {
        }
    }

    public void registerDisplayListener(Handler handler) {
        try {
            Class<?> listenerClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onDisplayChanged".equals(method.getName())) {
                            refreshDisplayInfo();
                        }
                        return null;
                    }
            );

            for (Method method : displayManagerGlobal.getClass().getDeclaredMethods()) {
                if (!"registerDisplayListener".equals(method.getName())) {
                    continue;
                }

                int parameterCount = method.getParameterTypes().length;
                if (parameterCount == 2) {
                    method.invoke(displayManagerGlobal, listener, handler);
                } else if (parameterCount == 3) {
                    method.invoke(displayManagerGlobal, listener, handler, 7L);
                } else if (parameterCount == 4) {
                    method.invoke(displayManagerGlobal, listener, handler, 7L, "com.android.shell");
                }
                break;
            }
        } catch (Exception ignored) {
        }
    }

    public Pair<Integer, Integer> computeTargetSize(int sizeLimit, boolean limitLongEdge) {
        int sourceLongEdge = Math.max(screenWidth, screenHeight);
        int sourceShortEdge = Math.min(screenWidth, screenHeight);
        float scale = 1.0f;

        if (limitLongEdge) {
            if (sourceLongEdge > sizeLimit) {
                scale = (float) sizeLimit / sourceLongEdge;
            }
        } else if (sourceShortEdge > sizeLimit) {
            scale = (float) sizeLimit / sourceShortEdge;
        }

        return new Pair<>((Math.round(screenWidth * scale) & ~15), (Math.round(screenHeight * scale) & ~15));
    }

    public int detectRefreshRate() {
        try {
            Object displayInfo = displayManagerGlobal.getClass().getMethod("getDisplayInfo", int.class).invoke(displayManagerGlobal, 0);
            Object mode = displayInfo.getClass().getMethod("getMode").invoke(displayInfo);
            float refreshRate = (Float) mode.getClass().getMethod("getRefreshRate").invoke(mode);
            return Math.min(Math.round(refreshRate), 60);
        } catch (Exception ignored) {
            return 60;
        }
    }

    public void bindCaptureSurface(Context context, Surface surface, int encoderWidth, int encoderHeight) {
        releaseCaptureSurface();
        displayToken = tryCreateSurfaceControlDisplay(surface, screenWidth, screenHeight, encoderWidth, encoderHeight, layerStack);
        if (displayToken == null) {
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            virtualDisplay = displayManager.createVirtualDisplay("ZeroCapture", screenWidth, screenHeight, 480, surface, 3);
            RecorderLog.w(TAG, "Bound capture via VirtualDisplay source=" + screenWidth + "x" + screenHeight
                    + " target=" + encoderWidth + "x" + encoderHeight);
        } else {
            RecorderLog.i(TAG, "Bound capture via SurfaceControl source=" + screenWidth + "x" + screenHeight
                    + " target=" + encoderWidth + "x" + encoderHeight);
        }
    }

    public boolean consumePendingRebind() {
        return needsRebind.getAndSet(false);
    }

    public void releaseCaptureSurface() {
        if (displayToken != null) {
            RecorderLog.i(TAG, "Releasing SurfaceControl display");
            destroySurfaceControlDisplay(displayToken);
            displayToken = null;
        }
        if (virtualDisplay != null) {
            RecorderLog.i(TAG, "Releasing VirtualDisplay");
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public int getRotation() {
        return rotation;
    }

    private static int readIntField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static IBinder tryCreateSurfaceControlDisplay(Surface surface, int sourceWidth, int sourceHeight,
            int encoderWidth, int encoderHeight, int layerStack) {
        try {
            IBinder token;
            try {
                token = (IBinder) Class.forName("android.window.DisplayControl")
                        .getMethod("createDisplay", String.class, boolean.class)
                        .invoke(null, "ZeroCapture", false);
            } catch (Throwable ignored) {
                try {
                    token = (IBinder) Class.forName("android.view.SurfaceControl")
                            .getMethod("createDisplay", String.class, boolean.class, String.class)
                            .invoke(null, "ZeroCapture", false, "zero-layer");
                } catch (Throwable ignoredAgain) {
                    token = (IBinder) Class.forName("android.view.SurfaceControl")
                            .getMethod("createDisplay", String.class, boolean.class)
                            .invoke(null, "ZeroCapture", false);
                }
            }

            if (token == null) {
                return null;
            }

            Class<?> transactionClass = Class.forName("android.view.SurfaceControl$Transaction");
            Object transaction = transactionClass.getConstructor().newInstance();
            transactionClass.getMethod("setDisplaySurface", IBinder.class, Surface.class)
                    .invoke(transaction, token, surface);
            transactionClass.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(transaction, token, 0,
                            new Rect(0, 0, sourceWidth, sourceHeight),
                            new Rect(0, 0, encoderWidth, encoderHeight));
            transactionClass.getMethod("setDisplayLayerStack", IBinder.class, int.class)
                    .invoke(transaction, token, layerStack);
            transactionClass.getMethod("apply").invoke(transaction);
            return token;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void destroySurfaceControlDisplay(IBinder token) {
        try {
            try {
                Class.forName("android.window.DisplayControl")
                        .getMethod("destroyDisplay", IBinder.class)
                        .invoke(null, token);
            } catch (Exception ignored) {
                Class.forName("android.view.SurfaceControl")
                        .getMethod("destroyDisplay", IBinder.class)
                        .invoke(null, token);
            }
        } catch (Exception ignored) {
        }
    }
}
