/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

/** Which account a received item belongs to, as far as reaction ownership is concerned. */
final class ReactionViewer {

    private ReactionViewer() {
    }

    /**
     * The viewer id taken when an item is queued: the item's own session, else for REST items the
     * session that is current then. Null when neither is known, never the stored fallback id.
     */
    static String atEnqueue(String itemSessionId, String currentSessionId, boolean restPath) {
        if (!isBlank(itemSessionId)) return itemSessionId;
        return restPath && !isBlank(currentSessionId) ? currentSessionId : null;
    }

    /** The viewer id reactions may be compared for, or null when ownership can't be established. */
    static String forComparison(boolean restPath, String enqueuedViewerId) {
        return restPath && !isBlank(enqueuedViewerId) ? enqueuedViewerId : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
