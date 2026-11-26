/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import java.util.List;

import app.morphe.extension.instagram.utils.ExactTime;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/** Shows when the other person in a chat was last active as a local time instead of "Active 5m ago". */
@SuppressWarnings("unused")
public final class LastActive {
    private LastActive() {
    }

    /**
     * Called where the chat header reads Instagram's presence label, on the thread that builds the header.
     *
     * @param label     Instagram's label, null when it shows none.
     * @param presences the presence states of the chat's people, first one shown.
     */
    public static String chatHeaderStatus(String label, List<?> presences) {
        if (label == null || !Pref.exactLastActive()) return label;
        try {
            Object presence = presences == null || presences.isEmpty() ? null : presences.get(0);
            if (presence == null || presenceActive(presence, System.currentTimeMillis())) return label;
            long lastActive = lastActivityMs(presence);
            return lastActive > 0 ? str("spookd_last_active_at", ExactTime.format(lastActive, ExactTime.SHORT_DATE)) : label;
        } catch (Throwable t) {
            Logger.printException(() -> "Could not show the exact last active time", t);
            return label;
        }
    }

    // The patch replaces this body with Instagram's own "active now" check.
    public static boolean presenceActive(Object presence, long now) {
        return false;
    }

    // The patch replaces this body with a read of the presence's last activity time in milliseconds.
    public static long lastActivityMs(Object presence) {
        return 0L;
    }
}
