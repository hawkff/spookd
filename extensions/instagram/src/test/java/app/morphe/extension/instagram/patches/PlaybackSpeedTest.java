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
    public void defaultReplacesOnlyInstagramsUnpickedOneX() {
        float defaultSpeed = 1.5f;
        // Untouched Reel: Instagram restores 1x, which becomes the default.
        assertEquals(Float.valueOf(1.5f), PlaybackSpeed.resolveReelSpeed(1f, null, false, defaultSpeed));
        // A speed picked in Instagram's own menu stays, session-wide, until changed there.
        assertEquals(Float.valueOf(2f), PlaybackSpeed.resolveReelSpeed(2f, null, false, defaultSpeed));
        assertEquals(Float.valueOf(2f), PlaybackSpeed.resolveReelSpeed(2f, null, true, defaultSpeed));
        // An explicit 1x pick in Instagram's menu stays 1x on that Reel.
        assertEquals(Float.valueOf(1f), PlaybackSpeed.resolveReelSpeed(1f, null, true, defaultSpeed));
        // A panel pick applies to its Reel only and wins on resume.
        assertEquals(Float.valueOf(1.25f), PlaybackSpeed.resolveReelSpeed(1f, 1.25f, false, defaultSpeed));
        // Nothing is applied where Instagram applies nothing, and a 1x default changes nothing.
        assertNull(PlaybackSpeed.resolveReelSpeed(null, 1.25f, false, defaultSpeed));
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
