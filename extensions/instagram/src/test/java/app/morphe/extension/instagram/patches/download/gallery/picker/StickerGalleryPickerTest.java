package app.morphe.extension.instagram.patches.download.gallery.picker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
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
    public void aNameCannotLeaveItsPickFolder() {
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234/..")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234/.")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-1234/..%2F..%2Fsecret")));
        assertNull(StickerGalleryPicker.pickedFile(CACHE, uri("/cache/piko_share/pick-..%2F..%2Fx/photo.jpg")));
    }
}
