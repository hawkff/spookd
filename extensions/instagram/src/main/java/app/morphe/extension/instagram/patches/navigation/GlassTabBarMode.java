/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

/** Decides how the glass tab bar is drawn for one frame. */
final class GlassTabBarMode {
    /** Instagram's own pill is drawn. */
    static final int OFF = 0;
    /** A more opaque tint, rim and shadow, without blur. */
    static final int OPAQUE = 1;
    /** The blurred content behind the bar under a translucent tint, rim and shadow. */
    static final int BLUR = 2;

    private GlassTabBarMode() {
    }

    static int choose(boolean enabled, int sdk, boolean hardwareCanvas, boolean blurFailed, boolean barAway) {
        if (!enabled) return OFF;
        if (sdk < 31 || !hardwareCanvas || blurFailed || barAway) return OPAQUE;
        return BLUR;
    }
}
