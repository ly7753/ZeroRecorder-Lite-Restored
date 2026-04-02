package com.zero.recorder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Pair;
import com.zero.recorder.audio.AudioCaptureFactory;
import com.zero.recorder.capture.DisplayCaptureController;
import com.zero.recorder.gl.GlFrameRenderer;
import com.zero.recorder.media.AsyncMp4Muxer;
import com.zero.recorder.media.VideoEncoderFactory;
import com.zero.recorder.system.ShellEnvironment;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("WrongConstant")
public class MainRecorder {
    private static final String TAG = "ZR.Main";
    private static final long CODEC_DEQUEUE_TIMEOUT_US = 5_000L;
    private static final long RECORDING_DURATION_MS = 20_000L;
    private static final long FIRST_FRAME_TIMEOUT_MS = 1_500L;
    private static final int MAX_STARTUP_REBINDS = 2;
    private static final int AUDIO_BITRATE_BPS = 192_000;
    private static final int AUDIO_MAX_INPUT_SIZE = 16_384;
    private static final int AUDIO_BUFFER_SIZE = 4_096;
    private static final int VIDEO_BITRATE_BPS = 15_000_000;

    private static volatile long globalBaseTimeUs = -1L;

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO);
        Binder.clearCallingIdentity();

        RecorderLog.i(TAG, "ZeroRecorder starting");
        ShellEnvironment.bypassHiddenApi();
        ShellEnvironment.applyWorkarounds();

        Context shellContext = ShellEnvironment.getShellContext();
        DisplayCaptureController displayController =
                new DisplayCaptureController(ShellEnvironment.getDisplayManagerGlobal());
        displayController.refreshDisplayInfo();

        logContext(shellContext);
        globalBaseTimeUs = -1L;

        int initialRotation = displayController.getRotation();
        new Thread(() -> runRecording(shellContext, displayController, initialRotation), "RecorderMainThread").start();
        displayController.registerDisplayListener(new Handler(Looper.getMainLooper()));
        Looper.loop();
    }

    private static void runRecording(Context shellContext, DisplayCaptureController displayController, int initialRotation) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        String outputPath = buildOutputPath();
        int fps = displayController.detectRefreshRate();
        List<VideoCandidate> candidates = buildVideoCandidates(displayController, fps);
        if (candidates.isEmpty()) {
            RecorderLog.e(TAG, "No usable video encoder found");
            System.exit(1);
            return;
        }

        Exception lastFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            VideoCandidate candidate = candidates.get(i);
            if (i > 0) {
                RecorderLog.w(TAG, "Retrying with fallback video profile "
                        + candidate.codecLabel + " " + candidate.width + "x" + candidate.height
                        + " @" + candidate.fps + "fps");
            }

            RecordingAttemptResult result =
                    attemptRecording(shellContext, displayController, initialRotation, outputPath, candidate);
            if (result.success) {
                RecorderLog.i(TAG, "Saved to " + outputPath);
                System.exit(0);
                return;
            }

            lastFailure = result.failure;
            cleanupFailedOutput(outputPath);
            globalBaseTimeUs = -1L;
        }

        if (lastFailure != null) {
            lastFailure.printStackTrace();
        }
        System.exit(1);
    }

    private static RecordingAttemptResult attemptRecording(
            Context shellContext,
            DisplayCaptureController displayController,
            int initialRotation,
            String outputPath,
            VideoCandidate candidate
    ) {
        VideoSetup videoSetup = null;
        MediaCodec audioCodec = null;
        AudioCaptureFactory.AudioCaptureSession audioSession = null;
        Thread audioThread = null;
        AsyncMp4Muxer muxer = null;
        GlFrameRenderer renderer = null;
        AtomicBoolean recordingActive = new AtomicBoolean(true);
        int expectedTracks = 1;

        try {
            videoSetup = createVideoSetup(candidate);

            try {
                audioSession = AudioCaptureFactory.createBestEffortSession(shellContext);
                audioCodec = createAudioEncoder(audioSession);
                expectedTracks = 2;
                RecorderLog.i(TAG, "Audio capture enabled. mode=" + audioSession.mode
                        + " sampleRate=" + audioSession.sampleRate
                        + " channels=" + audioSession.channelCount);
            } catch (Exception e) {
                releaseAudioResources(audioCodec, audioSession);
                audioCodec = null;
                audioSession = null;
                RecorderLog.w(TAG, "Audio init failed, falling back to video only: " + e.getMessage());
            }

            muxer = new AsyncMp4Muxer(outputPath, expectedTracks);
            muxer.start();

            renderer = new GlFrameRenderer(videoSetup.width, videoSetup.height);
            renderer.initialize(videoSetup.inputSurface);
            renderer.setEncoderSize(videoSetup.width, videoSetup.height);
            renderer.updateSourceSize(displayController.getScreenWidth(), displayController.getScreenHeight());
            displayController.bindCaptureSurface(shellContext, renderer.getInputSurface(), videoSetup.width, videoSetup.height);

            if (audioSession != null && audioCodec != null) {
                AudioRecord audioRecord = audioSession.record;
                MediaCodec finalAudioCodec = audioCodec;
                AsyncMp4Muxer finalMuxer = muxer;
                audioThread = new Thread(
                        () -> runAudioLoop(audioRecord, finalAudioCodec, finalMuxer, recordingActive),
                        "AudioCaptureThread"
                );
                audioThread.start();
            }

            RecorderLog.i(TAG, "Track count: " + expectedTracks);
            RecorderLog.i(TAG, "Recording " + videoSetup.width + "x" + videoSetup.height
                    + " using " + videoSetup.codecLabel + " @" + videoSetup.fps + "fps");
            runVideoLoop(displayController, shellContext, renderer, muxer, videoSetup.codec,
                    initialRotation, videoSetup.fps, videoSetup.width, videoSetup.height, recordingActive);

            joinQuietly(audioThread, 2000);
            finalizeVideo(videoSetup.codec, muxer);
            videoSetup.codecReleased = true;
            releaseAudioResources(audioCodec, audioSession);
            audioCodec = null;
            audioSession = null;

            muxer.stopAndRelease();
            muxer = null;

            renderer.release();
            renderer = null;
            return RecordingAttemptResult.success();
        } catch (Exception e) {
            recordingActive.set(false);
            RecorderLog.e(TAG, "Video profile failed: " + candidate.codecLabel + " "
                    + candidate.width + "x" + candidate.height + " @" + candidate.fps + "fps"
                    + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return RecordingAttemptResult.failure(e);
        } finally {
            recordingActive.set(false);
            displayController.releaseCaptureSurface();
            joinQuietly(audioThread, 1000);
            if (videoSetup != null && !videoSetup.codecReleased) {
                releaseVideoCodec(videoSetup.codec);
            }
            releaseAudioResources(audioCodec, audioSession);
            if (muxer != null) {
                muxer.stopAndRelease();
            }
            if (renderer != null) {
                renderer.release();
            }
        }
    }

    private static void runVideoLoop(
            DisplayCaptureController displayController,
            Context shellContext,
            GlFrameRenderer renderer,
            AsyncMp4Muxer muxer,
            MediaCodec videoCodec,
            int initialRotation,
            int fps,
            int targetWidth,
            int targetHeight,
            AtomicBoolean recordingActive
    ) {
        MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
        int videoTrackIndex = -1;
        long stopTime = System.currentTimeMillis() + RECORDING_DURATION_MS;
        long firstFrameDeadline = System.currentTimeMillis() + FIRST_FRAME_TIMEOUT_MS;
        int startupRebinds = 0;
        boolean firstFrameRendered = false;
        long firstEncodedSampleDeadline = System.currentTimeMillis() + FIRST_FRAME_TIMEOUT_MS;
        boolean firstVideoSampleWritten = false;

        while (System.currentTimeMillis() < stopTime) {
            if (displayController.consumePendingRebind()) {
                renderer.updateSourceSize(displayController.getScreenWidth(), displayController.getScreenHeight());
                displayController.bindCaptureSurface(shellContext, renderer.getInputSurface(), targetWidth, targetHeight);
            }

            float rotationFix = (initialRotation - displayController.getRotation()) * 90f;
            if (rotationFix < 0) {
                rotationFix += 360f;
            }
            boolean receivedNewFrame = renderer.awaitAndDraw(rotationFix, fps);
            if (!firstFrameRendered) {
                if (receivedNewFrame) {
                    firstFrameRendered = true;
                } else if (System.currentTimeMillis() >= firstFrameDeadline) {
                    if (startupRebinds >= MAX_STARTUP_REBINDS) {
                        throw new IllegalStateException("Timed out waiting for first video frame");
                    }
                    startupRebinds++;
                    firstFrameDeadline = System.currentTimeMillis() + FIRST_FRAME_TIMEOUT_MS;
                    RecorderLog.w(TAG, "No first video frame yet, rebinding capture surface (attempt "
                            + startupRebinds + "/" + MAX_STARTUP_REBINDS + ")");
                    renderer.updateSourceSize(displayController.getScreenWidth(), displayController.getScreenHeight());
                    displayController.bindCaptureSurface(shellContext, renderer.getInputSurface(), targetWidth, targetHeight);
                    continue;
                } else {
                    continue;
                }
            } else if (!firstVideoSampleWritten && System.currentTimeMillis() >= firstEncodedSampleDeadline) {
                RecorderLog.w(TAG, "Frames are arriving, but no encoded video sample has been produced yet");
                firstEncodedSampleDeadline = Long.MAX_VALUE;
            }

            while (true) {
                int outputIndex = videoCodec.dequeueOutputBuffer(videoInfo, CODEC_DEQUEUE_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrackIndex = muxer.addTrack(videoCodec.getOutputFormat());
                    RecorderLog.i(TAG, "Video output format ready");
                } else if (outputIndex >= 0) {
                    if (videoInfo.size > 0) {
                        if (!firstVideoSampleWritten) {
                            firstVideoSampleWritten = true;
                            RecorderLog.i(TAG, "First encoded video sample received");
                        }
                        normalizeTimeBase(videoInfo);
                        ByteBuffer outputBuffer = videoCodec.getOutputBuffer(outputIndex);
                        if (outputBuffer != null) {
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, videoInfo);
                        }
                    }
                    videoCodec.releaseOutputBuffer(outputIndex, false);
                } else {
                    break;
                }
            }
        }

        recordingActive.set(false);
        displayController.releaseCaptureSurface();
    }

    private static List<VideoCandidate> buildVideoCandidates(DisplayCaptureController displayController, int fps) {
        String[] mimeTypes = {MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC};
        int[][] resolutionCaps = {{0, 0}, {1440, 0}, {1080, 0}, {1920, 1}};
        int[] fpsCaps = fps > 30 ? new int[]{fps, 30} : new int[]{fps};
        Set<String> seen = new LinkedHashSet<>();
        List<VideoCandidate> candidates = new ArrayList<>();

        for (int[] cap : resolutionCaps) {
            Pair<Integer, Integer> size = (cap[0] == 0)
                    ? new Pair<>(displayController.getScreenWidth() & ~15, displayController.getScreenHeight() & ~15)
                    : displayController.computeTargetSize(cap[0], cap[1] == 1);
            for (int targetFps : fpsCaps) {
                for (String mimeType : mimeTypes) {
                    String codecLabel = mimeType.contains("hevc") ? "H.265" : "H.264";
                    String key = mimeType + ":" + size.first + "x" + size.second + "@" + targetFps;
                    if (seen.add(key)) {
                        candidates.add(new VideoCandidate(mimeType, size.first, size.second, targetFps, codecLabel));
                    }
                }
            }
        }

        return candidates;
    }

    private static VideoSetup createVideoSetup(VideoCandidate candidate) throws Exception {
        Pair<MediaCodec, android.view.Surface> encoderPair =
                VideoEncoderFactory.createAndStart(candidate.mimeType, candidate.width, candidate.height,
                        candidate.fps, VIDEO_BITRATE_BPS);
        return new VideoSetup(encoderPair.first, encoderPair.second,
                candidate.width, candidate.height, candidate.fps, candidate.codecLabel);
    }

    private static MediaCodec createAudioEncoder(AudioCaptureFactory.AudioCaptureSession audioSession) throws Exception {
        MediaCodec audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                audioSession.sampleRate,
                audioSession.channelCount
        );
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE_BPS);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_SIZE);
        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioCodec.start();
        return audioCodec;
    }

    private static void runAudioLoop(AudioRecord record, MediaCodec codec, AsyncMp4Muxer muxer, AtomicBoolean recordingActive) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] audioBuffer = new byte[AUDIO_BUFFER_SIZE];
        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        AudioTrackState audioTrackState = new AudioTrackState();

        try {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING");
            }

            while (recordingActive.get()) {
                int bytesRead = record.read(audioBuffer, 0, audioBuffer.length);
                if (bytesRead <= 0) {
                    continue;
                }

                int inputIndex = codec.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(audioBuffer, 0, bytesRead);
                        codec.queueInputBuffer(inputIndex, 0, bytesRead, System.nanoTime() / 1000L, 0);
                    }
                }

                drainAudioEncoder(codec, audioInfo, muxer, audioTrackState);
            }

            int inputIndex = codec.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US);
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drainAudioEncoder(codec, audioInfo, muxer, audioTrackState);
        } catch (Exception e) {
            RecorderLog.w(TAG, "Audio loop stopped: " + e.getMessage());
        } finally {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static void drainAudioEncoder(MediaCodec codec, MediaCodec.BufferInfo info, AsyncMp4Muxer muxer, AudioTrackState audioTrackState) {
        while (true) {
            int outputIndex;
            try {
                outputIndex = codec.dequeueOutputBuffer(info, 0);
            } catch (IllegalStateException e) {
                break;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrackState.trackIndex = muxer.addTrack(codec.getOutputFormat());
            } else if (outputIndex >= 0) {
                if (info.size > 0) {
                    normalizeTimeBase(info);
                    muxer.writeSampleData(audioTrackState.trackIndex, codec.getOutputBuffer(outputIndex), info);
                }
                codec.releaseOutputBuffer(outputIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private static void finalizeVideo(MediaCodec videoCodec, AsyncMp4Muxer muxer) {
        MediaCodec.BufferInfo finalInfo = new MediaCodec.BufferInfo();
        int videoTrackIndex = -1;
        try {
            videoCodec.signalEndOfInputStream();
            long drainStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - drainStart < 3000) {
                int outputIndex = videoCodec.dequeueOutputBuffer(finalInfo, CODEC_DEQUEUE_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrackIndex = muxer.addTrack(videoCodec.getOutputFormat());
                } else if (outputIndex >= 0) {
                    if (finalInfo.size > 0) {
                        normalizeTimeBase(finalInfo);
                        muxer.writeSampleData(videoTrackIndex, videoCodec.getOutputBuffer(outputIndex), finalInfo);
                    }
                    boolean endOfStream = (finalInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    videoCodec.releaseOutputBuffer(outputIndex, false);
                    if (endOfStream) {
                        break;
                    }
                } else {
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            releaseVideoCodec(videoCodec);
        }
    }

    private static void releaseVideoCodec(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        try {
            codec.release();
        } catch (Exception ignored) {
        }
    }

    private static void releaseAudioResources(MediaCodec audioCodec, AudioCaptureFactory.AudioCaptureSession audioSession) {
        if (audioCodec != null) {
            try {
                audioCodec.stop();
            } catch (Exception ignored) {
            }
            try {
                audioCodec.release();
            } catch (Exception ignored) {
            }
        }

        if (audioSession != null) {
            audioSession.release();
        }
    }

    private static void joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void normalizeTimeBase(MediaCodec.BufferInfo info) {
        if (globalBaseTimeUs == -1L) {
            synchronized (MainRecorder.class) {
                if (globalBaseTimeUs == -1L) {
                    globalBaseTimeUs = info.presentationTimeUs;
                }
            }
        }
        info.presentationTimeUs -= globalBaseTimeUs;
    }

    private static String buildOutputPath() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File outputDir = new File("/sdcard/Movies/ZeroRecorder");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        return outputDir.getAbsolutePath() + "/Rec_" + timestamp + ".mp4";
    }

    private static void logContext(Context shellContext) {
        try {
            RecorderLog.i(TAG, "Context package=" + shellContext.getPackageName()
                    + " opPackage=" + shellContext.getOpPackageName()
                    + " attribution=" + shellContext.getAttributionSource().getPackageName());
        } catch (Exception e) {
            RecorderLog.w(TAG, "Failed to inspect context attribution: " + e.getMessage());
        }
    }

    private static void cleanupFailedOutput(String outputPath) {
        try {
            File outputFile = new File(outputPath);
            if (outputFile.exists() && !outputFile.delete()) {
                RecorderLog.w(TAG, "Failed to delete incomplete output: " + outputPath);
            }
        } catch (Exception ignored) {
        }
    }

    private static final class AudioTrackState {
        int trackIndex = -1;
    }

    private static final class VideoSetup {
        final MediaCodec codec;
        final android.view.Surface inputSurface;
        final int width;
        final int height;
        final int fps;
        final String codecLabel;
        boolean codecReleased;

        VideoSetup(MediaCodec codec, android.view.Surface inputSurface, int width, int height, int fps, String codecLabel) {
            this.codec = codec;
            this.inputSurface = inputSurface;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.codecLabel = codecLabel;
        }
    }

    private static final class VideoCandidate {
        final String mimeType;
        final int width;
        final int height;
        final int fps;
        final String codecLabel;

        VideoCandidate(String mimeType, int width, int height, int fps, String codecLabel) {
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.codecLabel = codecLabel;
        }
    }

    private static final class RecordingAttemptResult {
        final boolean success;
        final Exception failure;

        private RecordingAttemptResult(boolean success, Exception failure) {
            this.success = success;
            this.failure = failure;
        }

        static RecordingAttemptResult success() {
            return new RecordingAttemptResult(true, null);
        }

        static RecordingAttemptResult failure(Exception failure) {
            return new RecordingAttemptResult(false, failure);
        }
    }
}
