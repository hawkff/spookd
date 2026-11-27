/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class ProfileExtras {
    // Grids that wanted their next page while a request was still loading; checked again once it succeeds.
    private static final Set<Object> WAITING_GRIDS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

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
     * loader for the next page: hidden reels can leave a page with nothing to show or scroll. Never after a
     * failed request, so an error is not retried on its own; Instagram's Retry row stays in charge.
     */
    public static boolean profileGridNeedsNextPage(Object grid, View list, boolean loading, boolean failed, boolean hasMore) {
        try {
            boolean canScrollDown = list != null && list.canScrollVertically(1);
            return onGridChanged(grid, Pref.hideProfileGridReels(), loading, failed, hasMore, canScrollDown);
        } catch (Throwable t) {
            Logger.printException(() -> "profileGridNeedsNextPage failure", t);
            return false;
        }
    }

    /** Called when a profile grid request succeeded, after its loading state cleared; checks waiting grids once. */
    public static void onProfileGridRequestFinished() {
        try {
            for (Object grid : takeWaitingGrids()) Utils.runOnMainThread(() -> rerunProfileGridChange(grid));
        } catch (Throwable t) {
            Logger.printException(() -> "onProfileGridRequestFinished failure", t);
        }
    }

    /** Replaced at patch time with a call to Instagram's grid data change handler for {@code grid}. */
    public static void rerunProfileGridChange(Object grid) {
    }

    static boolean onGridChanged(Object grid, boolean hiding, boolean loading, boolean failed, boolean hasMore,
                                 boolean canScrollDown) {
        boolean wanted = hiding && hasMore && !failed && !canScrollDown;
        if (wanted && loading) WAITING_GRIDS.add(grid);
        else WAITING_GRIDS.remove(grid);
        return wanted && !loading;
    }

    static List<Object> takeWaitingGrids() {
        synchronized (WAITING_GRIDS) {
            List<Object> grids = new ArrayList<>(WAITING_GRIDS);
            WAITING_GRIDS.clear();
            return grids;
        }
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
