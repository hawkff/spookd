package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GalleryKindTest {
    @Test
    public void kindFromTheFileNameOfDownloadsWithoutAMediaType() {
        assertEquals(GalleryItem.VIDEO, GalleryKind.of("alice_1.mp4"));
        assertEquals(GalleryItem.VIDEO, GalleryKind.of("piko_2.MOV"));
        assertEquals(GalleryItem.AUDIO, GalleryKind.of("piko_3.m4a"));
        assertEquals(GalleryItem.AUDIO, GalleryKind.of("4.mp3"));
        assertEquals(GalleryItem.PHOTO, GalleryKind.of("alice_dp.jpg"));
        assertEquals(GalleryItem.PHOTO, GalleryKind.of("bob_tag.gif"));
        assertEquals(GalleryItem.PHOTO, GalleryKind.of("mp4"));
        assertEquals(GalleryItem.PHOTO, GalleryKind.of(null));
    }
}
