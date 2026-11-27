package app.morphe.extension.instagram.patches.instants.upload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.media.ExifInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class InstantsUploadTest {
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
    public void onlyAKnownDurationOverASetLimitIsTooLong() {
        assertTrue(InstantsUpload.tooLong(10_001, 10_000));
        assertFalse(InstantsUpload.tooLong(10_000, 10_000));
        assertFalse(InstantsUpload.tooLong(-1, 10_000));
        assertFalse(InstantsUpload.tooLong(60_000, 0));
    }

    @Test
    public void exifOrientationBecomesAClockwiseTurn() {
        assertEquals(0, InstantsUpload.exifDegrees(ExifInterface.ORIENTATION_NORMAL));
        assertEquals(90, InstantsUpload.exifDegrees(ExifInterface.ORIENTATION_ROTATE_90));
        assertEquals(180, InstantsUpload.exifDegrees(ExifInterface.ORIENTATION_ROTATE_180));
        assertEquals(270, InstantsUpload.exifDegrees(ExifInterface.ORIENTATION_ROTATE_270));
        assertEquals(90, InstantsUpload.exifDegrees(ExifInterface.ORIENTATION_TRANSVERSE));
        assertEquals(270, InstantsUpload.exifDegrees(ExifInterface.ORIENTATION_TRANSPOSE));
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
