/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;

/** Sends receipts for the owner's items before the one marked with the eye button. */
public final class StoryEarlierSeen {
    private StoryEarlierSeen() {
    }

    /** After the eye button marked {@code item}, on the main thread. */
    static void markBefore(Object session, Object item, Object reel, Object source) {
        try {
            if (!SettingsStatus.markEarlierStoriesSeen
                    || !SharedPref.getBooleanPref(Settings.MARK_EARLIER_STORIES_SEEN)) return;
            List<?> items = itemsNative(reel, session);
            if (items == null) return;
            // The bridge skips items already marked or pending, and drops receipts once its request limit is reached.
            for (Object earlier : itemsBefore(items, item)) {
                StorySeenBridge.markSeen(session, earlier, reel, source, null);
            }
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to mark earlier stories as seen", throwable);
        }
    }

    static List<?> itemsBefore(List<?> items, Object item) {
        int index = items.indexOf(item);
        // A copy, so marking cannot interfere with Instagram changing its live list.
        return index > 0 ? new ArrayList<Object>(items.subList(0, index)) : Collections.emptyList();
    }

    // The patch replaces this body with the reel's ordered item list.
    public static List<?> itemsNative(Object reel, Object session) {
        return null;
    }
}
