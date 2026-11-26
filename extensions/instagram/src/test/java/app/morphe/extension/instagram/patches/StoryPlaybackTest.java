package app.morphe.extension.instagram.patches;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StoryPlaybackTest {
    @Test
    public void muteStoriesSilencesStoriesUntilTheViewerAsksForSound() {
        assertFalse(StoryPlayback.playsSound(true, false, true));
        assertTrue(StoryPlayback.playsSound(true, true, true));
    }

    @Test
    public void muteStoriesNeverTurnsSoundOn() {
        assertFalse(StoryPlayback.playsSound(false, false, true));
        assertFalse(StoryPlayback.playsSound(false, true, true));
        assertFalse(StoryPlayback.playsSound(false, false, false));
    }

    @Test
    public void withMuteStoriesOffInstagramDecides() {
        assertTrue(StoryPlayback.playsSound(true, false, false));
        assertTrue(StoryPlayback.playsSound(true, true, false));
    }
}
