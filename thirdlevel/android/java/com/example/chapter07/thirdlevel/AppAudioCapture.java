package com.example.chapter07.thirdlevel;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * 使用 Android 10+ 的 AudioPlaybackCapture 采集其他应用主动允许采集的播放音频。
 * 本类只处理应用播放，不降级为麦克风环境声采集。
 */
public final class AppAudioCapture implements AutoCloseable {

    public interface Listener {
        void onChunk(byte[] wavBytes, int durationMs);

        void onSilence();

        void onUnavailable(String message);

        void onError(String message);
    }

    private static final String TAG = "ThirdLevelAudio";
    private static final int SAMPLE_RATE = 16_000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE;
    private static final int CHUNK_DURATION_MS = 10_000;
    private static final int CHUNK_BYTES = BYTES_PER_SECOND * CHUNK_DURATION_MS / 1000;
    private static final int OVERLAP_BYTES = BYTES_PER_SECOND / 2;
    private static final int SILENCE_AMPLITUDE_THRESHOLD = 80;

    private final MediaProjection mediaProjection;
    private final Listener listener;
    private volatile boolean running;
    private AudioRecord audioRecord;
    private Thread captureThread;

    public AppAudioCapture(MediaProjection mediaProjection, Listener listener) {
        this.mediaProjection = mediaProjection;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            listener.onUnavailable("应用音频采集需要 Android 10 或更高版本");
            return;
        }

        try {
            AudioPlaybackCaptureConfiguration captureConfig =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_MASK)
                    .build();
            int minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_MASK,
                    ENCODING
            );
            if (minBufferSize <= 0) {
                throw new IllegalStateException("设备不支持 16 kHz 单声道音频");
            }

            audioRecord = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(Math.max(minBufferSize * 2, BYTES_PER_SECOND))
                    .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord 初始化失败");
            }
            audioRecord.startRecording();
            running = true;
            captureThread = new Thread(this::captureLoop, "ThirdLevelAudioCapture");
            captureThread.start();
            Log.i(TAG, "application playback capture started");
        } catch (SecurityException error) {
            stopInternal();
            listener.onUnavailable("未获得音频采集权限");
        } catch (Throwable error) {
            stopInternal();
            listener.onUnavailable("当前应用不允许播放音频采集或设备不支持：" + safeMessage(error));
        }
    }

    public void stop() {
        running = false;
        AudioRecord record = audioRecord;
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
        }
        Thread thread = captureThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        stopInternal();
    }

    private void captureLoop() {
        short[] buffer = new short[1_600];
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(CHUNK_BYTES + OVERLAP_BYTES);
        long energy = 0;
        int energySamples = 0;

        try {
            while (running) {
                int read = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read < 0) {
                    if (running) {
                        listener.onError("读取应用音频失败，错误码：" + read);
                    }
                    break;
                }
                if (read == 0) {
                    continue;
                }

                byte[] pcm = new byte[read * BYTES_PER_SAMPLE];
                for (int i = 0; i < read; i++) {
                    short sample = buffer[i];
                    pcm[i * 2] = (byte) (sample & 0xff);
                    pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                    energy += Math.abs((int) sample);
                }
                energySamples += read;
                chunk.write(pcm, 0, pcm.length);

                if (chunk.size() < CHUNK_BYTES) {
                    continue;
                }

                long averageAmplitude = energySamples == 0 ? 0 : energy / energySamples;
                if (averageAmplitude < SILENCE_AMPLITUDE_THRESHOLD) {
                    listener.onSilence();
                } else {
                    listener.onChunk(toWav(chunk.toByteArray()), CHUNK_DURATION_MS);
                }

                byte[] all = chunk.toByteArray();
                int overlapLength = Math.min(OVERLAP_BYTES, all.length);
                byte[] overlap = Arrays.copyOfRange(
                        all,
                        all.length - overlapLength,
                        all.length
                );
                chunk.reset();
                chunk.write(overlap, 0, overlap.length);
                energy = 0;
                energySamples = 0;
            }
        } catch (Throwable error) {
            if (running) {
                listener.onError("应用音频采集异常：" + safeMessage(error));
            }
        }
    }

    private static byte[] toWav(byte[] pcm) {
        int totalLength = 44 + pcm.length;
        byte[] wav = new byte[totalLength];
        writeAscii(wav, 0, "RIFF");
        writeIntLE(wav, 4, totalLength - 8);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeIntLE(wav, 16, 16);
        writeShortLE(wav, 20, 1);
        writeShortLE(wav, 22, 1);
        writeIntLE(wav, 24, SAMPLE_RATE);
        writeIntLE(wav, 28, BYTES_PER_SECOND);
        writeShortLE(wav, 32, BYTES_PER_SAMPLE);
        writeShortLE(wav, 34, BYTES_PER_SAMPLE * 8);
        writeAscii(wav, 36, "data");
        writeIntLE(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private synchronized void stopInternal() {
        running = false;
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
        captureThread = null;
    }

    private static void writeAscii(byte[] target, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            target[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void writeShortLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    private static void writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
        target[offset + 2] = (byte) ((value >> 16) & 0xff);
        target[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    @Override
    public void close() {
        stop();
    }
}
