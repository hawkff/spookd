package app.morphe.extension.instagram.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackSpeedTest {
    private static class Item {
        private final Object media;
        private final int index = 3;

        Item(Object media) {
            this.media = media;
        }
    }

    private static class Wrapper extends Item {
        Wrapper(Object media) {
            super(media);
        }
    }

    @Test
    public void parsesStoredSpeedsAndFallsBackToNormal() {
        assertEquals(1.5f, PlaybackSpeed.parseSpeed("1.5"), 0f);
        assertEquals(2f, PlaybackSpeed.parseSpeed("2"), 0f);
        assertEquals(1f, PlaybackSpeed.parseSpeed(null), 0f);
        assertEquals(1f, PlaybackSpeed.parseSpeed("fast"), 0f);
        assertEquals(1f, PlaybackSpeed.parseSpeed("NaN"), 0f);
        assertEquals(1f, PlaybackSpeed.parseSpeed("0.1"), 0f);
        assertEquals(1f, PlaybackSpeed.parseSpeed("5"), 0f);
    }

    @Test
    public void untouchedReelGetsTheDefaultWhenInstagramRestoresSpeeds() {
        float defaultSpeed = 1.5f;
        // A Reel with a speed picked in Instagram's menu keeps it, including an explicit 1x.
        assertEquals(Float.valueOf(2f), PlaybackSpeed.resolveReelSpeed(2f, null, true, defaultSpeed));
        assertEquals(Float.valueOf(1f), PlaybackSpeed.resolveReelSpeed(1f, null, true, defaultSpeed));
        // The next, untouched Reel: Instagram restores 1x, which becomes the default.
        assertEquals(Float.valueOf(1.5f), PlaybackSpeed.resolveReelSpeed(1f, null, false, defaultSpeed));
        // Instagram's own session-wide speeds stay.
        assertEquals(Float.valueOf(0.5f), PlaybackSpeed.resolveReelSpeed(0.5f, null, false, defaultSpeed));
        // A panel pick wins on resume, and nothing is applied where Instagram applies nothing.
        assertEquals(Float.valueOf(1.25f), PlaybackSpeed.resolveReelSpeed(1f, 1.25f, false, defaultSpeed));
        assertNull(PlaybackSpeed.resolveReelSpeed(null, 1.25f, false, defaultSpeed));
        // With the default at 1x nothing changes.
        assertEquals(Float.valueOf(1f), PlaybackSpeed.resolveReelSpeed(1f, null, false, 1f));
    }

    @Test
    public void labelsSpeedsWithoutTrailingZeros() {
        assertEquals("1x", PlaybackSpeed.speedLabel(1f));
        assertEquals("0.5x", PlaybackSpeed.speedLabel(0.5f));
        assertEquals("1.25x", PlaybackSpeed.speedLabel(1.25f));
        assertEquals("2x", PlaybackSpeed.speedLabel(2f));
    }

    @Test
    public void formatsMinutesAndHours() {
        assertEquals("0:00", PlaybackSpeed.formatTime(-5));
        assertEquals("0:09", PlaybackSpeed.formatTime(9_999));
        assertEquals("1:05", PlaybackSpeed.formatTime(65_000));
        assertEquals("1:01:01", PlaybackSpeed.formatTime(3_661_000));
    }

    @Test
    public void skipsStayInsideTheVideo() {
        assertEquals(15_000, PlaybackSpeed.seekTarget(5_000, 10_000, 60_000));
        assertEquals(0, PlaybackSpeed.seekTarget(4_000, -10_000, 60_000));
        assertEquals(59_999, PlaybackSpeed.seekTarget(55_000, 10_000, 60_000));
    }

    @Test
    public void findsTheMediaDirectlyOrInsideTheReelItem() throws Exception {
        Object media = new Object();
        assertTrue(PlaybackSpeed.holdsMedia(media, media));
        assertTrue(PlaybackSpeed.holdsMedia(new Item(media), media));
        assertTrue(PlaybackSpeed.holdsMedia(new Wrapper(media), media));
        assertFalse(PlaybackSpeed.holdsMedia(new Item(new Object()), media));
        assertFalse(PlaybackSpeed.holdsMedia(null, media));
        assertFalse(PlaybackSpeed.holdsMedia(new Item(media), null));
    }
}
