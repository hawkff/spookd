/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import java.util.function.Supplier;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/** The swipe between tabs setting, applied to Instagram's final decision whether the main tabs swipe. */
@SuppressWarnings("unused")
public final class TabSwipe {
    // Replaced in tests.
    static Supplier<String> reader = () -> Pref.swipeBetweenTabs();
    // Read once per process: the tab host is built from the first answer, so a change waits for the restart it asks for.
    static volatile String frozen;

    private TabSwipe() { }

    /** Called with each result of Instagram's swipeable-tabs check, on whatever thread asks; Default keeps Instagram's answer. */
    public static boolean swipeTabs(boolean instagram) {
        try {
            return decide(mode(), instagram);
        } catch (Throwable t) {
            Logger.printException(() -> "TabSwipe.swipeTabs failed", t);
            return instagram;
        }
    }

    static String mode() {
        String mode = frozen;
        if (mode != null) return mode;
        synchronized (TabSwipe.class) {
            if (frozen == null) {
                String read = null;
                try {
                    read = reader.get();
                } catch (Throwable ignored) {
                    // An unreadable setting is frozen as Default too, so a later read cannot change the built tabs.
                }
                frozen = read == null ? "default" : read;
            }
            return frozen;
        }
    }

    static boolean decide(String mode, boolean instagram) {
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) return false;
        return instagram;
    }
}
