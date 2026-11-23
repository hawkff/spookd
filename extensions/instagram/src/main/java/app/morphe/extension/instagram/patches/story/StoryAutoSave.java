/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.content.DialogInterface;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import com.instagram.common.session.UserSession;

/** Auto-save stories: saves viewed story items of everyone or of the listed owners. */
public final class StoryAutoSave {
    static final String MODE_LISTED = "listed";

    private StoryAutoSave() {
    }

    // Only the listed mode uses the list, so the menu stays short otherwise.
    public static boolean showMenuItem() {
        return SettingsStatus.storyAutoSave && Pref.enableDownload()
                && MODE_LISTED.equals(SharedPref.getStringPref(Settings.STORY_AUTO_SAVE));
    }

    /** The story menu item, on the main thread: lists the story owner, or unlists them when listed. */
    public static void toggleStoryOwner(UserSession session, Object media, DialogInterface.OnDismissListener onDismiss) {
        try {
            UserData owner = new MediaData(media, session).getUserData();
            String userId = owner.getUserId();
            String username = owner.getUsername();
            if (userId != null && username != null && !userId.equals(session.getUserId())) {
                boolean added = StoryUserList.AUTO_SAVE.add(userId, username);
                if (!added) StoryUserList.AUTO_SAVE.remove(userId);
                Utils.showToastShort(str(added ? "spookd_story_auto_save_added" : "spookd_story_auto_save_removed",
                        username));
            }
        } catch (Exception e) {
            Logger.printException(() -> "Failed to change the story auto-save list", e);
        } finally {
            if (onDismiss != null) onDismiss.onDismiss(null);
        }
    }

    /** The list screen in the Story settings. */
    public static void showList(Context context) {
        StoryUserList.AUTO_SAVE.showList(context);
    }
}
