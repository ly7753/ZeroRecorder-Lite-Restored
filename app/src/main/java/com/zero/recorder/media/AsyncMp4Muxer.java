package com.zero.recorder.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Process;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncMp4Muxer {
    private static final int QUEUE_CAPACITY = 500;
    private static final int MIN_BUFFER_SIZE = 512 * 1024;
    private static final int BUFFER_PADDING = 10 * 1024;

    private final MediaMuxer mediaMuxer;
    private final LinkedBlockingQueue<EncodedFrame> frameQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final LinkedBlockingQueue<ByteBuffer> bufferPool = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muxerStarted = new AtomicBoolean(false);
    private final AtomicInteger trackCount = new AtomicInteger(0);
    private final int expectedTrackCount;

    private Thread workerThread;

    public AsyncMp4Muxer(String outputPath, int expectedTrackCount) throws Exception {
        mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        this.expectedTrackCount = expectedTrackCount;
    }

    public synchronized int addTrack(MediaFormat format) {
        int trackIndex = mediaMuxer.addTrack(format);
        if (trackCount.incrementAndGet() == expectedTrackCount) {
            mediaMuxer.start();
            muxerStarted.set(true);
        }
        return trackIndex;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        workerThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (running.get() || !frameQueue.isEmpty()) {
                try {
                    EncodedFrame frame = frameQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        continue;
                    }
                    if (frame.info.size > 0 && muxerStarted.get()) {
                        mediaMuxer.writeSampleData(frame.trackIndex, frame.buffer, frame.info);
                    }
                    frame.buffer.clear();
                    bufferPool.offer(frame.buffer);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
            }
        }, "AsyncMuxerThread");
        workerThread.start();
    }

    public void writeSampleData(int trackIndex, ByteBuffer codecBuffer, MediaCodec.BufferInfo info) {
        if (!muxerStarted.get() || codecBuffer == null || info == null || info.size <= 0 || trackIndex < 0) {
            return;
        }

        ByteBuffer copyBuffer = bufferPool.poll();
        int requiredCapacity = Math.max(info.size + BUFFER_PADDING, MIN_BUFFER_SIZE);
        if (copyBuffer == null || copyBuffer.capacity() < requiredCapacity) {
            copyBuffer = ByteBuffer.allocateDirect(requiredCapacity);
        }

        copyBuffer.clear();
        codecBuffer.position(info.offset);
        codecBuffer.limit(info.offset + info.size);
        copyBuffer.put(codecBuffer);
        copyBuffer.flip();

        MediaCodec.BufferInfo copiedInfo = new MediaCodec.BufferInfo();
        copiedInfo.set(0, info.size, info.presentationTimeUs, info.flags);
        frameQueue.offer(new EncodedFrame(trackIndex, copyBuffer, copiedInfo));
    }

    public void stopAndRelease() {
        running.set(false);
        if (workerThread != null) {
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (muxerStarted.get()) {
                mediaMuxer.stop();
            }
        } catch (Exception ignored) {
        } finally {
            try {
                mediaMuxer.release();
            } catch (Exception ignored) {
            }
        }

        frameQueue.clear();
        bufferPool.clear();
    }

    private static final class EncodedFrame {
        final int trackIndex;
        final ByteBuffer buffer;
        final MediaCodec.BufferInfo info;

        EncodedFrame(int trackIndex, ByteBuffer buffer, MediaCodec.BufferInfo info) {
            this.trackIndex = trackIndex;
            this.buffer = buffer;
            this.info = info;
        }
    }
}
