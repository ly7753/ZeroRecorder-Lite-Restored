package com.zero.recorder.system;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Looper;
import android.os.Parcel;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi"})
public final class ShellEnvironment {
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final int SHELL_UID = 2000;

    private static final Class<?> ACTIVITY_THREAD_CLASS;
    private static final Object ACTIVITY_THREAD;

    @SuppressLint("StaticFieldLeak")
    private static Context systemContext;

    @SuppressLint("StaticFieldLeak")
    private static Context shellContext;

    static {
        try {
            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
            Constructor<?> activityThreadConstructor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
            activityThreadConstructor.setAccessible(true);
            ACTIVITY_THREAD = activityThreadConstructor.newInstance();

            Field currentActivityThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
            currentActivityThreadField.setAccessible(true);
            currentActivityThreadField.set(null, ACTIVITY_THREAD);

            Field systemThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread");
            systemThreadField.setAccessible(true);
            systemThreadField.setBoolean(ACTIVITY_THREAD, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ShellEnvironment() {
    }

    public static void bypassHiddenApi() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null);
            vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, new Object[]{new String[]{"L"}});
        } catch (Exception e) {
            System.err.println("[!] Hidden API bypass failed: " + e.getMessage());
        }
    }

    public static void applyWorkarounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fillConfigurationController();
        }
        fillBoundApplication();
        fillInitialApplication();
    }

    public static Context getSystemContext() {
        try {
            if (systemContext == null) {
                if (Looper.getMainLooper() == null) {
                    Looper.prepareMainLooper();
                }
                Method getSystemContextMethod = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
                systemContext = (Context) getSystemContextMethod.invoke(ACTIVITY_THREAD);
            }
            return systemContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire system context", e);
        }
    }

    public static Context getShellContext() {
        try {
            if (shellContext == null) {
                Context baseContext = getSystemContext();
                Context shellPackageContext = baseContext;
                try {
                    shellPackageContext = baseContext.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
                } catch (Exception e) {
                    System.err.println("[!] Falling back to system context for shell package: " + e.getMessage());
                }
                shellContext = new ShellContext(shellPackageContext);
            }
            return shellContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire shell context", e);
        }
    }

    public static Object getDisplayManagerGlobal() {
        try {
            return Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire DisplayManagerGlobal", e);
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    @SuppressLint({"WrongConstant", "MissingPermission"})
    public static AudioRecord createAudioRecordReflectively(Context context, int source, int sampleRate,
            int channelConfig, int channelCount, int channelMask, int encoding) throws Exception {
        Constructor<AudioRecord> audioRecordConstructor = AudioRecord.class.getDeclaredConstructor(long.class);
        audioRecordConstructor.setAccessible(true);
        AudioRecord audioRecord = audioRecordConstructor.newInstance(0L);

        setDeclaredField(AudioRecord.class, audioRecord, "mRecordingState", AudioRecord.RECORDSTATE_STOPPED);

        Looper initLooper = Looper.myLooper();
        if (initLooper == null) {
            initLooper = Looper.getMainLooper();
        }
        setDeclaredField(AudioRecord.class, audioRecord, "mInitializationLooper", initLooper);

        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
        Method setInternalCapturePresetMethod = AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class);
        setInternalCapturePresetMethod.invoke(attributesBuilder, source);
        AudioAttributes audioAttributes = attributesBuilder.build();
        setDeclaredField(AudioRecord.class, audioRecord, "mAudioAttributes", audioAttributes);

        Method audioParamCheckMethod = AudioRecord.class.getDeclaredMethod("audioParamCheck", int.class, int.class, int.class);
        audioParamCheckMethod.setAccessible(true);
        audioParamCheckMethod.invoke(audioRecord, source, sampleRate, encoding);

        setDeclaredField(AudioRecord.class, audioRecord, "mChannelCount", channelCount);
        setDeclaredField(AudioRecord.class, audioRecord, "mChannelMask", channelMask);

        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding);
        int bufferSizeInBytes = Math.max(minBufferSize, 4096) * 8;

        Method audioBufferSizeCheckMethod = AudioRecord.class.getDeclaredMethod("audioBuffSizeCheck", int.class);
        audioBufferSizeCheckMethod.setAccessible(true);
        audioBufferSizeCheckMethod.invoke(audioRecord, bufferSizeInBytes);

        int[] sampleRateArray = new int[]{sampleRate};
        int[] sessionIds = new int[]{AudioManager.AUDIO_SESSION_ID_GENERATE};
        int initResult;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod(
                    "native_setup",
                    Object.class, Object.class, int[].class, int.class, int.class, int.class, int.class, int[].class, String.class, long.class
            );
            nativeSetupMethod.setAccessible(true);
            initResult = (Integer) nativeSetupMethod.invoke(
                    audioRecord,
                    new WeakReference<>(audioRecord),
                    audioAttributes,
                    sampleRateArray,
                    channelMask,
                    0,
                    audioRecord.getAudioFormat(),
                    bufferSizeInBytes,
                    sessionIds,
                    context.getOpPackageName(),
                    0L
            );
        } else {
            AttributionSource attributionSource = context.getAttributionSource();
            Method asScopedParcelStateMethod = AttributionSource.class.getDeclaredMethod("asScopedParcelState");
            asScopedParcelStateMethod.setAccessible(true);

            try (AutoCloseable scopedParcelState = (AutoCloseable) asScopedParcelStateMethod.invoke(attributionSource)) {
                Method getParcelMethod = scopedParcelState.getClass().getDeclaredMethod("getParcel");
                Parcel attributionSourceParcel = (Parcel) getParcelMethod.invoke(scopedParcelState);

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod(
                            "native_setup",
                            Object.class, Object.class, int[].class, int.class, int.class, int.class, int.class, int[].class,
                            Parcel.class, long.class, int.class
                    );
                    nativeSetupMethod.setAccessible(true);
                    initResult = (Integer) nativeSetupMethod.invoke(
                            audioRecord,
                            new WeakReference<>(audioRecord),
                            audioAttributes,
                            sampleRateArray,
                            channelMask,
                            0,
                            audioRecord.getAudioFormat(),
                            bufferSizeInBytes,
                            sessionIds,
                            attributionSourceParcel,
                            0L,
                            0
                    );
                } else {
                    Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod(
                            "native_setup",
                            Object.class, Object.class, int[].class, int.class, int.class, int.class, int.class, int[].class,
                            Parcel.class, long.class, int.class, int.class
                    );
                    nativeSetupMethod.setAccessible(true);
                    initResult = (Integer) nativeSetupMethod.invoke(
                            audioRecord,
                            new WeakReference<>(audioRecord),
                            audioAttributes,
                            sampleRateArray,
                            channelMask,
                            0,
                            audioRecord.getAudioFormat(),
                            bufferSizeInBytes,
                            sessionIds,
                            attributionSourceParcel,
                            0L,
                            0,
                            0
                    );
                }
            }
        }

        if (initResult != AudioRecord.SUCCESS) {
            throw new RuntimeException("Cannot create AudioRecord, native_setup=" + initResult);
        }

        setDeclaredField(AudioRecord.class, audioRecord, "mSampleRate", sampleRateArray[0]);
        setDeclaredField(AudioRecord.class, audioRecord, "mSessionId", sessionIds[0]);
        setDeclaredField(AudioRecord.class, audioRecord, "mState", AudioRecord.STATE_INITIALIZED);
        return audioRecord;
    }

    private static void fillBoundApplication() {
        try {
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> appBindDataConstructor = appBindDataClass.getDeclaredConstructor();
            appBindDataConstructor.setAccessible(true);
            Object appBindData = appBindDataConstructor.newInstance();

            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = SHELL_PACKAGE;
            applicationInfo.uid = SHELL_UID;
            setDeclaredField(appBindDataClass, appBindData, "appInfo", applicationInfo);
            setDeclaredField(ACTIVITY_THREAD_CLASS, ACTIVITY_THREAD, "mBoundApplication", appBindData);
        } catch (Throwable e) {
            System.err.println("[!] Could not fill app info: " + e.getMessage());
        }
    }

    private static void fillInitialApplication() {
        try {
            Application application = Instrumentation.newApplication(Application.class, getShellContext());
            setDeclaredField(ACTIVITY_THREAD_CLASS, ACTIVITY_THREAD, "mInitialApplication", application);
        } catch (Throwable e) {
            System.err.println("[!] Could not fill app context: " + e.getMessage());
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> configurationControllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> configurationControllerConstructor =
                    configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass);
            configurationControllerConstructor.setAccessible(true);
            Object configurationController = configurationControllerConstructor.newInstance(ACTIVITY_THREAD);
            setDeclaredField(ACTIVITY_THREAD_CLASS, ACTIVITY_THREAD, "mConfigurationController", configurationController);
        } catch (Throwable e) {
            System.err.println("[!] Could not fill configuration controller: " + e.getMessage());
        }
    }

    private static void setDeclaredField(Class<?> owner, Object target, String fieldName, Object value) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class ShellContext extends ContextWrapper {
        private final ApplicationInfo shellApplicationInfo;

        ShellContext(Context base) {
            super(base);

            ApplicationInfo appInfo = null;
            try {
                appInfo = base.getApplicationInfo();
                if (appInfo != null) {
                    appInfo.packageName = SHELL_PACKAGE;
                    appInfo.uid = SHELL_UID;
                }
            } catch (Exception ignored) {
            }
            shellApplicationInfo = appInfo;
        }

        @Override
        public String getPackageName() {
            return SHELL_PACKAGE;
        }

        @Override
        public String getOpPackageName() {
            return SHELL_PACKAGE;
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            return shellApplicationInfo != null ? shellApplicationInfo : super.getApplicationInfo();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @SuppressLint("NewApi")
        @Override
        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build();
        }

        @Override
        public Object getSystemService(String name) {
            Object service = super.getSystemService(name);
            if (service != null) {
                patchServiceContext(service);
            }
            return service;
        }

        private void patchServiceContext(Object service) {
            Class<?> current = service.getClass();
            while (current != null) {
                try {
                    Field contextField = current.getDeclaredField("mContext");
                    contextField.setAccessible(true);
                    contextField.set(service, this);
                    return;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                } catch (Exception ignored) {
                    return;
                }
            }
        }
    }
}
