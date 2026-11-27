/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import app.morphe.extension.shared.Logger;

import com.instagram.common.session.UserSession;

/** Keep seen locally: a chat whose read receipt DM ghost keeps back is still marked read on this device. */
@SuppressWarnings("unused")
public final class KeepSeenLocally {
    // Written at the start of a seen request and read at its queue call, both on the request's thread.
    private static final ThreadLocal<Boolean> LOCAL = new ThreadLocal<>();

    private KeepSeenLocally() {
    }

    /** Whether a seen request ends early; one kept local runs on to build its receipt, which is then not queued. */
    static boolean blocksRequest(boolean blocked, boolean keepLocally) {
        boolean local = blocked && keepLocally;
        LOCAL.set(local);
        return blocked && !local;
    }

    /** Called right before the seen request queues its receipt: true drops the receipt after marking the chat read here. */
    public static boolean keptLocally(UserSession session, Object receipt) {
        if (!takeLocal()) return false;
        try {
            markSeen(session, receipt, "queued");
        } catch (Throwable t) {
            Logger.printException(() -> "KeepSeenLocally.keptLocally failed", t);
        }
        return true;
    }

    static boolean takeLocal() {
        if (!Boolean.TRUE.equals(LOCAL.get())) return false;
        LOCAL.remove();
        return true;
    }

    // The patch replaces this body with Instagram's handling of a queued receipt, which only updates the chat on this device.
    private static void markSeen(UserSession session, Object receipt, String state) {
    }
}
