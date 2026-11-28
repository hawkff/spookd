package app.morphe.extension.instagram.patches.story.sticker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StoryStickerVideosTest {
    @Test
    public void theSwitchOnlyEverAddsVideos() {
        assertTrue(StoryStickerVideos.allows(false, true));
        assertTrue(StoryStickerVideos.allows(true, false));
        assertTrue(StoryStickerVideos.allows(true, true));
        assertFalse(StoryStickerVideos.allows(false, false));
    }
}
