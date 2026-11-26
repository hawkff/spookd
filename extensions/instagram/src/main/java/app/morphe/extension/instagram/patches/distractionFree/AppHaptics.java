/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.distractionFree;

import android.view.View;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/** Stands in for every View.performHapticFeedback call Instagram makes. */
@SuppressWarnings("unused")
public final class AppHaptics {
    private AppHaptics() {
    }

    public static boolean performHapticFeedback(View view, int feedbackConstant) {
        return !disabled() && view.performHapticFeedback(feedbackConstant);
    }

    public static boolean performHapticFeedback(View view, int feedbackConstant, int flags) {
        return !disabled() && view.performHapticFeedback(feedbackConstant, flags);
    }

    private static boolean disabled() {
        try {
            return Pref.disableAppHaptics();
        } catch (Throwable t) {
            Logger.printException(() -> "disableAppHaptics failure", t);
            return false;
        }
    }
}
