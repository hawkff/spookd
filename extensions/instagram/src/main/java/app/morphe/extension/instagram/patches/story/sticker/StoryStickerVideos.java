package app.morphe.extension.instagram.patches.story.sticker;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Lets the story photo sticker's gallery grid list videos. Instagram already trims a picked video
 * and adds it as a video sticker; only the grid's check for which pickers may offer videos blocks it.
 * That check admits the story layout picker with a flag, so the grid is let through as if it were
 * that picker, and the flag is forced only for a grid let through this way.
 */
public final class StoryStickerVideos {
    // Set by the layout check and read by the flag read right after it in the same refresh; main thread only.
    private static boolean admitted;

    private StoryStickerVideos() {
    }

    /** Main thread, from the grid's refresh: whether it is the layout picker; never throws. */
    public static boolean layoutCheck(boolean original) {
        try {
            admitted = admits(original, Pref.storyStickerVideos());
            return original || admitted;
        } catch (Throwable t) {
            admitted = false;
            Logger.printException(() -> "StoryStickerVideos: cannot read the switch", t);
            return original;
        }
    }

    /** Main thread, the flag read after {@link #layoutCheck}: Instagram's value for the layout picker itself. */
    public static boolean layoutFlag(boolean original) {
        boolean forced = admitted;
        admitted = false;
        return original || forced;
    }

    /** Whether the switch lets in a grid that is not the layout picker. */
    static boolean admits(boolean layoutPicker, boolean on) {
        return !layoutPicker && on;
    }
}
