package app.morphe.extension.instagram.patches.dm.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Converts an audio file to what Instagram's MediaRecorder voice recorder writes, mono AAC in MPEG-4 with
 * the chat recorder's own encoder, bit rate and sample rate, and computes the waveform the recorder
 * samples while it records. Every method that reads or writes a file runs on the caller's background thread.
 */
final class VoiceAudio {
    /** The recorder adds one waveform level every 100 ms. */
    static final int WAVEFORM_PER_SECOND = 10;
    /** Instagram drops recordings shorter than this, and stops recording at the maximum. */
    static final long MIN_MS = 750;
    static final long MAX_MS = 300_000;
    private static final long TIMEOUT_US = 10_000;
    // Empty 10 ms tries in a row before a stuck codec is given up.
    private static final int MAX_IDLE = 500;

    private VoiceAudio() {
    }

    /** The recorder's output: its sample rate, the AAC profile of its encoder, and its bit rate. */
    static final class Format {
        final int sampleRate;
        final int aacProfile;
        final int bitRate;

        Format(int sampleRate, int aacProfile, int bitRate) {
            this.sampleRate = sampleRate;
            this.aacProfile = aacProfile;
            this.bitRate = bitRate;
        }

        /**
         * The format of a MediaRecorder recorder with {@code settings} {encoder, bit rate, sample rate,
         * Pixel 4 sample rate}, on a Pixel 4 when {@code pixel4}; null when it is not AAC this can write.
         */
        static Format of(int[] settings, boolean pixel4) {
            if (settings == null || settings.length != 4) return null;
            int profile = aacProfile(settings[0]);
            int rate = pixel4 ? settings[3] : settings[2];
            return profile < 0 || settings[1] <= 0 || rate <= 0 ? null : new Format(rate, profile, settings[1]);
        }
    }

    /** The AAC profile a MediaRecorder audio encoder writes, or -1 when it does not write AAC. */
    static int aacProfile(int audioEncoder) {
        switch (audioEncoder) {
            case MediaRecorder.AudioEncoder.AAC:
                return MediaCodecInfo.CodecProfileLevel.AACObjectLC;
            case MediaRecorder.AudioEncoder.HE_AAC:
                return MediaCodecInfo.CodecProfileLevel.AACObjectHE;
            case MediaRecorder.AudioEncoder.AAC_ELD:
                return MediaCodecInfo.CodecProfileLevel.AACObjectELD;
            default:
                return -1;
        }
    }

    /** A converted file's length and waveform. */
    static final class Result {
        final int durationMs;
        final List<Float> waveform;

        Result(int durationMs, List<Float> waveform) {
            this.durationMs = durationMs;
            this.waveform = waveform;
        }
    }

    /** Converts {@code startMs}..{@code endMs} of {@code source}'s first audio track into {@code target}. */
    static Result convert(Context context, Uri source, long startMs, long endMs, Format format, String target)
            throws IOException {
        if (startMs < 0 || endMs - startMs < MIN_MS || endMs - startMs > MAX_MS) {
            throw new IOException("Selection " + startMs + ".." + endMs + " ms is out of range");
        }
        long expected = (endMs - startMs) * format.sampleRate / 1000;
        Pcm pcm = decode(context, source, startMs * 1000, endMs * 1000, format.sampleRate,
                (int) Math.min(Integer.MAX_VALUE - 8, expected + 4096));
        int durationMs = durationMs(pcm.size, format.sampleRate);
        if (durationMs < MIN_MS) throw new IOException("Only " + durationMs + " ms of audio decoded");
        encode(pcm, format, target);
        return new Result(durationMs, waveform(pcm.data, pcm.size, format.sampleRate));
    }

    /** Mono 16-bit samples. */
    static final class Pcm {
        short[] data;
        int size;

        Pcm(int capacity) {
            data = new short[Math.max(1024, capacity)];
        }

