/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import com.instagram.common.session.UserSession;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class ProfileExtras {
    private ProfileExtras() {
    }

    /**
     * Called by Instagram's profile grid filters with their verdict for one post; false drops it
     * from the grid's list. Paging is untouched, so Instagram keeps fetching pages as you scroll.
     */
    public static boolean keepInProfileGrid(boolean keep, Object media, UserSession session) {
        if (!keep || media == null || !Pref.hideProfileGridReels()) return keep;
        try {
            MediaData data = new MediaData(media, session);
            if (!data.isVideo()) return true;
            UserData owner = data.getUserData();
            // Your own grid is left whole: Instagram's pin handling there expects pinned posts to stay listed.
            return owner != null && session != null && session.getUserId().equals(owner.getUserId());
        } catch (Throwable t) {
            Logger.printException(() -> "keepInProfileGrid failure", t);
            return keep;
        }
    }

    /** Instagram's "fix the post count from the loaded grid" switch, off while reels are hidden from grids. */
    public static boolean profileGridCountRepair(boolean enabled) {
        try {
            return enabled && !Pref.hideProfileGridReels();
        } catch (Throwable t) {
            Logger.printException(() -> "profileGridCountRepair failure", t);
            return enabled;
        }
    }

    /** Takes Instagram's answer to whether a profile's Reels tab offers the Most viewed sort. */
    public static boolean profileReelsMostViewed(boolean available) {
        try {
            return mostViewedSort(Pref.profileReelsSort(), available);
        } catch (Throwable t) {
            Logger.printException(() -> "profileReelsMostViewed failure", t);
            return available;
        }
    }

    /** Takes Instagram's highlight reorder switch, read on a highlight long-press and when Reorder is picked. */
    public static boolean reorderHighlights(boolean enabled) {
        try {
            return enabled || Pref.reorderHighlights();
        } catch (Throwable t) {
            Logger.printException(() -> "reorderHighlights failure", t);
            return enabled;
        }
    }

    static boolean mostViewedSort(String mode, boolean available) {
        if ("keep".equals(mode)) return true;
        if ("remove".equals(mode)) return false;
        return available;
    }
}
