package app.morphe.extension.instagram.patches.download.gallery.editor;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyframe cut points and lossless remuxing for the video editor. Times are microseconds. Every
 * method that reads or writes a file runs on the caller's background thread.
 */
final class VideoTrim {
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,8}$");
    private static final String AAC = "audio/mp4a-latm";
    // Floor of the sample buffer; a track's own max input size raises it.
    private static final int MIN_BUFFER = 4 << 20;

    private VideoTrim() {
    }

    /** What the editor needs from a video: its cut points and the type of its first audio track, null without one. */
    static final class Probe {
        final long[] points;
        final String audioMime;

        Probe(long[] points, String audioMime) {
            this.points = points;
            this.audioMime = audioMime;
        }
    }

    /** Reads the first video track's keyframe times and duration; throws when there is no video track. */
    static Probe probe(String path) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            int video = -1;
            long duration = 0;
            String audioMime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (video < 0 && mime.startsWith("video/")) {
                    video = i;
                    if (format.containsKey(MediaFormat.KEY_DURATION)) duration = format.getLong(MediaFormat.KEY_DURATION);
                } else if (audioMime == null && mime.startsWith("audio/")) {
                    audioMime = mime;
                }
            }
            if (video < 0) throw new IOException("No video track");
            extractor.selectTrack(video);
            long[] syncs = new long[64];
            int count = 0;
            long last = 0;
            for (long time; (time = extractor.getSampleTime()) >= 0; extractor.advance()) {
                last = Math.max(last, time);
                if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) continue;
                if (count == syncs.length) syncs = Arrays.copyOf(syncs, count * 2);
                syncs[count++] = time;
            }
            // The end is exclusive, so a duration read from the last sample still keeps that sample.
            long end = duration > 0 ? duration : last + 1;
            if (duration <= 0 && last == 0) throw new IOException("No duration");
            return new Probe(cutPoints(syncs, count, end), audioMime);
        } finally {
            extractor.release();
        }
    }

    /**
     * The first {@code count} keyframe times, sorted, without repeats and below {@code durationUs},
     * then {@code durationUs}; 0 stands in for the first keyframe when none is below the duration.
     */
    static long[] cutPoints(long[] syncs, int count, long durationUs) {
        long[] sorted = Arrays.copyOf(syncs, count);
        Arrays.sort(sorted);
        long[] out = new long[count + 1];
        int n = 0;
        for (long time : sorted) {
            if (time < 0 || time >= durationUs || (n > 0 && out[n - 1] == time)) continue;
            out[n++] = time;
        }
        if (n == 0) out[n++] = 0;
        out[n++] = durationUs;
        return Arrays.copyOf(out, n);
    }

    /** The point of sorted {@code points} nearest {@code time} within {@code min}..{@code max}, the earlier on a tie; -1 when none. */
    static long snap(long[] points, long time, long min, long max) {
        long best = -1;
        for (long point : points) {
            if (point < min || point > max) continue;
            if (best < 0 || Math.abs(point - time) < Math.abs(best - time)) best = point;
        }
        return best;
    }

    /**
     * The point of sorted {@code points} before ({@code direction} below 0) or after {@code current},
     * within {@code min}..{@code max}; {@code current} when there is none.
     */
    static long step(long[] points, long current, int direction, long min, long max) {
        long out = current;
        for (long point : points) {
            if (point < min || point > max) continue;
            if (direction < 0 && point < current) out = point;
            if (direction > 0 && point > current) return point;
        }
        return out;
    }

    /** {@code us} as m:ss.t, or h:mm:ss.t from an hour, rounded to a tenth of a second. */
    static String time(long us) {
        long tenths = Math.round(Math.max(0, us) / 100_000.0);
        long seconds = tenths / 10;
        long minutes = seconds / 60;
        if (minutes >= 60) {
            return String.format(Locale.ROOT, "%d:%02d:%02d.%d", minutes / 60, minutes % 60, seconds % 60,
                    tenths % 10);
        }
        return String.format(Locale.ROOT, "%d:%02d.%d", minutes, seconds % 60, tenths % 10);
    }

    /** The extension of {@code name}, lowercase with its dot, or {@code fallback} when it has none. */
    static String extension(String name, String fallback) {
        Matcher m = EXTENSION.matcher(name == null ? "" : name);
        return m.find() ? m.group().toLowerCase(Locale.ROOT) : fallback;
    }

    /** The name of an item made from {@code fileName}: its name with {@code tag} once, then {@code extension}. */
    static String derivedName(String fileName, String tag, String extension) {
        String base = fileName == null || fileName.isEmpty() ? "video" : EXTENSION.matcher(fileName).replaceFirst("");
        if (!base.endsWith(tag)) base += tag;
        return base + extension;
    }

    /** Whether the muxer writes the container that {@code extension} names. */
    private static boolean writable(String extension) {
        return Arrays.asList(".mp4", ".m4v", ".webm", ".3gp", ".3gpp").contains(extension);
    }

    /** The extension of a trim: the source's when the muxer writes that container, else .mp4 for MPEG-4. */
    static String videoExtension(String sourceExtension) {
        return writable(sourceExtension) ? sourceExtension : ".mp4";
    }

    /** The extension of the audio alone: .m4a for AAC, else the source's container; null when the muxer cannot write it. */
    static String audioExtension(String audioMime, String sourceExtension) {
        if (AAC.equals(audioMime)) return ".m4a";
        return writable(sourceExtension) && !".mp4".equals(sourceExtension) && !".m4v".equals(sourceExtension)
                ? sourceExtension : null;
    }

    /** The muxer format of a file with an extension from {@link #videoExtension} or {@link #audioExtension}. */
    static int muxerFormat(String extension) {
        if (".webm".equals(extension)) return MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
        if (".3gp".equals(extension) || ".3gpp".equals(extension)) return MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP;
        return MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    }

    /** One selected source track and its muxer track. */
    private static final class Input {
        final MediaExtractor extractor = new MediaExtractor();
        MediaFormat format;
        int output;
        boolean done;
    }

    /** The first track of {@code source} whose type starts with {@code prefix}, selected; null when there is none. */
    private static Input open(String source, String prefix) throws IOException {
        Input in = new Input();
        try {
            in.extractor.setDataSource(source);
            for (int i = 0; i < in.extractor.getTrackCount(); i++) {
                MediaFormat format = in.extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith(prefix)) {
                    in.extractor.selectTrack(i);
                    in.format = format;
                    return in;
                }
            }
        } catch (IOException | RuntimeException e) {
            in.extractor.release();
            throw e;
        }
        in.extractor.release();
        return null;
    }

    /**
     * Copies samples of {@code source}'s first audio track, and with {@code video} of its first video
     * track, into a new {@code format} file at {@code target} without re-encoding. The copy starts at
     * the video keyframe at or before {@code startUs} (at {@code startUs} without video) and ends before
     * {@code endUs}, where video stops at the first keyframe at or after it; times start at 0 and the
     * rotation is kept. Throws when nothing was copied.
     */
    static void remux(String source, String target, int format, boolean video, long startUs, long endUs)
            throws IOException {
        Input videoIn = null;
        Input audioIn = null;
        MediaMuxer muxer = null;
        try {
            if (video) {
                videoIn = open(source, "video/");
                if (videoIn == null) throw new IOException("No video track");
            }
            audioIn = open(source, "audio/");
            if (videoIn == null && audioIn == null) throw new IOException("No audio track");
            muxer = new MediaMuxer(target, format);
            int size = MIN_BUFFER;
            long cut = startUs;
            if (videoIn != null) {
                MediaFormat videoFormat = videoIn.format;
                int rotation = videoFormat.containsKey(MediaFormat.KEY_ROTATION)
                        ? videoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
                if (rotation != 0 && format != MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) {
                    muxer.setOrientationHint(rotation);
                }
                videoIn.output = muxer.addTrack(videoFormat);
                videoIn.extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long keyframe = videoIn.extractor.getSampleTime();
                if (keyframe >= 0) cut = Math.min(cut, keyframe);
            }
            if (audioIn != null) {
                audioIn.output = muxer.addTrack(audioIn.format);
                audioIn.extractor.seekTo(cut, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            for (Input in : new Input[]{videoIn, audioIn}) {
                if (in != null && in.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    size = Math.max(size, in.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(size);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int written = 0;
            for (Input in; (in = next(videoIn, audioIn)) != null; ) {
                long time = in.extractor.getSampleTime();
                boolean keyframe = (in.extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                if (time < 0 || (time >= endUs && (keyframe || in != videoIn))) {
                    in.done = true;
                    continue;
                }
                // Before the cut are the open GOP's leading frames, which need the frames left out.
                if (time >= cut && time < endUs) {
                    int length = in.extractor.readSampleData(buffer, 0);
                    if (length >= 0) {
                        info.set(0, length, time - cut, keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        muxer.writeSampleData(in.output, buffer, info);
                        written++;
                    }
                }
                in.extractor.advance();
            }
            if (written == 0) throw new IOException("Nothing to copy");
            muxer.stop();
        } finally {
            if (muxer != null) release(muxer);
            if (videoIn != null) videoIn.extractor.release();
            if (audioIn != null) audioIn.extractor.release();
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

    /** The unfinished input whose next sample comes first, so the tracks are written interleaved; null when both are done. */
    private static Input next(Input a, Input b) {
        boolean useA = a != null && !a.done;
        boolean useB = b != null && !b.done;
        if (useA && useB) return a.extractor.getSampleTime() <= b.extractor.getSampleTime() ? a : b;
        return useA ? a : useB ? b : null;
    }
}
