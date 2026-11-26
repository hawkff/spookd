/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/** The swipe between tabs setting, applied to Instagram's final decision whether the main tabs swipe. */
@SuppressWarnings("unused")
public final class TabSwipe {
    private TabSwipe() { }

    // Held apart so loading this class for a test does not read settings.
    private static final class Mode {
        static final Pref.Cached<String> VALUE = new Pref.Cached<>(Pref::swipeBetweenTabs, Settings.SWIPE_BETWEEN_TABS.key);
    }

    /**
     * Called with each result of Instagram's swipeable-tabs check, on whatever thread asks; every reader of the tab host
     * goes through that check, so the pager and its layout always agree. Default keeps Instagram's answer.
     */
    public static boolean swipeTabs(boolean instagram) {
        try {
            return decide(Mode.VALUE.get(), instagram);
        } catch (Throwable t) {
            Logger.printException(() -> "TabSwipe.swipeTabs failed", t);
            return instagram;
        }
    }

    static boolean decide(String mode, boolean instagram) {
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) return false;
        return instagram;
    }
}
