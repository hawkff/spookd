package app.morphe.extension.instagram.patches;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReelsPlayerOptionsTest {
    private enum Source { FEED_TIMELINE, FEED_TIMELINE_POST_TAP, CLIPS_TAB, FEED_CONTEXTUAL_PROFILE }

    @Test
    public void onlyHomeFeedSourcesCountAsExpandedFeedVideos() {
        assertTrue(ReelsPlayerOptions.isHomeFeedSource(Source.FEED_TIMELINE));
        assertTrue(ReelsPlayerOptions.isHomeFeedSource(Source.FEED_TIMELINE_POST_TAP));
        assertFalse(ReelsPlayerOptions.isHomeFeedSource(Source.CLIPS_TAB));
        assertFalse(ReelsPlayerOptions.isHomeFeedSource(Source.FEED_CONTEXTUAL_PROFILE));
        assertFalse(ReelsPlayerOptions.isHomeFeedSource("FEED_TIMELINE"));
        assertFalse(ReelsPlayerOptions.isHomeFeedSource(null));
    }

    @Test
    public void anExpandedFeedViewerIsMutedOnlyOnItsFirstSoundCheck() {
        Object viewer = new Object();

        assertTrue(ReelsPlayerOptions.firstSoundCheck(viewer));
        assertFalse(ReelsPlayerOptions.firstSoundCheck(viewer));
        assertTrue(ReelsPlayerOptions.firstSoundCheck(new Object()));
        assertFalse(ReelsPlayerOptions.firstSoundCheck(null));
    }

    @Test
    public void tapRestartsOnlyTheEndedReelOnce() {
        Object ended = new Object();
        ReelsPlayerOptions.markEnded(ended);

        assertFalse(ReelsPlayerOptions.takeEndedReel(new Object(), true));
        assertFalse(ReelsPlayerOptions.takeEndedReel(null, true));
        assertTrue(ReelsPlayerOptions.takeEndedReel(ended, true));
        assertFalse(ReelsPlayerOptions.takeEndedReel(ended, true));
    }

    @Test
    public void playingTheEndedReelAgainClearsIt() {
        Object ended = new Object();
        ReelsPlayerOptions.markEnded(ended);

        ReelsPlayerOptions.clearIfStarted(new Object());
        ReelsPlayerOptions.clearIfStarted(null);
        assertTrue(ReelsPlayerOptions.takeEndedReel(ended, true));

        ReelsPlayerOptions.markEnded(ended);
        ReelsPlayerOptions.clearIfStarted(ended);
        assertFalse(ReelsPlayerOptions.takeEndedReel(ended, true));
    }

    @Test
    public void aLaterEndReplacesTheEarlierOne() {
        Object first = new Object();
        Object second = new Object();
        ReelsPlayerOptions.markEnded(first);
        ReelsPlayerOptions.markEnded(second);
        ReelsPlayerOptions.markEnded(null);

        assertFalse(ReelsPlayerOptions.takeEndedReel(first, true));
        assertTrue(ReelsPlayerOptions.takeEndedReel(second, true));
    }

    @Test
    public void turningStopLoopingOffDropsTheEndedReel() {
        Object ended = new Object();
        ReelsPlayerOptions.markEnded(ended);

        assertTrue(ReelsPlayerOptions.hasEndedReel(true));
        assertFalse(ReelsPlayerOptions.takeEndedReel(ended, false));
        // Turning it back on does not revive the dropped Reel.
        assertFalse(ReelsPlayerOptions.hasEndedReel(true));
        assertFalse(ReelsPlayerOptions.takeEndedReel(ended, true));
    }
}
