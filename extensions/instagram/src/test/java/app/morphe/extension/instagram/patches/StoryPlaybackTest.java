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
    public void onlyMusicHelpersFromTheStoryViewerFollowMuteStories() {
        Object story = new Object();
        Object feed = new Object();
        StoryPlayback.markStoryAudio(story);
        StoryPlayback.markStoryAudio(null);

        assertTrue(StoryPlayback.isStoryAudio(story));
        assertFalse(StoryPlayback.isStoryAudio(feed));
        assertFalse(StoryPlayback.isStoryAudio(null));
        // A helper outside stories keeps Instagram's decision without reading the switch.
        assertTrue(StoryPlayback.storyAudioSound(true, false, feed));
        assertFalse(StoryPlayback.storyAudioSound(false, false, story));
        assertTrue(StoryPlayback.storyAudioSound(true, true, story));
    }

    @Test
    public void withMuteStoriesOffInstagramDecides() {
        assertTrue(StoryPlayback.playsSound(true, false, false));
        assertTrue(StoryPlayback.playsSound(true, true, false));
    }
}
