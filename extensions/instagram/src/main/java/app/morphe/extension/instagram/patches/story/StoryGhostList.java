/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.content.DialogInterface;

import java.util.Map;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
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

    /** Whether a story of {@code ownerId} sends no automatic receipt; an unknown (null) owner never does. */
    static boolean staysAnonymous(String mode, String ownerId, Map<String, String> listed) {
        return ownerId == null || appliesTo(mode, listed.containsKey(ownerId));
    }

    /** Instagram queuing a consumed item for its receipt, on the main thread: owners the mode leaves out get it sent. */
    public static void onStoryConsumed(Object logger, Object item, Object reel) {
        try {
            if (!Pref.viewStoriesAnonymously()) return;
            String mode = SharedPref.getStringPref(Settings.STORY_GHOST_MODE);
            Object session = loggerSessionNative(logger);
            String ownerId = StoryUserList.ownerId(mediaNative(item), session);
            if (staysAnonymous(mode, ownerId, StoryUserList.GHOST.all())) return;
            markSeen(session, item, reel);
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

    // Shown in every mode so the list can be prepared before switching modes.
    public static boolean showMenuItem() {
        return SettingsStatus.viewStoriesAnonymously && Pref.viewStoriesAnonymously();
    }

    /** The story menu item, on the main thread. The menu left resuming the story to this action. */
    public static void addStoryOwner(UserSession session, Object media, DialogInterface.OnDismissListener onDismiss) {
        try {
            UserData owner = new MediaData(media, session).getUserData();
            String userId = owner.getUserId();
            String username = owner.getUsername();
            if (userId != null && username != null && !userId.equals(session.getUserId())) {
                Utils.showToastShort(str(StoryUserList.GHOST.add(userId, username)
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
        StoryUserList.GHOST.showList(context);
    }

    // The patch replaces these bodies with reads from Instagram's story objects.
    public static Object mediaNative(Object item) {
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
