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
     * The viewer id reactions may be compared for, from the item's own session, or null when
     * ownership can't be established. REST items carry no session of their own.
     */
    static String forComparison(boolean restPath, String itemSessionViewerId) {
        return !restPath && !isBlank(itemSessionViewerId) ? itemSessionViewerId : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
