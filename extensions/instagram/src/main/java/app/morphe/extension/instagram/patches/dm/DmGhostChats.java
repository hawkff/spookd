/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.patches.story.StoryUserList;
import app.morphe.extension.instagram.settings.AccountSettings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import com.instagram.common.session.UserSession;
import com.instagram.model.direct.DirectThreadKey;

/**
 * The chats View DMs anonymously covers: all, all but the chosen ones, or only the chosen ones. Chats are chosen
 * from their long-press menu in the inbox and kept under the account id, so each account has its own list.
 */
@SuppressWarnings("unused")
public final class DmGhostChats {
    static final String MODE_EVERYONE = "everyone";
    static final String MODE_EXCEPT_LISTED = "except_listed";
    static final String MODE_ONLY_LISTED = "only_listed";
    /**
     * The chat menu can only list buttons Instagram already has. The debug entry comes first because Pin recipients
     * borrows the other two, so both entries fit next to a native one; if a press were not caught, these only open a
     * debug note, details or a replay.
     */
    static final String[] MENU_BUTTONS = {"THREAD_LEVEL_DEBUG", "DETAILS", "REPLAY_VISUAL_MESSAGE"};
    // The button added to the menu opened last; menus open and are pressed on the main thread.
    private static String menuButton;
    private static String menuTitle;
    private static boolean menuButtonAdds;
    private static boolean labelPending;

    private DmGhostChats() { }

    /** Start of Instagram's DM seen request, on its thread: true keeps the receipt back. */
    public static boolean blocksSeen(UserSession session, Object request, String threadId) {
        try {
            return Pref.viewDmAnonymously(accountOf(session), threadId);
        } catch (Throwable t) {
            Logger.printException(() -> "DmGhostChats.blocksSeen failed", t);
            return Pref.viewDmAnonymously();
        }
    }

    /** Whether the mode keeps the seen receipt of a chat back; an unknown account or chat always stays anonymous. */
    public static boolean appliesTo(String mode, String account, String threadId) {
        if (threadId == null || account == null || account.isEmpty()
                || !(MODE_EXCEPT_LISTED.equals(mode) || MODE_ONLY_LISTED.equals(mode))) {
            return true;
        }
        return appliesTo(mode, threadId, StoryUserList.DM_GHOST.all(account));
    }

    static boolean appliesTo(String mode, String threadId, Map<String, String> listed) {
        if (threadId == null) return true;
        if (MODE_EXCEPT_LISTED.equals(mode)) return !listed.containsKey(threadId);
        if (MODE_ONLY_LISTED.equals(mode)) return listed.containsKey(threadId);
        return true;
    }

    /** The first button the menu does not list yet, or null when all are taken. */
    static String freeButton(Set<String> present) {
        for (String candidate : MENU_BUTTONS) {
            if (!present.contains(candidate)) return candidate;
        }
        return null;
    }

    /** Whether {@code button} is the one this added to the menu opened last, so other handlers leave its press alone. */
    public static boolean ownsMenuButton(String button) {
        return menuButton != null && menuButton.equals(button);
    }

    /** Called on the main thread when a chat's menu opens, with the title it shows; returns the buttons with the entry added. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List menuOpening(List buttons, Object menu, Object threadKey, String title) {
        menuButton = null;
        labelPending = false;
        try {
            if (!SettingsStatus.dmGhostChats || !Pref.viewDmAnonymously() || buttons == null || buttons.isEmpty()
                    || menu == null || !(threadKey instanceof DirectThreadKey)) {
                return buttons;
            }
            String chat = ((DirectThreadKey) threadKey).A00;
            String account = accountOf(HiddenChats.sessionOf(menu));
            if (chat == null || account == null) return buttons;

            // Only a button the menu does not list yet is used, so Pin recipients and Instagram keep theirs.
            Set<String> present = new HashSet<>();
            for (Object button : buttons) present.add(String.valueOf(button));
            String candidate = freeButton(present);
            if (candidate == null) return buttons;
            Class<? extends Enum> type = ((Enum) buttons.get(0)).getDeclaringClass();
            List extended = new ArrayList(buttons);
            extended.add(Enum.valueOf(type, candidate));
            menuButton = candidate;
            menuTitle = title;
            menuButtonAdds = !StoryUserList.DM_GHOST.all(account).containsKey(chat);
            labelPending = true;
            return extended;
        } catch (Throwable t) {
            Logger.printException(() -> "DmGhostChats.menuOpening failed", t);
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
                    labels[i] = str(menuButtonAdds ? "spookd_dm_ghost_choose" : "spookd_dm_ghost_unchoose");
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "DmGhostChats.menuLabels failed", t);
        }
        return labels;
    }

    /** Called on the main thread when a chat menu button is pressed; true when it was the added one, so nothing else handles it. */
    public static boolean onMenuButton(Object session, Object button, Object threadKey) {
        try {
            if (!ownsMenuButton(String.valueOf(button))) return false;
            String account = accountOf(session);
            if (account == null || !(threadKey instanceof DirectThreadKey)) return false;
            String chat = ((DirectThreadKey) threadKey).A00;
            if (chat == null) return false;
            menuButton = null;
            if (menuButtonAdds) {
                StoryUserList.DM_GHOST.add(account, chat, menuTitle == null || menuTitle.isEmpty() ? chat : menuTitle);
            } else {
                StoryUserList.DM_GHOST.remove(account, chat);
            }
            PikoUtils.toast(str(menuButtonAdds ? "spookd_dm_ghost_chosen" : "spookd_dm_ghost_unchosen"));
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "DmGhostChats.onMenuButton failed", t);
            return false;
        }
    }

    /** The list screen in the Ghost settings, for the account whose settings are shown: tap a chat to remove it. */
    public static void showList(Context context) {
        StoryUserList.DM_GHOST.showList(context, SharedPref.getStringPref(AccountSettings.OWNER_KEY, null));
    }

    private static String accountOf(Object session) {
        if (!(session instanceof UserSession)) return null;
        String account = ((UserSession) session).getUserId();
        return account == null || account.isEmpty() ? null : account;
    }
}
