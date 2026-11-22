package app.morphe.extension.instagram.patches.download.gallery;

import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.PostType;

/** The private gallery tab a download lands in. */
public enum GalleryTab {
    POSTS, REELS, STORIES, DMS;

    /**
     * {@code type} is the root media's post type, null when the caller has no media (profile
     * picture, comment media, Instants). {@code hint} replaces a type that reads POST, its fallback.
     */
    public static GalleryTab of(PostType type, PostType hint, String subFolder) {
        if (Constants.DEFAULT_DM_FOLDER.equals(subFolder)) return DMS;
        if ((type == null || type == PostType.POST) && hint != null) type = hint;
        if (type == PostType.REEL) return REELS;
        if (type == PostType.STORY) return STORIES;
        return POSTS;
    }
}
