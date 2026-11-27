package app.morphe.extension.instagram.patches.download.gallery.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class VideoTrimTest {
    private static final long[] POINTS = {0, 2_000_000, 4_000_000, 6_000_000, 7_500_000};

    @Test
    public void cutPointsAreSortedUniqueKeyframesBelowTheDurationThenTheDuration() {
        long[] syncs = {4_000_000, 0, 2_000_000, 2_000_000, 8_000_000, 99};
        assertArrayEquals(new long[]{0, 2_000_000, 4_000_000, 7_500_000},
                VideoTrim.cutPoints(syncs, 5, 7_500_000));
        assertArrayEquals(new long[]{0, 1_000_000}, VideoTrim.cutPoints(new long[]{5_000_000}, 1, 1_000_000));
        assertArrayEquals(new long[]{0, 1_000_000}, VideoTrim.cutPoints(new long[0], 0, 1_000_000));
    }

    @Test
    public void aFirstKeyframeAfterZeroStartsTheCutPointsAtZero() {
        assertArrayEquals(new long[]{0, 2_000_000, 5_000_000},
                VideoTrim.cutPoints(new long[]{2_000_000, 500_000}, 2, 5_000_000));
    }

    @Test
    public void aHandleSnapsToTheNearestKeyframeItMayTake() {
        assertEquals(2_000_000, VideoTrim.snap(POINTS, 2_900_000, 0, 7_500_000));
        assertEquals(4_000_000, VideoTrim.snap(POINTS, 3_100_000, 0, 7_500_000));
        // A tie takes the earlier keyframe.
        assertEquals(2_000_000, VideoTrim.snap(POINTS, 3_000_000, 0, 7_500_000));
        // The start stays before the end, the end after the start.
        assertEquals(4_000_000, VideoTrim.snap(POINTS, 7_000_000, 0, 6_000_000 - 1));
        assertEquals(6_000_000, VideoTrim.snap(POINTS, 1_000_000, 4_000_000 + 1, 7_500_000));
        assertEquals(-1, VideoTrim.snap(POINTS, 1_000_000, 100, 200));
    }

    @Test
    public void aStepMovesOneKeyframeWithinItsBounds() {
        assertEquals(4_000_000, VideoTrim.step(POINTS, 6_000_000, -1, 0, 7_500_000));
        assertEquals(7_500_000, VideoTrim.step(POINTS, 6_000_000, 1, 0, 7_500_000));
        assertEquals(2_000_000, VideoTrim.step(POINTS, 0, 1, 0, 7_500_000));
        assertEquals(0, VideoTrim.step(POINTS, 0, -1, 0, 7_500_000));
        // The start cannot reach the end at 4 s.
        assertEquals(2_000_000, VideoTrim.step(POINTS, 2_000_000, 1, 0, 4_000_000 - 1));
        // The end cannot reach the start at 4 s.
        assertEquals(6_000_000, VideoTrim.step(POINTS, 6_000_000, -1, 4_000_000 + 1, 7_500_000));
    }

    @Test
    public void timesShowTenthsOfASecond() {
        assertEquals("0:00.0", VideoTrim.time(0));
        assertEquals("0:01.3", VideoTrim.time(1_250_000));
        assertEquals("0:01.2", VideoTrim.time(1_249_999));
        assertEquals("1:00.0", VideoTrim.time(59_960_000));
        assertEquals("12:34.5", VideoTrim.time(754_500_000));
        assertEquals("1:02:03.4", VideoTrim.time(3_723_400_000L));
        assertEquals("0:00.0", VideoTrim.time(-5));
    }

    @Test
    public void savedItemsAreNamedAfterTheOriginal() {
        assertEquals("clip_trimmed.mp4", VideoTrim.derivedName("clip.mp4", "_trimmed", ".mp4"));
        assertEquals("clip_trimmed.mp4", VideoTrim.derivedName("clip_trimmed.mp4", "_trimmed", ".mp4"));
        assertEquals("clip_audio.m4a", VideoTrim.derivedName("clip.mp4", "_audio", ".m4a"));
        assertEquals("video_frame.jpg", VideoTrim.derivedName(null, "_frame", ".jpg"));
        assertEquals(".webm", VideoTrim.extension("a.WEBM", ".mp4"));
        assertEquals(".mp4", VideoTrim.extension("noextension", ".mp4"));
    }

    @Test
    public void audioIsM4aForAacElseKeepsTheSourceContainer() {
        assertEquals(".m4a", VideoTrim.audioExtension("audio/mp4a-latm", ".mp4"));
        assertEquals(".m4a", VideoTrim.audioExtension("audio/mp4a-latm", ".mkv"));
        assertEquals(".webm", VideoTrim.audioExtension("audio/opus", ".webm"));
        assertEquals(".3gp", VideoTrim.audioExtension("audio/3gpp", ".3gp"));
        // Never MPEG-4 bytes under another container's name, nor codecs the MPEG-4 muxer cannot hold.
        assertNull(VideoTrim.audioExtension("audio/vorbis", ".mkv"));
        assertNull(VideoTrim.audioExtension("audio/mpeg", ".mp4"));
        assertNull(VideoTrim.audioExtension("audio/mpeg", ".mov"));
        assertNull(VideoTrim.audioExtension("audio/mpeg", ".webm"));
        assertNull(VideoTrim.audioExtension("audio/opus", ".3gp"));
        assertEquals(VideoTrim.muxerFormat(".mp4"), VideoTrim.muxerFormat(".m4a"));
        assertEquals(android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, VideoTrim.muxerFormat(".webm"));
        assertEquals(android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP, VideoTrim.muxerFormat(".3gp"));
        assertEquals(android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, VideoTrim.muxerFormat(".mp4"));
    }

    @Test
    public void aTrimIsWrittenOnlyToAContainerThatHoldsBothTracks() {
        assertEquals(".mp4", VideoTrim.trimExtension(".mp4", "video/avc", "audio/mp4a-latm"));
        assertEquals(".mp4", VideoTrim.trimExtension(".mov", "video/hevc", "audio/mp4a-latm"));
        assertEquals(".mp4", VideoTrim.trimExtension(".mkv", "video/avc", null));
        assertEquals(".mp4", VideoTrim.trimExtension(".3gp", "video/mp4v-es", "audio/mp4a-latm"));
        assertEquals(".webm", VideoTrim.trimExtension(".webm", "video/x-vnd.on2.vp9", "audio/opus"));
        assertEquals(".webm", VideoTrim.trimExtension(".webm", "video/x-vnd.on2.vp8", "audio/vorbis"));
        assertEquals(".webm", VideoTrim.trimExtension(".webm", "video/x-vnd.on2.vp8", null));
        // WebM output only for a WebM source.
        assertNull(VideoTrim.trimExtension(".mkv", "video/x-vnd.on2.vp9", "audio/opus"));
        assertNull(VideoTrim.trimExtension(".mp4", "video/avc", "audio/opus"));
        assertNull(VideoTrim.trimExtension(".mp4", "video/avc", "audio/mpeg"));
        assertNull(VideoTrim.trimExtension(".webm", "video/x-vnd.on2.vp9", "audio/mp4a-latm"));
        assertNull(VideoTrim.trimExtension(".webm", "video/av01", "audio/opus"));
        assertNull(VideoTrim.trimExtension(".mp4", null, null));
    }
}
