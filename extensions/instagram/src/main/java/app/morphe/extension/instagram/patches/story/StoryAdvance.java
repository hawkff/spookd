/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import java.lang.ref.WeakReference;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.BooleanSetting;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Moves the resumed story viewer to the next item through Instagram's own tap-forward path. */
public final class StoryAdvance {
    private static volatile WeakReference<Object> resumedViewer = new WeakReference<>(null);

    private StoryAdvance() {
    }

    /** Start of the story viewer's onResume, on the main thread. */
    public static void onViewerResumed(Object viewer) {
        resumedViewer = new WeakReference<>(viewer);
    }

    /** Start of the story viewer's onPause, on the main thread. */
    public static void onViewerPaused(Object viewer) {
        if (resumedViewer.get() == viewer) resumedViewer = new WeakReference<>(null);
    }

    static void afterMarkSeen() {
        advanceIf(Settings.ADVANCE_STORY_ON_MARK_SEEN);
    }

    static void afterLike() {
        advanceIf(Settings.ADVANCE_STORY_ON_LIKE);
    }

    static void afterReply() {
        advanceIf(Settings.ADVANCE_STORY_ON_REPLY);
    }

    private static void advanceIf(BooleanSetting trigger) {
        try {
            if (!SettingsStatus.advanceStory || !SharedPref.getBooleanPref(trigger)) return;
            Object viewer = resumedViewer.get();
            if (viewer == null) return;
            // Posted so Instagram finishes handling the tap, like or reply before the viewer moves on.
            Utils.runOnMainThread(() -> {
                try {
                    if (resumedViewer.get() == viewer) advanceNative(viewer, null);
                } catch (Throwable throwable) {
                    Logger.printException(() -> "Failed to advance the story", throwable);
                }
            });
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to advance the story", throwable);
        }
    }

    // The patch replaces this body with the viewer's tap-forward call.
    public static void advanceNative(Object viewer, Object scratch) {
    }
}
