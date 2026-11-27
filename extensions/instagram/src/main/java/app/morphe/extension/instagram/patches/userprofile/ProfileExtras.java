/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import android.view.View;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class ProfileExtras {
    private ProfileExtras() {
    }

    /**
     * Called by Instagram's profile grid filters with their verdict for one post; false drops it
     * from the grid's list. Paging is untouched; {@link #profileGridNeedsNextPage} covers emptied pages.
     */
    public static boolean keepInProfileGrid(boolean keep, Object media) {
        if (!keep || media == null || !Pref.hideProfileGridReels()) return keep;
        try {
            return !new MediaData(media).isVideo();
        } catch (Throwable t) {
            Logger.printException(() -> "keepInProfileGrid failure", t);
            return keep;
        }
    }

    /**
     * Called on the main thread at the start of each profile grid data change. True asks Instagram's own
     * loader for the next page: hidden reels can leave a page with nothing to show or scroll.
     */
    public static boolean profileGridNeedsNextPage(View grid, boolean loading, boolean hasMore) {
        try {
            return needsNextPage(Pref.hideProfileGridReels(), loading, hasMore, grid != null && grid.canScrollVertically(1));
        } catch (Throwable t) {
            Logger.printException(() -> "profileGridNeedsNextPage failure", t);
            return false;
        }
    }

    static boolean needsNextPage(boolean hiding, boolean loading, boolean hasMore, boolean canScrollDown) {
        return hiding && hasMore && !loading && !canScrollDown;
    }

    /**
     * Instagram grid switches that are wrong for a filtered grid, off while reels are hidden: your own
     * grid's pinned-post sync (fails on a pinned reel the filter left out) and the post count repair
     * (rewrites the profile's post count to the filtered grid size).
     */
    public static boolean offWhileGridReelsHidden(boolean enabled) {
        try {
            return enabled && !Pref.hideProfileGridReels();
        } catch (Throwable t) {
            Logger.printException(() -> "offWhileGridReelsHidden failure", t);
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
