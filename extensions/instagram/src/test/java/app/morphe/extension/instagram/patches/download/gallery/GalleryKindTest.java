package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GalleryTest {
    @Test
    public void kindFromTheFileNameOfDownloadsWithoutAMediaType() {
        assertEquals(GalleryItem.VIDEO, Gallery.kindOf("alice_1.mp4"));
        assertEquals(GalleryItem.VIDEO, Gallery.kindOf("piko_2.MOV"));
        assertEquals(GalleryItem.AUDIO, Gallery.kindOf("piko_3.m4a"));
        assertEquals(GalleryItem.AUDIO, Gallery.kindOf("4.mp3"));
        assertEquals(GalleryItem.PHOTO, Gallery.kindOf("alice_dp.jpg"));
        assertEquals(GalleryItem.PHOTO, Gallery.kindOf("bob_tag.gif"));
        assertEquals(GalleryItem.PHOTO, Gallery.kindOf("mp4"));
        assertEquals(GalleryItem.PHOTO, Gallery.kindOf(null));
    }
}
