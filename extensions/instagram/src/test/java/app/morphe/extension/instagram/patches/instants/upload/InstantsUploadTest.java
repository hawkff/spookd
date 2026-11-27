package app.morphe.extension.instagram.patches.instants.upload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class InstantsUploadTest {
    @Test
    public void aPickIsDroppedWhenItsCameraClosedWasReleasedOrTheSwitchIsOff() {
        assertTrue(InstantsUpload.accepts(true, true, true));
        assertFalse(InstantsUpload.accepts(false, true, true));
        assertFalse(InstantsUpload.accepts(true, false, true));
        assertFalse(InstantsUpload.accepts(true, true, false));
    }

    @Test
    public void aCameraWhosePreviewIsGoneHasNoLiveShutter() {
        View preview = new View(RuntimeEnvironment.getApplication());
        assertNull(new InstantsUpload.Camera(preview, new Object()).live());
    }

    @Test
    public void centerSquareTakesTheMiddleOfTheLongerSide() {
        assertArrayEquals(new int[]{0, 420, 1080}, InstantsUpload.centerSquare(1080, 1920));
        assertArrayEquals(new int[]{420, 0, 1080}, InstantsUpload.centerSquare(1920, 1080));
        assertArrayEquals(new int[]{0, 0, 500}, InstantsUpload.centerSquare(500, 500));
    }

    @Test
    public void sampleSizeKeepsTheShorterSideAtLeastTheTarget() {
        assertEquals(1, InstantsUpload.sampleSize(1080, 1080));
        assertEquals(1, InstantsUpload.sampleSize(2000, 1080));
        assertEquals(2, InstantsUpload.sampleSize(2160, 1080));
        assertEquals(4, InstantsUpload.sampleSize(6000, 1080));
        assertEquals(1, InstantsUpload.sampleSize(300, 1080));
    }

    @Test
    public void boundedSampleSizeKeepsAPanoramaWithinThePixelBudget() {
        int sample = InstantsUpload.boundedSampleSize(65535, 1080, 1080, 8_000_000L);
        assertTrue((long) (65535 / sample) * (1080 / sample) <= 8_000_000L);
        assertEquals(4, sample);
        assertEquals(2, InstantsUpload.boundedSampleSize(4032, 3024, 1080, 8_000_000L));
        assertEquals(1, InstantsUpload.boundedSampleSize(1080, 1920, 1080, 8_000_000L));
    }

    @Test
    public void onlyAKnownDurationWithinAKnownLimitIsAllowed() {
        assertTrue(InstantsUpload.durationAllowed(10_000, 10_000));
        assertTrue(InstantsUpload.durationAllowed(1, 10_000));
        assertFalse(InstantsUpload.durationAllowed(10_001, 10_000));
        assertFalse(InstantsUpload.durationAllowed(-1, 10_000));
        assertFalse(InstantsUpload.durationAllowed(0, 10_000));
        assertFalse(InstantsUpload.durationAllowed(5_000, 0));
    }

    @Test
    public void onlyVideosTallEnoughForTheCameraSquareFit() {
        // A 1080 px preview with 24 px margins and the square 100 px down needs 1132 px of scaled height.
        assertTrue(InstantsUpload.fitsCameraCrop(1080, 1920, 0, 1080, 24, 100));
        assertFalse(InstantsUpload.fitsCameraCrop(1920, 1080, 0, 1080, 24, 100));
        assertFalse(InstantsUpload.fitsCameraCrop(1080, 1080, 0, 1080, 24, 100));
        assertTrue(InstantsUpload.fitsCameraCrop(1080, 1080, 0, 1080, 24, 0));
        assertTrue(InstantsUpload.fitsCameraCrop(1920, 1080, 90, 1080, 24, 100));
        assertFalse(InstantsUpload.fitsCameraCrop(1080, 1920, 270, 1080, 24, 100));
        assertFalse(InstantsUpload.fitsCameraCrop(-1, -1, -1, 1080, 24, 100));
        assertFalse(InstantsUpload.fitsCameraCrop(1080, 1920, 0, 0, 0, 0));
    }

    @Test
    public void exifOrientationMovesTheTopLeftCornerOfA4By2Photo() {
        assertCorner(ExifInterface.ORIENTATION_NORMAL, 0, 0, 4, 2);
        assertCorner(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 4, 0, 4, 2);
        assertCorner(ExifInterface.ORIENTATION_ROTATE_180, 4, 2, 4, 2);
        assertCorner(ExifInterface.ORIENTATION_FLIP_VERTICAL, 0, 2, 4, 2);
        assertCorner(ExifInterface.ORIENTATION_TRANSPOSE, 0, 0, 2, 4);
        assertCorner(ExifInterface.ORIENTATION_ROTATE_90, 2, 0, 2, 4);
        assertCorner(ExifInterface.ORIENTATION_TRANSVERSE, 2, 4, 2, 4);
        assertCorner(ExifInterface.ORIENTATION_ROTATE_270, 0, 4, 2, 4);
    }

    private static void assertCorner(int orientation, float x, float y, float width, float height) {
        Matrix matrix = InstantsUpload.exifMatrix(orientation);
        RectF bounds = new RectF(0, 0, 4, 2);
        matrix.mapRect(bounds);
        float[] corner = {0, 0};
        matrix.mapPoints(corner);
        String name = "orientation " + orientation;
        assertEquals(name, width, bounds.width(), 0.01f);
        assertEquals(name, height, bounds.height(), 0.01f);
        assertEquals(name, x, corner[0] - bounds.left, 0.01f);
        assertEquals(name, y, corner[1] - bounds.top, 0.01f);
    }

    @Test
    public void galleryOpensInPickModeOnTheModalScreen() {
        Intent intent = InstantsUpload.galleryIntent("com.instagram.android");
        assertEquals("com.instagram.modal.ModalActivity", intent.getComponent().getClassName());
        assertEquals("private_gallery", intent.getStringExtra("piko_launch_type"));
        assertTrue(intent.getBooleanExtra("spookd_gallery_pick", false));
        assertFalse(intent.getBooleanExtra("spookd_gallery_photos_only", false));
    }

    @Test
    public void phoneBelowAndroid13PicksPhotosAndVideosFromFiles() {
        Intent intent = InstantsUpload.phoneIntent();
        assertEquals(Intent.ACTION_GET_CONTENT, intent.getAction());
        assertArrayEquals(new String[]{"image/*", "video/*"}, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
    }
}
