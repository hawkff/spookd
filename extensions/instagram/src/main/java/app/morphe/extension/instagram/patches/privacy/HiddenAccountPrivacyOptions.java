/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.privacy;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class HiddenAccountPrivacyOptions {
    // The server value that offers the follow list rows in Account privacy; nothing else in this app version reads it.
    static final String FOLLOW_LIST_ROWS_ELIGIBILITY = "is_uk_osa_enrolled_late_teen";

    private HiddenAccountPrivacyOptions() {
    }

    /**
     * Called on the coroutine that resolves a server value of an Instagram settings screen, before it is fetched.
     * Null keeps the server's answer.
     */
    public static Boolean serverValue(String name) {
        try {
            return serverValue(name, Pref.hiddenAccountPrivacyOptions());
        } catch (Throwable t) {
            Logger.printException(() -> "HiddenAccountPrivacyOptions.serverValue failed", t);
            return null;
        }
    }

    static Boolean serverValue(String name, boolean enabled) {
        return enabled && FOLLOW_LIST_ROWS_ELIGIBILITY.equals(name) ? Boolean.TRUE : null;
    }
}
