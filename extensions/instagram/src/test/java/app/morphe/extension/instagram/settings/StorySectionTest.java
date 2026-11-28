package app.morphe.extension.instagram.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class StorySectionTest {
    private final boolean saved = SettingsStatus.storyStickerVideos;

    @After
    public void restore() {
        SettingsStatus.storyStickerVideos = saved;
    }

    @Test
    public void theStickerVideosPatchAloneShowsTheStorySection() {
        SettingsStatus.storyStickerVideos = false;
        boolean others = SettingsStatus.storySection();
        SettingsStatus.storyStickerVideos = true;
        assertTrue(SettingsStatus.storySection());
        if (!others) {
            SettingsStatus.storyStickerVideos = false;
            assertFalse(SettingsStatus.storySection());
        }
    }
}
