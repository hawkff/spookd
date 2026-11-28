package app.morphe.extension.instagram.patches.story.sticker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StoryStickerVideosTest {
    @Test
    public void onlyAGridThatIsNotTheLayoutPickerIsLetIn() {
        assertTrue(StoryStickerVideos.admits(false, true));
        assertFalse(StoryStickerVideos.admits(true, true));
        assertFalse(StoryStickerVideos.admits(false, false));
        assertFalse(StoryStickerVideos.admits(true, false));
    }

    @Test
    public void theLayoutPickerKeepsItsOwnFlag() {
        // Without the patch's settings the switch reads off, so nothing is let in.
        assertTrue(StoryStickerVideos.layoutCheck(true));
        assertFalse(StoryStickerVideos.layoutFlag(false));
        assertTrue(StoryStickerVideos.layoutFlag(true));
        assertFalse(StoryStickerVideos.layoutCheck(false));
        assertFalse(StoryStickerVideos.layoutFlag(false));
    }
}