        void add(short sample) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = sample;
        }
    }

    /**
     * Linear interpolation from one sample rate to another, fed one decoded chunk at a time. Before a
     * lower rate a low-pass filter takes out what the lower rate cannot hold, so it does not fold back.
     */
    static final class Resampler {
        static final int TAPS = 63;
        private final double step;
        // Null when the rate does not go down.
        private final float[] taps;
        // The last TAPS - 1 input samples, which the filter needs at the start of the next chunk.
        private final short[] history = new short[TAPS - 1];
        // Where the next output sample lies in the next chunk; -1..0 is between the last chunk's end and it.
        private double position;
        private short previous;

        Resampler(int fromRate, int toRate) {
            step = fromRate / (double) toRate;
            taps = fromRate > toRate ? lowPassTaps(fromRate, toRate) : null;
        }

        void add(short[] in, int count, Pcm out) {
            short[] samples = taps == null ? in : lowPass(in, count);
            while (position < count - 1) {
                int i = (int) Math.floor(position);
                double fraction = position - i;
                short a = i < 0 ? previous : samples[i];
                short b = samples[i + 1];
                out.add((short) Math.round(a + (b - a) * fraction));
                position += step;
            }
            if (count > 0) {
                previous = samples[count - 1];
                position -= count;
            }
        }

        private short[] lowPass(short[] in, int count) {
            short[] filtered = new short[count];
            for (int i = 0; i < count; i++) {
                float sum = 0;
                for (int k = 0; k < TAPS; k++) {
                    int at = i - k;
                    sum += taps[k] * (at >= 0 ? in[at] : history[history.length + at]);
                }
                filtered[i] = pcm16(sum);
            }
            int keep = history.length;
            if (count >= keep) {
                System.arraycopy(in, count - keep, history, 0, keep);
            } else {
                System.arraycopy(history, count, history, 0, keep - count);
                System.arraycopy(in, 0, history, keep - count, count);
            }
            return filtered;
        }
    }

    /** A Hamming-windowed sinc low-pass at 90% of {@code toRate}'s Nyquist frequency, with unit gain. */
    static float[] lowPassTaps(int fromRate, int toRate) {
        int taps = Resampler.TAPS;
        double cutoff = 0.45 * toRate / fromRate;
        int middle = (taps - 1) / 2;
        double[] values = new double[taps];
        double sum = 0;
        for (int k = 0; k < taps; k++) {
            int x = k - middle;
            double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            values[k] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * k / (taps - 1)));
            sum += values[k];
        }
        float[] out = new float[taps];
        for (int k = 0; k < taps; k++) out[k] = (float) (values[k] / sum);
        return out;
    }

    private static Pcm decode(Context context, Uri source, long startUs, long endUs, int targetRate, int capacity)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(context, source, null);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount() && format == null; i++) {
                MediaFormat track = extractor.getTrackFormat(i);
                String mime = track.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = track;
                }
            }
            if (format == null) throw new IOException("No audio track");
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int rate = integer(format, MediaFormat.KEY_SAMPLE_RATE, targetRate);
            int channels = integer(format, MediaFormat.KEY_CHANNEL_COUNT, 1);
            boolean floats = false;
            Pcm out = new Pcm(capacity);
            Resampler resampler = null;
            int resamplerRate = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            for (int idle = 0; idle < MAX_IDLE; ) {
                if (!inputDone) {
                    int in = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        idle = 0;
                        ByteBuffer buffer = decoder.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        long time = extractor.getSampleTime();
                        if (size < 0 || time < 0 || time >= endUs) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(in, 0, size, time, 0);
                            extractor.advance();
                        }
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = decoder.getOutputFormat();
                    rate = integer(output, MediaFormat.KEY_SAMPLE_RATE, rate);
                    channels = integer(output, MediaFormat.KEY_CHANNEL_COUNT, channels);
                    int encoding = integer(output, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        throw new IOException("Unsupported PCM encoding " + encoding);
                    }
                    floats = encoding == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (index >= 0) {
                    idle = 0;
                    ByteBuffer buffer = decoder.getOutputBuffer(index);
                    if (buffer != null && info.size > 0 && channels > 0) {
                        if (resampler == null || resamplerRate != rate) {
                            resampler = new Resampler(rate, targetRate);
                            resamplerRate = rate;
                        }
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        ByteBuffer data = buffer.slice().order(ByteOrder.nativeOrder());
                        int frames = info.size / ((floats ? 4 : 2) * channels);
                        int[] kept = keptFrames(info.presentationTimeUs, frames, rate, startUs, endUs);
                        short[] mono = mixDown(data, floats, channels, kept[0], kept[1]);
                        resampler.add(mono, mono.length, out);
                    }
                    decoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return out;
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    idle++;
                }
            }
            throw new IOException("The decoder stopped answering");
        } finally {
            if (decoder != null) decoder.release();
            extractor.release();
        }
    }

    /** The channel average of frames {@code from} to {@code to} (excluded) of interleaved PCM {@code data}. */
    private static short[] mixDown(ByteBuffer data, boolean floats, int channels, int from, int to) {
        short[] mono = new short[to - from];
        FloatBuffer floatData = floats ? data.asFloatBuffer() : null;
        ShortBuffer shortData = floats ? null : data.asShortBuffer();
        for (int frame = from; frame < to; frame++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) {
                int at = frame * channels + c;
                sum += floats ? floatData.get(at) * 32767f : shortData.get(at);
            }
            mono[frame - from] = pcm16(sum / channels);
        }
        return mono;
    }

    static short pcm16(float value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }

    /**
     * The frames, as [from, to), of a decoded buffer of {@code frames} frames from {@code ptsUs} at
     * {@code rate} whose times lie in {@code startUs} (included) to {@code endUs} (excluded).
     */
    static int[] keptFrames(long ptsUs, int frames, int rate, long startUs, long endUs) {
        long from = Math.max(0, Math.min(frames, ceilDiv((startUs - ptsUs) * rate, 1_000_000)));
        long to = Math.max(from, Math.min(frames, ceilDiv((endUs - ptsUs) * rate, 1_000_000)));
        return new int[]{(int) from, (int) to};
    }

    private static long ceilDiv(long value, long divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static int integer(MediaFormat format, String key, int fallback) {
        return format.containsKey(key) ? format.getInteger(key) : fallback;
    }

    private static void encode(Pcm pcm, Format output, String target) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, output.sampleRate, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, output.aacProfile);
        format.setInteger(MediaFormat.KEY_BIT_RATE, output.bitRate);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer muxer = null;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(target, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            int queued = 0;
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int idle = 0; idle < MAX_IDLE; ) {
                if (!inputDone) {
                    int in = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        idle = 0;
                        ByteBuffer buffer = encoder.getInputBuffer(in);
                        int count = buffer == null ? 0 : Math.min(pcm.size - queued, buffer.remaining() / 2);
                        long time = queued * 1_000_000L / output.sampleRate;
                        if (count <= 0) {
                            encoder.queueInputBuffer(in, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm.data, queued, count);
                            encoder.queueInputBuffer(in, 0, count * 2, time, 0);
                            queued += count;
                        }
                    }
                }
                int index = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (track >= 0) throw new IOException("The encoder changed its format twice");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                } else if (index >= 0) {
                    idle = 0;
                    ByteBuffer buffer = encoder.getOutputBuffer(index);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (buffer != null && info.size > 0 && !config && track >= 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    encoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (track < 0) throw new IOException("The encoder gave no format");
                        muxer.stop();
                        return;
                    }
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    idle++;
                }
            }
            throw new IOException("The encoder stopped answering");
        } finally {
            encoder.release();
            if (muxer != null) release(muxer);
        }
    }

    /** Releases even a muxer whose stop fails: the failed stop marks it stopped, so a second release frees it. */
    private static void release(MediaMuxer muxer) {
        try {
            muxer.release();
        } catch (RuntimeException e) {
            try {
                muxer.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    static int durationMs(int samples, int sampleRate) {
        return (int) Math.round(samples * 1000.0 / sampleRate);
    }

    /** One level per 100 ms of the first {@code size} samples at {@code sampleRate}, from each window's peak. */
    static List<Float> waveform(short[] samples, int size, int sampleRate) {
        int window = Math.max(1, sampleRate / WAVEFORM_PER_SECOND);
        List<Float> out = new ArrayList<>(size / window + 1);
        for (int from = 0; from < size; from += window) {
            int peak = 0;
            for (int i = from, to = Math.min(size, from + window); i < to; i++) {
                peak = Math.max(peak, Math.abs(samples[i]));
            }
            out.add(level(peak));
        }
        return out;
    }

    /** The recorder's level for a peak amplitude: ln(peak) / 4 - 1.5807, clamped to 0..1. */
    static float level(int peak) {
        float value = (float) (Math.log(peak) * 0.25 - 1.5807000398635864);
        return Math.min(1f, Math.max(0f, value));
    }

    /** {@code ms} as m:ss.t. */
    static String time(long ms) {
        long tenths = Math.max(0, ms) / 100;
        return String.format(Locale.ROOT, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10);
    }

    /**
     * The selection after one handle moved, at least {@link #MIN_MS} and at most {@link #MAX_MS} long
     * within 0..{@code durationMs}; the other handle gives way. {@code durationMs} is at least the minimum.
     */
    static long[] clampRange(long startMs, long endMs, long durationMs, boolean startMoved) {
        long start = startMs;
        long end = endMs;
        if (startMoved) {
            start = Math.max(0, Math.min(start, durationMs - MIN_MS));
            end = Math.min(Math.max(end, start + MIN_MS), Math.min(durationMs, start + MAX_MS));
        } else {
            end = Math.min(durationMs, Math.max(end, MIN_MS));
            start = Math.max(Math.min(start, end - MIN_MS), Math.max(0, end - MAX_MS));
        }
        return new long[]{start, end};
    }
}
