/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.pip;

import android.os.Build;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/** Turns on Instagram's own picture-in-picture for full-screen Reels and feed videos. */
public final class VideoPip {
    // Instagram opens its video-only PiP view from PictureInPictureUiState.isTransitioningToPip, added in Android 15.
    static final int MIN_SDK = 35;

    // Read once per process: each Reels viewer sets up PiP when it is created.
    private static volatile Boolean switchOn;

    private VideoPip() {}

    /**
     * Called at the start of Instagram's Reels PiP availability check and of its stored PiP choice,
     * on the thread that asks (the main thread for the Reels viewer). True answers yes; false keeps
     * Instagram's own answer. {@code fromMenu} is the check's Boolean, true where the Reels menu
     * decides whether to show Instagram's own PiP row, which is left to Instagram.
     */
    public static boolean forceOn(boolean fromMenu) {
        try {
            return forced(fromMenu, switchOn(), Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to read the picture-in-picture switch", t);
            return false;
        }
    }

    static boolean forced(boolean fromMenu, boolean on, int sdk) {
        return !fromMenu && sdk >= MIN_SDK && on;
    }

    private static boolean switchOn() {
        Boolean value = switchOn;
        if (value == null) {
            value = Pref.pipForVideos();
            switchOn = value;
        }
        return value;
    }
}
