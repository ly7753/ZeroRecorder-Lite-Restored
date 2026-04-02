package com.zero.recorder.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Pair;
import android.view.Surface;

public final class VideoEncoderFactory {
    private VideoEncoderFactory() {
    }

    public static Pair<MediaCodec, Surface> createAndStart(String mimeType, int width, int height, int fps, int bitrate) throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType(mimeType);
        try {
            try {
                codec.configure(
                        buildFormat(mimeType, width, height, fps, bitrate,
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ, 1, true),
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE
                );
            } catch (Exception ignored) {
                codec.configure(
                        buildFormat(mimeType, width, height, fps, bitrate,
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, 10, false),
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE
                );
            }

            Surface inputSurface = codec.createInputSurface();
            codec.start();
            return new Pair<>(codec, inputSurface);
        } catch (Exception e) {
            codec.release();
            throw e;
        }
    }

    private static MediaFormat buildFormat(String mimeType, int width, int height, int fps, int bitrate,
            int bitrateMode, int iFrameInterval, boolean preferConstantQuality) {
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);

        if (preferConstantQuality) {
            format.setInteger("quality", 85);
        }

        try {
            format.setInteger("vendor.mtk-venc-low-latency", 1);
        } catch (Exception ignored) {
        }
        return format;
    }
}
