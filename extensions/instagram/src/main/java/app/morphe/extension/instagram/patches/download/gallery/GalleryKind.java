package app.morphe.extension.instagram.patches.download.gallery;

import java.util.Locale;

/** Classifies downloads without Android or download-library types. */
public final class GalleryKind {
    private GalleryKind() {
    }

    /** The kind of a download that carries no media type, from its file name's extension. */
    public static String of(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.matches(".*\\.(mp4|mov|webm|mkv|3gp)")) return GalleryItem.VIDEO;
        if (name.matches(".*\\.(m4a|mp3|aac|ogg|opus|wav|amr)")) return GalleryItem.AUDIO;
        return GalleryItem.PHOTO;
    }
}
