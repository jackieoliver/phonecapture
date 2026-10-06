package com.hapticasensorics.phonecapturekiosk;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

final class Nv21Mp4Recorder {
    interface Listener {
        void onStarted();

        void onCompleted(String path);

        void onError(String message);
    }

    private static final String TAG = "PhonecaptureRecorder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long CODEC_TIMEOUT_US = 10_000L;

    private final File outputFile;
    private final int width;
    private final int height;
    private final int fps;
    private final int bitrate;
    private final Listener listener;

    private final Object frameLock = new Object();

    private HandlerThread workerThread;
    private Handler workerHandler;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private MediaCodec.BufferInfo bufferInfo;
    private boolean started;
    private boolean stopRequested;
    private boolean muxerStarted;
    private int trackIndex = -1;
    private int colorFormat;
    private long frameIntervalUs;
    private long nextPtsUs;
    private byte[] latestFrame;
    private boolean latestFrameAvailable;
    private byte[] conversionBuffer;

    Nv21Mp4Recorder(File outputFile, int width, int height, int fps, int bitrate, Listener listener) {
        this.outputFile = outputFile;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.listener = listener;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        workerThread = new HandlerThread("phonecapture-recorder");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        workerHandler.post(this::startInternal);
    }

    void offerFrame(byte[] nv21Frame, int frameWidth, int frameHeight) {
        if (!started || stopRequested || frameWidth != width || frameHeight != height) {
            return;
        }
        synchronized (frameLock) {
            int requiredSize = width * height * 3 / 2;
            if (latestFrame == null || latestFrame.length != requiredSize) {
                latestFrame = new byte[requiredSize];
            }
            System.arraycopy(nv21Frame, 0, latestFrame, 0, requiredSize);
            latestFrameAvailable = true;
        }
    }

    void stop() {
        if (!started || stopRequested) {
            return;
        }
        stopRequested = true;
    }

    private void startInternal() {
        try {
            colorFormat = selectColorFormat();
            Log.i(TAG, "Using encoder color format: " + colorFormatName(colorFormat) + " (" + colorFormat + ")");
            frameIntervalUs = 1_000_000L / Math.max(fps, 1);
            nextPtsUs = 0L;
            bufferInfo = new MediaCodec.BufferInfo();

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            codec = MediaCodec.createEncoderByType(MIME_TYPE);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            if (outputFile.getParentFile() != null) {
                outputFile.getParentFile().mkdirs();
            }
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            if (listener != null) {
                listener.onStarted();
            }

            workerHandler.post(encodeLoop);
        } catch (Exception e) {
            fail("Recorder start failed: " + e.getMessage(), e);
        }
    }

    private final Runnable encodeLoop = new Runnable() {
        @Override
        public void run() {
            if (codec == null) {
                return;
            }

            try {
                boolean encodedFrame = queueLatestFrameIfAvailable();
                drainEncoder(false);

                if (stopRequested) {
                    signalEndOfInput();
                    drainEncoder(true);
                    finish();
                    return;
                }

                long delayMs = encodedFrame ? Math.max(1L, frameIntervalUs / 1000L) : 20L;
                workerHandler.postDelayed(this, delayMs);
            } catch (Exception e) {
                fail("Recorder loop failed: " + e.getMessage(), e);
            }
        }
    };

    private boolean queueLatestFrameIfAvailable() {
        byte[] frame = null;
        synchronized (frameLock) {
            if (latestFrameAvailable && latestFrame != null) {
                frame = latestFrame.clone();
                latestFrameAvailable = false;
            }
        }
        if (frame == null) {
            return false;
        }

        int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (inputIndex < 0) {
            return false;
        }

        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
        if (inputBuffer == null) {
            return false;
        }
        inputBuffer.clear();

        byte[] encodedInput = convertNv21(frame);
        inputBuffer.put(encodedInput, 0, encodedInput.length);
        codec.queueInputBuffer(inputIndex, 0, encodedInput.length, nextPtsUs, 0);
        nextPtsUs += frameIntervalUs;
        return true;
    }

