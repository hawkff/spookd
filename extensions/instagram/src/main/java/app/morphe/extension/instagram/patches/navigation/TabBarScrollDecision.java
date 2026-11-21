/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

/** Decides from the feed's scroll steps whether the tab bar should be out of the way. */
final class TabBarScrollDecision {
    private final int hideDistance;
    private final int showDistance;
    private boolean hidden;
    // Distance scrolled since the last change of direction; positive is down.
    private int distance;

    TabBarScrollDecision(int hideDistance, int showDistance) {
        this.hideDistance = hideDistance;
        this.showDistance = showDistance;
    }

    /** Takes one scroll step of dy pixels (positive is down) and returns whether the bar should be hidden. */
    boolean onScrolled(int dy, boolean atTop) {
        if (atTop) {
            reset();
            return false;
        }
        if (dy == 0) return hidden;
        if ((dy > 0) != (distance > 0)) distance = 0;
        distance += dy;
        if (distance > hideDistance) {
            hidden = true;
        } else if (-distance > showDistance) {
            hidden = false;
        }
        return hidden;
    }

    void reset() {
        hidden = false;
        distance = 0;
    }
}
