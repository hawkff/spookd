/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Skips reloads of Home and Reels that Instagram starts on its own. Each entry point runs inside
 * Instagram's code and returns Instagram's own decision on any failure.
 */
@SuppressWarnings("unused")
public final class RefreshControl {
    private RefreshControl() {
    }

    /**
     * From Home's scroll to top, on the main thread, after it has scrolled and before it reloads;
     * {@code reason} says what asked for the scroll.
     */
    public static boolean allowHomeScrollRefresh(boolean refresh, Object reason) {
        try {
            return refresh && !(isHomeTabTap(reason) && Pref.disableHomeTabRefresh());
        } catch (Throwable t) {
            Logger.printException(() -> "allowHomeScrollRefresh failure", t);
            return refresh;
        }
    }

    /**
     * From Home's scroll to top, on the main thread, after it has scrolled: the New posts pill it would
     * open instead of reloading, or null to skip it. Tapping the pill itself does not pass here.
     */
    public static Object homeScrollNewPostsPill(Object pill, Object reason) {
        try {
            return newPostsPill(pill, reason, pill != null && isHomeTabTap(reason) && Pref.disableHomeTabRefresh());
        } catch (Throwable t) {
            Logger.printException(() -> "homeScrollNewPostsPill failure", t);
            return pill;
        }
    }

    static Object newPostsPill(Object pill, Object reason, boolean skipTabRefresh) {
        return skipTabRefresh && isHomeTabTap(reason) ? null : pill;
    }

    static boolean isHomeTabTap(Object reason) {
        return reason instanceof Enum && "HOME_PRESS".equals(((Enum<?>) reason).name());
    }

    /** From the alarm, on the main thread, that reloads Home while the app is in the background. */
    public static boolean allowHomeBackgroundRefresh() {
        try {
            return !Pref.disableHomeBackgroundRefresh();
        } catch (Throwable t) {
            Logger.printException(() -> "allowHomeBackgroundRefresh failure", t);
            return true;
        }
    }

    /** From the Reels tab tap, on the main thread; false still scrolls to the first Reel, without reloading. */
    public static boolean allowReelsTabRefresh(boolean refresh) {
        try {
            return refresh && !Pref.disableReelsTabRefresh();
        } catch (Throwable t) {
            Logger.printException(() -> "allowReelsTabRefresh failure", t);
            return refresh;
        }
    }
}
