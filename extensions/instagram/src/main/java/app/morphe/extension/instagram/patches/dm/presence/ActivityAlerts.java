/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm.presence;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.patches.dm.HiddenChatsPref;
import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.patches.story.StoryUserList;
import app.morphe.extension.instagram.settings.AccountSettings;
import app.morphe.extension.instagram.settings.BooleanSetting;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import com.instagram.common.session.UserSession;
import com.instagram.model.direct.DirectThreadKey;

/**
 * Local notifications, while Instagram runs, when people chosen from their chat's menu in the inbox come online, go
 * offline, type to you or read your message. Only what Instagram already receives is read; nothing is sent. Each
 * account has its own list, keyed by {@link #entry}.
 */
@SuppressWarnings("unused")
public final class ActivityAlerts {
    static final String CHANNEL_ID = "spookd_activity";
    // A presence toggle this soon after the last one is ignored; typing and reads notify once per person in it.
    static final long QUIET_MS = 2 * 60 * 1000L;
    // A receipt read longer ago than this arrived late, synced after the app was away.
    static final long STALE_READ_MS = 5 * 60 * 1000L;
    // Pressing these in the chat menu does nothing in Instagram, and no other entry borrows them.
    static final String[] MENU_BUTTONS = {"LAUNCH_INTEROP_UPGRADE_FLOW", "MUTE_NOTIFICATIONS"};
    static final Tracker TRACKER = new Tracker();
    // The button added to the menu opened last; menus open and are pressed on the main thread.
    private static String menuButton;
    private static String menuEntry;
    private static String menuTitle;
    private static boolean menuButtonAdds;
    private static boolean labelPending;

    private ActivityAlerts() { }

    /** What is known about each listed person in this process; called from Instagram's realtime and presence threads. */
    static final class Tracker {
        private final Map<String, Boolean> active = new HashMap<>();
        private final Map<String, Long> changedAt = new HashMap<>();
        private final Map<String, Long> notifiedAt = new HashMap<>();

        /**
         * Whether the person's presence changed in a way worth a notification. {@code nowActive} is null when Instagram
         * has no presence for them, which only ends a known active state. The first state known is not a change.
         */
        synchronized boolean changed(String person, Boolean nowActive, long now) {
            Boolean before = active.get(person);
            if (nowActive == null) {
                if (!Boolean.TRUE.equals(before)) return false;
                nowActive = false;
            }
            active.put(person, nowActive);
            if (before == null || before.equals(nowActive)) return false;
            Long last = changedAt.put(person, now);
            return last == null || now - last >= QUIET_MS;
        }

        /** Drops what is known about the people whose keys start with {@code prefix}, so their next state is a baseline. */
        synchronized void forget(String prefix) {
            active.keySet().removeIf(person -> person.startsWith(prefix));
            changedAt.keySet().removeIf(person -> person.startsWith(prefix));
        }

        /** Whether a typing or read notification for {@code key} is due: at most one per {@link #QUIET_MS}. */
        synchronized boolean due(String key, long now) {
            Long last = notifiedAt.get(key);
            if (last != null && now - last < QUIET_MS) return false;
            notifiedAt.put(key, now);
            return true;
        }
    }

