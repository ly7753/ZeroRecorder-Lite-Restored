package com.zero.recorder.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;
import com.zero.recorder.system.ShellEnvironment;

public final class AudioCaptureFactory {
    private static final int AUDIO_SOURCE_REMOTE_SUBMIX = 8;
    private static final int LEGACY_LOOPBACK_ROUTE_FLAGS = 3;
    private static final int RULE_MATCH_ATTRIBUTE_USAGE = 1;
    private static final int INIT_POLL_COUNT = 10;
    private static final long INIT_POLL_DELAY_MS = 50L;

    private AudioCaptureFactory() {
    }

    public static AudioCaptureSession createBestEffortSession(Context shellContext) throws Exception {
        Exception lastError = null;
        int[] sampleRates = {48_000, 44_100};
        int[] channelMasks = {AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO};

        for (int sampleRate : sampleRates) {
            for (int channelMask : channelMasks) {
                System.out.println("[*] Trying audio config sampleRate=" + sampleRate + " channelMask=" + channelMask);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        return createPlaybackPolicySession(shellContext, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                    } catch (Exception playbackError) {
                        System.err.println("[-] Playback capture rejected sampleRate=" + sampleRate
                                + " channelMask=" + channelMask + ": " + playbackError.getMessage());
                        lastError = playbackError;
                    }
                }

                try {
                    return createDirectRemoteSubmixSession(shellContext, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                } catch (Exception directError) {
                    System.err.println("[-] Direct audio capture rejected sampleRate=" + sampleRate
                            + " channelMask=" + channelMask + ": " + directError.getMessage());
                    lastError = directError;
                }

                try {
                    return createLegacyPolicySession(shellContext, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                } catch (Exception legacyError) {
                    System.err.println("[-] Policy audio capture rejected sampleRate=" + sampleRate
                            + " channelMask=" + channelMask + ": " + legacyError.getMessage());
                    lastError = legacyError;
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new RuntimeException("No audio capture configuration succeeded");
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static AudioCaptureSession createPlaybackPolicySession(Context shellContext, int sampleRate, int channelMask, int encoding) throws Exception {
        Exception lastError = null;
        Context systemContext = ShellEnvironment.getSystemContext();
        Context[] candidateContexts = {shellContext, systemContext};
        boolean[] privilegedFlags = {true, false};

        for (Context context : candidateContexts) {
            for (boolean privileged : privilegedFlags) {
                String contextName;
                try {
                    contextName = context.getPackageName();
                } catch (Exception e) {
                    contextName = "<unknown>";
                }

                try {
                    System.out.println("[*] Trying playback policy context=" + contextName + " privileged=" + privileged);
                    return createPlaybackPolicySessionOnce(context, sampleRate, channelMask, encoding, privileged);
                } catch (Exception e) {
                    System.err.println("[-] Playback policy failed context=" + contextName
                            + " privileged=" + privileged + ": " + e.getMessage());
                    lastError = e;
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new RuntimeException("No playback policy configuration succeeded");
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static AudioCaptureSession createPlaybackPolicySessionOnce(
            Context context,
            int sampleRate,
            int channelMask,
            int encoding,
            boolean privileged
    ) throws Exception {
        Class<?> audioMixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object ruleBuilder = mixRuleBuilderClass.newInstance();

        try {
            int mixRolePlayers = audioMixingRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null);
            mixRuleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(ruleBuilder, mixRolePlayers);
        } catch (NoSuchMethodException ignored) {
        }

        AudioAttributes mediaAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
        mixRuleBuilderClass.getMethod("addRule", AudioAttributes.class, int.class)
                .invoke(ruleBuilder, mediaAttributes, RULE_MATCH_ATTRIBUTE_USAGE);

        invokeIfPresent(mixRuleBuilderClass, ruleBuilder, "allowPrivilegedPlaybackCapture",
                new Class<?>[]{boolean.class}, new Object[]{privileged});
        invokeIfPresent(mixRuleBuilderClass, ruleBuilder, "voiceCommunicationCaptureAllowed",
                new Class<?>[]{boolean.class}, new Object[]{true});
        Object rule = mixRuleBuilderClass.getMethod("build").invoke(ruleBuilder);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        Class<?> audioMixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mixBuilder = mixBuilderClass.getConstructor(audioMixingRuleClass).newInstance(rule);
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        int loopBackRender = audioMixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, loopBackRender);
        Object audioMix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        Class<?> audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(context);
        policyBuilderClass.getMethod("addMix", audioMixClass).invoke(policyBuilder, audioMix);
        Object audioPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int result = (Integer) AudioManager.class
                .getMethod("registerAudioPolicy", audioPolicyClass)
                .invoke(audioManager, audioPolicy);
        if (result != 0) {
            throw new RuntimeException("registerAudioPolicy failed: " + result);
        }

        AudioRecord record = null;
        try {
            record = (AudioRecord) audioPolicyClass
                    .getMethod("createAudioRecordSink", audioMixClass)
                    .invoke(audioPolicy, audioMix);
            ensureInitialized(record, "Playback AudioRecord", sampleRate, channelMask);
            return new AudioCaptureSession(audioManager, audioPolicy, record, sampleRate, channelMask, encoding, "playback");
        } catch (Exception e) {
            releaseRecord(record);
            unregisterPolicy(audioManager, audioPolicy);
            throw e;
        }
    }

    @SuppressLint({"MissingPermission", "WrongConstant"})
    private static AudioCaptureSession createDirectRemoteSubmixSession(Context shellContext, int sampleRate, int channelMask, int encoding) throws Exception {
        int channelCount = channelMask == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
        try {
            AudioRecord reflectiveRecord = ShellEnvironment.createAudioRecordReflectively(
                    shellContext,
                    AUDIO_SOURCE_REMOTE_SUBMIX,
                    sampleRate,
                    channelMask,
                    channelCount,
                    channelMask,
                    encoding
            );
            ensureInitialized(reflectiveRecord, "Reflective Direct AudioRecord", sampleRate, channelMask);
            return new AudioCaptureSession(null, null, reflectiveRecord, sampleRate, channelMask, encoding, "direct-reflective");
        } catch (Exception e) {
            System.err.println("[-] Reflective direct capture failed: " + e.getMessage());
        }

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding);
        if (minBufferSize <= 0) {
            minBufferSize = 4096;
        }

        AudioRecord.Builder builder = new AudioRecord.Builder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setContext(shellContext);
        }
        builder.setAudioSource(AUDIO_SOURCE_REMOTE_SUBMIX);
        builder.setAudioFormat(format);
        builder.setBufferSizeInBytes(Math.max(minBufferSize * 8, 4096));

        AudioRecord record = builder.build();
        try {
            ensureInitialized(record, "Direct AudioRecord", sampleRate, channelMask);
            return new AudioCaptureSession(null, null, record, sampleRate, channelMask, encoding, "direct");
        } catch (Exception e) {
            releaseRecord(record);
            throw e;
        }
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static AudioCaptureSession createLegacyPolicySession(Context shellContext, int sampleRate, int channelMask, int encoding) throws Exception {
        AudioAttributes mediaAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();

        Class<?> audioMixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object ruleBuilder = mixRuleBuilderClass.newInstance();
        mixRuleBuilderClass.getMethod("addRule", AudioAttributes.class, int.class)
                .invoke(ruleBuilder, mediaAttributes, RULE_MATCH_ATTRIBUTE_USAGE);
        Object rule = mixRuleBuilderClass.getMethod("build").invoke(ruleBuilder);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        Class<?> audioMixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mixBuilder = mixBuilderClass.getConstructor(audioMixingRuleClass).newInstance(rule);
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, LEGACY_LOOPBACK_ROUTE_FLAGS);
        Object audioMix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        Class<?> audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(shellContext);
        policyBuilderClass.getMethod("addMix", audioMixClass).invoke(policyBuilder, audioMix);
        Object audioPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

        AudioManager audioManager = (AudioManager) shellContext.getSystemService(Context.AUDIO_SERVICE);
        int result = (Integer) AudioManager.class
                .getMethod("registerAudioPolicy", audioPolicyClass)
                .invoke(audioManager, audioPolicy);
        if (result != 0) {
            throw new RuntimeException("registerAudioPolicy failed: " + result);
        }

        AudioRecord record = null;
        try {
            record = (AudioRecord) audioPolicyClass
                    .getMethod("createAudioRecordSink", audioMixClass)
                    .invoke(audioPolicy, audioMix);
            ensureInitialized(record, "Policy AudioRecord", sampleRate, channelMask);
            return new AudioCaptureSession(audioManager, audioPolicy, record, sampleRate, channelMask, encoding, "policy");
        } catch (Exception e) {
            releaseRecord(record);
            unregisterPolicy(audioManager, audioPolicy);
            throw e;
        }
    }

    private static void ensureInitialized(AudioRecord record, String label, int sampleRate, int channelMask) {
        if (record == null) {
            throw new RuntimeException(label + " was not created");
        }

        waitForInitialized(record);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new RuntimeException(
                    label + " is not initialized for sampleRate=" + sampleRate
                            + ", channelMask=" + channelMask
                            + ", actualSampleRate=" + record.getSampleRate()
                            + ", actualChannelCount=" + record.getChannelCount()
            );
        }
    }

    private static void waitForInitialized(AudioRecord record) {
        for (int i = 0; i < INIT_POLL_COUNT && record.getState() != AudioRecord.STATE_INITIALIZED; i++) {
            try {
                Thread.sleep(INIT_POLL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void releaseRecord(AudioRecord record) {
        if (record == null) {
            return;
        }
        try {
            record.release();
        } catch (Exception ignored) {
        }
    }

    private static void unregisterPolicy(AudioManager audioManager, Object audioPolicy) {
        if (audioManager == null || audioPolicy == null) {
            return;
        }
        try {
            AudioManager.class
                    .getMethod("unregisterAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
                    .invoke(audioManager, audioPolicy);
        } catch (Exception ignored) {
        }
    }

    private static void invokeIfPresent(Class<?> clazz, Object target, String methodName, Class<?>[] parameterTypes, Object[] args) throws Exception {
        try {
            clazz.getMethod(methodName, parameterTypes).invoke(target, args);
        } catch (NoSuchMethodException ignored) {
        }
    }

    public static final class AudioCaptureSession {
        public final AudioRecord record;
        public final int sampleRate;
        public final int channelMask;
        public final int channelCount;
        public final int encoding;
        public final String mode;

        private final AudioManager audioManager;
        private final Object audioPolicy;

        AudioCaptureSession(AudioManager audioManager, Object audioPolicy, AudioRecord record,
                int sampleRate, int channelMask, int encoding, String mode) {
            this.audioManager = audioManager;
            this.audioPolicy = audioPolicy;
            this.record = record;
            this.sampleRate = sampleRate;
            this.channelMask = channelMask;
            this.channelCount = channelMask == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
            this.encoding = encoding;
            this.mode = mode;
        }

        public void release() {
            releaseRecord(record);
            unregisterPolicy(audioManager, audioPolicy);
        }
    }
}
