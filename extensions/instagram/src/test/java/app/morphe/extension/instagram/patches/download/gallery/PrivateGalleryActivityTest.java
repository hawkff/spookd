package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class PrivateGalleryActivityTest {
    @Test
    public void thumbnailsAreSampledDownOnlyWhileBothSidesStillCoverTheTile() {
        assertEquals(1, PrivateGalleryActivity.sampleSize(300, 300, 300));
        assertEquals(4, PrivateGalleryActivity.sampleSize(4000, 1200, 300));
        assertEquals(2, PrivateGalleryActivity.sampleSize(1200, 700, 300));
    }

    @Test
    public void aThumbnailOfASmallSourceIsAsLargeAsItCanBe() {
        assertEquals(200, PrivateGalleryActivity.bestShorterSide(400, 200, 300, true));
        assertEquals(300, PrivateGalleryActivity.bestShorterSide(1200, 600, 300, true));
        assertEquals(200, PrivateGalleryActivity.bestShorterSide(200, 400, 300, false));
        assertEquals(300, PrivateGalleryActivity.bestShorterSide(1920, 1080, 300, false));
        assertEquals(Integer.MAX_VALUE, PrivateGalleryActivity.bestShorterSide(0, 0, 300, true));
    }

    @Test
    public void scaledVideoFramesStillCoverTheTileOnTheirShorterSide() {
        assertEquals(300, PrivateGalleryActivity.frameBox(1080, 1080, 300));
        assertEquals(533, PrivateGalleryActivity.frameBox(1920, 1080, 300));
        assertEquals(533, PrivateGalleryActivity.frameBox(1080, 1920, 300));
        assertEquals(1200, PrivateGalleryActivity.frameBox(10000, 100, 300));
        assertEquals(0, PrivateGalleryActivity.frameBox(0, 1080, 300));
    }

    @Test
    public void pickModeListsPhotosAndOnlyOffersVideosWhenThePickerTakesThem() {
        assertTrue(PrivateGalleryActivity.pickable(GalleryItem.PHOTO, true));
        assertTrue(PrivateGalleryActivity.pickable(GalleryItem.PHOTO, false));
        assertTrue(PrivateGalleryActivity.pickable(GalleryItem.VIDEO, false));
        assertFalse(PrivateGalleryActivity.pickable(GalleryItem.VIDEO, true));
        assertFalse(PrivateGalleryActivity.pickable(GalleryItem.AUDIO, false));
        assertFalse(PrivateGalleryActivity.pickable("voice", false));
    }
}