    /**
     * Start of Instagram's presence store notifying its listeners, on the thread that updated it and under the store's
     * lock: the presence states of everyone Instagram knows, by user id. Every update passes the store's own map, an
     * empty one included; the session ending passes a different, empty map.
     */
    @SuppressWarnings("rawtypes")
    public static void onPresence(Object session, Map presences, Map storeMap) {
        try {
            if (presences == null || !(session instanceof UserSession)) return;
            String account = nonEmpty(((UserSession) session).getUserId());
            if (account == null) return;
            if (presences != storeMap) {
                TRACKER.forget(account + ":");
                return;
            }
            boolean online = Pref.activityAlert(Settings.ACTIVITY_ALERT_ONLINE);
            boolean offline = Pref.activityAlert(Settings.ACTIVITY_ALERT_OFFLINE);
            if (!online && !offline) return;
            long now = SystemClock.elapsedRealtime();
            for (Map.Entry<String, String> listed : StoryUserList.ACTIVITY_ALERTS.all(account).entrySet()) {
                String user = userOf(listed.getKey());
                if (user == null) continue;
                Object presence = presences.get(user);
                Boolean active = presence == null ? null : presenceActive(presence);
                if (!TRACKER.changed(account + ":" + user, active, now)) continue;
                boolean cameOnline = Boolean.TRUE.equals(active);
                if (cameOnline ? online : offline) {
                    post(account, user, chatOf(listed.getKey()), listed.getValue(),
                            str(cameOnline ? "spookd_activity_came_online" : "spookd_activity_went_offline"),
                            cameOnline ? Settings.ACTIVITY_ALERT_ONLINE : Settings.ACTIVITY_ALERT_OFFLINE);
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "ActivityAlerts.onPresence failed", t);
        }
    }

    /** Start of Instagram's typing indicator update, on its realtime or timer thread; {@code activity} is 0 when typing stopped. */
    public static void onTyping(Object chat, Object sender, int activity) {
        try {
            if (activity == 0 || !Pref.activityAlert(Settings.ACTIVITY_ALERT_TYPING)) return;
            notifyListed(pairId(sender), pairId(chat), Settings.ACTIVITY_ALERT_TYPING, "spookd_activity_typing");
        } catch (Throwable t) {
            Logger.printException(() -> "ActivityAlerts.onTyping failed", t);
        }
    }

    /** End of Instagram's read receipt constructor, on the thread parsing it: who read, when (ms, as text), and the chat. */
    public static void onReadReceipt(String reader, String readAtMs, String chat) {
        try {
            if (!Pref.activityAlert(Settings.ACTIVITY_ALERT_READ) || !recent(readAtMs, System.currentTimeMillis())) return;
            notifyListed(reader, chat, Settings.ACTIVITY_ALERT_READ, "spookd_activity_read");
        } catch (Throwable t) {
            Logger.printException(() -> "ActivityAlerts.onReadReceipt failed", t);
        }
    }

    /** Instagram's wait before its next active status refresh, on the main or its presence thread; the chosen one instead. */
    public static long presenceRefreshMs(long instagramMs) {
        try {
            return refreshMs(Pref.presenceRefreshMinutes(), instagramMs);
        } catch (Throwable t) {
            Logger.printException(() -> "ActivityAlerts.presenceRefreshMs failed", t);
            return instagramMs;
        }
    }

    // Only the offered choices count; anything else, such as an imported value, keeps Instagram's wait.
    static long refreshMs(String minutes, long instagramMs) {
        if ("1".equals(minutes) || "5".equals(minutes) || "15".equals(minutes)) {
            return Long.parseLong(minutes) * 60 * 1000L;
        }
        return instagramMs;
    }

    static boolean recent(String readAtMs, long now) {
        if (readAtMs == null) return false;
        try {
            return Math.abs(now - Long.parseLong(readAtMs)) <= STALE_READ_MS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // The patch replaces this body with a read of whether Instagram's presence state says the person is active.
    public static boolean presenceActive(Object presence) {
        return false;
    }

    // The patch replaces this body with a read of the user or chat id that Instagram's id pair holds.
    public static String pairId(Object pair) {
        return null;
    }

    private static void notifyListed(String user, String chat, BooleanSetting kind, String textKey) {
        String account = currentAccount();
        if (account == null || user == null || chat == null || user.equals(account)) return;
        String name = StoryUserList.ACTIVITY_ALERTS.all(account).get(entry(user, chat));
        if (name == null || !TRACKER.due(account + ":" + user + ":" + kind.key, SystemClock.elapsedRealtime())) return;
        post(account, user, chat, name, str(textKey), kind);
    }

    /**
     * One notification per person, replaced by their next one; tapping it opens the chat. The chat link opens with the
     * current account, so only that account's notifications are posted and kept: {@link #onCurrentSession} removes the rest.
     */
    private static void post(String account, String user, String chat, String name, String text, BooleanSetting kind) {
        Utils.runOnMainThread(() -> {
            // Checked again as it is posted, so turning the kind off or removing the person in between keeps it out.
            if (!account.equals(currentAccount()) || !stillWanted(kind, account, user, chat)
                    || hiddenAndMuted(account, chat)) {
                return;
            }
            Context context = Utils.getContext();
            NotificationManager manager = context == null ? null
                    : (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, str("spookd_activity_channel"),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(str("spookd_activity_channel_desc"));
            manager.createNotificationChannel(channel);

            Intent open = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("instagram://direct-thread?thread_id=" + Uri.encode(chat)))
                    .setPackage(context.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent tap = PendingIntent.getActivity(context, tag(account, user).hashCode(), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            int icon = context.getApplicationInfo().icon;
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_dialog_info)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setContentTitle(name == null || name.isEmpty() ? user : name)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .build();
            manager.notify(tag(account, user), 0, InboxLock.hideNotification(notification));
        });
    }

    /** Called on the main thread when a chat's menu opens, with the title it shows; adds the entry for one-to-one chats. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List menuOpening(List buttons, Object threadKey, String title) {
        menuButton = null;
        labelPending = false;
        try {
            if (!anyAlertOn() || buttons == null || buttons.isEmpty() || !(threadKey instanceof DirectThreadKey)) {
                return buttons;
            }
            DirectThreadKey key = (DirectThreadKey) threadKey;
            String account = currentAccount();
            String user = otherUser(key.A02, account);
            if (account == null || user == null || key.A00 == null) return buttons;

            Set<String> present = new HashSet<>();
            for (Object button : buttons) present.add(String.valueOf(button));
            String candidate = freeButton(present);
            if (candidate == null) return buttons;
            Class<? extends Enum> type = ((Enum) buttons.get(0)).getDeclaringClass();
            List extended = new ArrayList(buttons);
            extended.add(Enum.valueOf(type, candidate));
            menuButton = candidate;
            menuEntry = entry(user, key.A00);
            menuTitle = title == null || title.isEmpty() ? user : title;
            menuButtonAdds = !StoryUserList.ACTIVITY_ALERTS.all(account).containsKey(menuEntry);
            labelPending = true;
            return extended;
        } catch (Throwable t) {
            Logger.printException(() -> "ActivityAlerts.menuOpening failed", t);
        }
        return buttons;
    }

    /** Called on the main thread with the labels Instagram made for the menu's buttons; names the added one. */
    @SuppressWarnings("rawtypes")
    public static String[] menuLabels(List buttons, String[] labels) {
        try {
            if (!labelPending || menuButton == null || buttons == null || labels == null) return labels;
            labelPending = false;
            for (int i = 0; i < buttons.size() && i < labels.length; i++) {
                if (menuButton.equals(String.valueOf(buttons.get(i)))) {
                    labels[i] = str(menuButtonAdds ? "spookd_activity_alert_add" : "spookd_activity_alert_remove");
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "ActivityAlerts.menuLabels failed", t);
        }
        return labels;
    }

    /** Called on the main thread when a chat menu button is pressed; true when it was the added one, so nothing else handles it. */
    public static boolean onMenuButton(Object session, Object button) {
        try {
            if (menuButton == null || !menuButton.equals(String.valueOf(button))) return false;
            String account = session instanceof UserSession ? nonEmpty(((UserSession) session).getUserId()) : null;
            if (account == null || menuEntry == null) return false;
            menuButton = null;
            if (menuButtonAdds) {
                StoryUserList.ACTIVITY_ALERTS.add(account, menuEntry, menuTitle);
            } else {
                StoryUserList.ACTIVITY_ALERTS.remove(account, menuEntry);
            }
            PikoUtils.toast(str(menuButtonAdds ? "spookd_activity_alert_added" : "spookd_activity_alert_removed"));
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "ActivityAlerts.onMenuButton failed", t);
            return false;
        }
    }

    /** The list screen in the DM settings, for the account whose settings are shown: tap a person to remove them. */
    public static void showList(Context context) {
        StoryUserList.ACTIVITY_ALERTS.showList(context, SharedPref.getStringPref(AccountSettings.OWNER_KEY, ""));
    }

    /** Called when Instagram sets its current session, on that thread; removes the other accounts' notifications. */
    public static void onCurrentSession(UserSession session) {
        try {
            String account = session == null ? null : nonEmpty(session.getUserId());
            if (account == null) return;
            Utils.runOnMainThread(() -> {
                Context context = Utils.getContext();
                NotificationManager manager = context == null ? null
                        : (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager == null) return;
                for (StatusBarNotification shown : manager.getActiveNotifications()) {
                    String tag = shown.getTag();
                    if (isOtherAccountsTag(tag, account)) manager.cancel(tag, shown.getId());
                }
            });
        } catch (Throwable t) {
            Logger.printException(() -> "ActivityAlerts.onCurrentSession failed", t);
        }
    }

    private static boolean stillWanted(BooleanSetting kind, String account, String user, String chat) {
        return Pref.activityAlert(kind) && StoryUserList.ACTIVITY_ALERTS.all(account).containsKey(entry(user, chat));
    }

    // Checked as the notification is posted, so hiding the chat in between still keeps it out.
    private static boolean hiddenAndMuted(String account, String chat) {
        return Pref.hiddenChats() && Pref.hiddenChatsMute() && HiddenChatsPref.ids(account).contains(chat);
    }

    static String tag(String account, String user) {
        return CHANNEL_ID + ":" + account + ":" + user;
    }

    static boolean isOtherAccountsTag(String tag, String account) {
        return tag != null && tag.startsWith(CHANNEL_ID + ":") && !tag.startsWith(CHANNEL_ID + ":" + account + ":");
    }

    private static String currentAccount() {
        return nonEmpty(SharedPref.getStringPref(AccountSettings.OWNER_KEY, ""));
    }

    private static boolean anyAlertOn() {
        return Pref.activityAlert(Settings.ACTIVITY_ALERT_ONLINE) || Pref.activityAlert(Settings.ACTIVITY_ALERT_OFFLINE)
                || Pref.activityAlert(Settings.ACTIVITY_ALERT_TYPING) || Pref.activityAlert(Settings.ACTIVITY_ALERT_READ);
    }

    static String entry(String user, String chat) {
        return user + ":" + chat;
    }

    static String userOf(String entry) {
        int split = entry.indexOf(':');
        return split > 0 ? entry.substring(0, split) : null;
    }

    static String chatOf(String entry) {
        return entry.substring(entry.indexOf(':') + 1);
    }

    /** The first button the menu does not list yet, or null when all are taken. */
    static String freeButton(Set<String> present) {
        for (String candidate : MENU_BUTTONS) {
            if (!present.contains(candidate)) return candidate;
        }
        return null;
    }

    /** The one person in a chat besides the account, or null for a group or an unknown account. */
    static String otherUser(List<?> people, String account) {
        if (people == null || account == null) return null;
        String other = null;
        for (Object person : people) {
            if (person == null || account.equals(String.valueOf(person))) continue;
            if (other != null) return null;
            other = String.valueOf(person);
        }
        return other;
    }

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
