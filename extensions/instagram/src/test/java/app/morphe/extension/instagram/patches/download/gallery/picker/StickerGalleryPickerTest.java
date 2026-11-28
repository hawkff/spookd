package app.morphe.extension.instagram.patches.download.gallery.picker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class StickerGalleryPickerTest {
    private static final File CACHE = new File("/data/cache");

    private static Uri uri(String path) {
        return Uri.parse("content://app.provider" + path);
    }

    @Test
    public void aPickUriMapsToItsCopyInTheShareFolder() {
        assertEquals(new File("/data/cache/piko_share/pick-1234/photo.jpg"),
                StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234/photo.jpg")));
    }

    @Test
    public void onlyPickFoldersOfTheShareFolderAreAccepted() {
        assertNull(StickerGalleryPicker.pickedFile(CACHE, null));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, Uri.parse("file:///data/cache/piko_share/pick-1/a.jpg")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/1234/photo.jpg")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/other/pick-1234/photo.jpg")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/files/piko_share/pick-1234/photo.jpg")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234/a/b.jpg")));
    }

    @Test
    public void aLateResultIsDroppedOnceItsGridIsHiddenOrAnotherTapOpenedTheGallery() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout tray = new FrameLayout(activity);
        View grid = new View(activity);
        tray.addView(grid);
        activity.setContentView(tray);
        // No tap yet, so the current tap is 0.
        assertTrue(StickerGalleryPicker.stillShown(grid, 0));
        assertFalse(StickerGalleryPicker.stillShown(grid, 1));
        assertFalse(StickerGalleryPicker.stillShown(null, 0));
        // Instagram leaves a grid by hiding it, still attached.
        grid.setVisibility(View.INVISIBLE);
        assertTrue(grid.isAttachedToWindow());
        assertFalse(StickerGalleryPicker.stillShown(grid, 0));
    }

    @Test
    public void aNameCannotLeaveItsPickFolder() {
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234/..")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234/.")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234/..%2F..%2Fsecret")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-..%2F..%2Fx/photo.jpg")));
    }
}