    private void signalEndOfInput() {
        int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (inputIndex >= 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, nextPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
    }

    private void drainEncoder(boolean endOfStream) {
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new IllegalStateException("Output format changed twice");
                }
                MediaFormat newFormat = codec.getOutputFormat();
                trackIndex = muxer.addTrack(newFormat);
                muxer.start();
                muxerStarted = true;
            } else if (outputIndex >= 0) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                if (outputBuffer == null) {
                    codec.releaseOutputBuffer(outputIndex, false);
                    continue;
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size > 0 && muxerStarted) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                }

                codec.releaseOutputBuffer(outputIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && !endOfStream) {
                break;
            }
        }
    }

    private void finish() {
        releaseResources();
        if (listener != null) {
            listener.onCompleted(outputFile.getAbsolutePath());
        }
    }

    private void fail(String message, Exception error) {
        Log.e(TAG, message, error);
        releaseResources();
        if (outputFile.exists()) {
            outputFile.delete();
        }
        if (listener != null) {
            listener.onError(message);
        }
    }

    private void releaseResources() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
        }
        muxerStarted = false;
        trackIndex = -1;
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
    }

    private int selectColorFormat() {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        Integer flexibleFallback = null;
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] supportedTypes = codecInfo.getSupportedTypes();
            for (String type : supportedTypes) {
                if (!MIME_TYPE.equalsIgnoreCase(type)) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(type);
                for (int candidate : capabilities.colorFormats) {
                    if (candidate == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                        return candidate;
                    }
                    if (candidate == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                        return candidate;
                    }
                    if (candidate == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                        flexibleFallback = candidate;
                    }
                }
            }
        }
        if (flexibleFallback != null) {
            Log.w(TAG, "Falling back to COLOR_FormatYUV420Flexible; chroma layout may be vendor-specific.");
            return flexibleFallback;
        }
        throw new IllegalStateException("No supported AVC color format found");
    }

    private String colorFormatName(int format) {
        if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            return "COLOR_FormatYUV420SemiPlanar";
        }
        if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            return "COLOR_FormatYUV420Planar";
        }
        if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            return "COLOR_FormatYUV420Flexible";
        }
        return "UNKNOWN";
    }

    private byte[] convertNv21(byte[] source) {
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            return convertNv21ToI420(source);
        }
        return convertNv21ToNv12(source);
    }

    private byte[] convertNv21ToNv12(byte[] source) {
        int frameSize = width * height;
        int totalSize = frameSize * 3 / 2;
        if (conversionBuffer == null || conversionBuffer.length != totalSize) {
            conversionBuffer = new byte[totalSize];
        }
        System.arraycopy(source, 0, conversionBuffer, 0, frameSize);
        for (int i = 0; i < frameSize / 2; i += 2) {
            conversionBuffer[frameSize + i] = source[frameSize + i + 1];
            conversionBuffer[frameSize + i + 1] = source[frameSize + i];
        }
        return conversionBuffer;
    }

    private byte[] convertNv21ToI420(byte[] source) {
        int frameSize = width * height;
        int qFrameSize = frameSize / 4;
        int totalSize = frameSize * 3 / 2;
        if (conversionBuffer == null || conversionBuffer.length != totalSize) {
            conversionBuffer = new byte[totalSize];
        }
        System.arraycopy(source, 0, conversionBuffer, 0, frameSize);
        int uOffset = frameSize;
        int vOffset = frameSize + qFrameSize;
        int uvStart = frameSize;
        for (int i = 0; i < qFrameSize; i++) {
            conversionBuffer[uOffset + i] = source[uvStart + (i * 2) + 1];
            conversionBuffer[vOffset + i] = source[uvStart + (i * 2)];
        }
        return conversionBuffer;
    }
}
