package app.morphe.extension.instagram.patches.story.sticker;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Lets the story photo sticker's gallery grid list videos. Instagram already trims a picked video
 * and adds it as a video sticker; only the grid's check for which pickers may offer videos blocks it.
 */
public final class StoryStickerVideos {
    private StoryStickerVideos() {
    }

    /** Main thread, from the grid's refresh: each check that lets it offer videos; never throws. */
    public static boolean videos(boolean original) {
        try {
            return allows(original, Pref.storyStickerVideos());
        } catch (Throwable t) {
            Logger.printException(() -> "StoryStickerVideos: cannot read the switch", t);
            return original;
        }
    }

    static boolean allows(boolean original, boolean on) {
        return original || on;
    }
}
