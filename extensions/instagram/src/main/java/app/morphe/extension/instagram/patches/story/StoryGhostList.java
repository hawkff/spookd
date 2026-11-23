/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import com.instagram.common.session.UserSession;

/** Who View stories anonymously applies to, and the seen receipts it lets through. */
public final class StoryGhostList {
    static final String MODE_EVERYONE = "everyone";
    static final String MODE_EXCEPT_LISTED = "except_listed";
    static final String MODE_ONLY_LISTED = "only_listed";

    private StoryGhostList() {
    }

    static boolean appliesTo(String mode, boolean listed) {
        if (MODE_EXCEPT_LISTED.equals(mode)) return !listed;
        if (MODE_ONLY_LISTED.equals(mode)) return listed;
        return true;
    }

    /** Instagram queuing a consumed item for its receipt, on the main thread: owners the mode leaves out get it sent. */
    public static void onStoryConsumed(Object logger, Object item, Object reel) {
        try {
            if (!Pref.viewStoriesAnonymously()) return;
            String mode = SharedPref.getStringPref(Settings.STORY_GHOST_MODE);
            if (appliesTo(mode, StoryGhostUsers.all().containsKey(ownerIdNative(reel)))) return;
            markSeen(loggerSessionNative(logger), item, reel);
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to apply the story ghost list", throwable);
        }
    }

    /** Start of Instagram's story like action, on the main thread. */
    public static void onStoryLiked(Object controller, Object state, Object reelModel, Object item, Object reel) {
        try {
            if (state instanceof Enum && "LIKED".equals(((Enum<?>) state).name()) && interactionMarksSeen()) {
                markSeen(controllerSessionNative(controller), item, reel);
            }
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to mark a liked story as seen", throwable);
        }
    }

    /** Start of the story viewer's sent-reply log, which also covers quick reactions. */
    public static void onStoryReplySent(Object logger, Object reel) {
        try {
            if (!interactionMarksSeen()) return;
            Object session = loggerSessionNative(logger);
            markSeen(session, currentItemNative(session, reel), reel);
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to mark a replied story as seen", throwable);
        }
    }

    private static boolean interactionMarksSeen() {
        return SharedPref.getBooleanPref(Settings.MARK_STORY_SEEN_ON_INTERACTION) && Pref.viewStoriesAnonymously();
    }

    // Already marked or pending stories are skipped; with the request registry full the receipt is dropped, which stays private.
    private static void markSeen(Object session, Object item, Object reel) {
        StorySeenBridge.markSeenAutomatically(session, item, reel, null);
    }

    public static boolean showMenuItem() {
        return SettingsStatus.viewStoriesAnonymously
                && Pref.viewStoriesAnonymously()
                && !MODE_EVERYONE.equals(SharedPref.getStringPref(Settings.STORY_GHOST_MODE));
    }

    /** The story menu item, on the main thread. The menu left resuming the story to this action. */
    public static void addStoryOwner(UserSession session, Object media, DialogInterface.OnDismissListener onDismiss) {
        try {
            UserData owner = new MediaData(media, session).getUserData();
            String userId = owner.getUserId();
            String username = owner.getUsername();
            if (userId != null && username != null && !userId.equals(session.getUserId())) {
                Utils.showToastShort(str(StoryGhostUsers.add(userId, username)
                        ? "spookd_story_ghost_added"
                        : "spookd_story_ghost_already_added", username));
            }
        } catch (Exception e) {
            Logger.printException(() -> "Failed to add the story owner to the story ghost list", e);
        } finally {
            if (onDismiss != null) onDismiss.onDismiss(null);
        }
    }

    /** The list screen in the Ghost settings: tap a username to remove it. */
    public static void showList(Context context) {
        Map<String, String> users = StoryGhostUsers.all();
        List<String> ids = new ArrayList<>(users.keySet());
        CharSequence[] names = new CharSequence[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = "@" + users.get(ids.get(i));

        AlertDialog.Builder dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(str("spookd_story_ghost_users"))
                .setNegativeButton(str("spookd_close"), null);
        if (ids.isEmpty()) {
            dialog.setMessage(str("spookd_story_ghost_users_empty"));
        } else {
            dialog.setItems(names, (d, which) -> confirmRemove(context, ids.get(which), names[which]));
        }
        dialog.show();
    }

    private static void confirmRemove(Context context, String userId, CharSequence name) {
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setMessage(str("spookd_story_ghost_remove_confirm", name))
                .setNegativeButton(str("spookd_cancel"), (d, which) -> showList(context))
                .setPositiveButton(str("spookd_story_ghost_remove"), (d, which) -> {
                    StoryGhostUsers.remove(userId);
                    showList(context);
                })
                .show();
    }

    // The patch replaces these bodies with reads from Instagram's story objects.
    public static String ownerIdNative(Object reel) {
        return null;
    }

    public static Object loggerSessionNative(Object logger) {
        return null;
    }

    public static Object controllerSessionNative(Object controller) {
        return null;
    }

    public static Object currentItemNative(Object session, Object reel) {
        return null;
    }
}
