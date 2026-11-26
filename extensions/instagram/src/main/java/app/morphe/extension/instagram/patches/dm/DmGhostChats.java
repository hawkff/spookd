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
import app.morphe.extension.instagram.patches.story.StoryUserList;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import com.instagram.model.direct.DirectThreadKey;

/**
 * The chats View DMs anonymously covers: all, all but the chosen ones, or only the chosen ones. Chats are chosen
 * from their long-press menu in the inbox and kept in the settings file, so each account has its own list.
 */
@SuppressWarnings("unused")
public final class DmGhostChats {
    static final String MODE_EVERYONE = "everyone";
    static final String MODE_EXCEPT_LISTED = "except_listed";
    static final String MODE_ONLY_LISTED = "only_listed";
    // The chat menu can only list buttons Instagram already has; if a press were not caught, these only open details or a replay.
    private static final String[] MENU_BUTTONS = {"DETAILS", "REPLAY_VISUAL_MESSAGE"};
    // The button added to the menu opened last; menus open and are pressed on the main thread.
    private static String menuButton;
    private static String menuTitle;
    private static boolean menuButtonAdds;
    private static boolean labelPending;

    private DmGhostChats() { }

    /** Whether the mode keeps the seen receipt of a chat back; an unknown (null) chat always stays anonymous. */
    public static boolean appliesTo(String mode, String threadId) {
        if (threadId == null || !(MODE_EXCEPT_LISTED.equals(mode) || MODE_ONLY_LISTED.equals(mode))) return true;
        return appliesTo(mode, threadId, StoryUserList.DM_GHOST.all());
    }

    static boolean appliesTo(String mode, String threadId, Map<String, String> listed) {
        if (threadId == null) return true;
        if (MODE_EXCEPT_LISTED.equals(mode)) return !listed.containsKey(threadId);
        if (MODE_ONLY_LISTED.equals(mode)) return listed.containsKey(threadId);
        return true;
    }

    /** Called on the main thread when a chat's menu opens, with the title it shows; returns the buttons with the entry added. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List menuOpening(List buttons, Object threadKey, String title) {
        menuButton = null;
        labelPending = false;
        try {
            if (!SettingsStatus.dmGhostChats || !Pref.viewDmAnonymously() || buttons == null || buttons.isEmpty()
                    || !(threadKey instanceof DirectThreadKey)) {
                return buttons;
            }
            String chat = ((DirectThreadKey) threadKey).A00;
            if (chat == null) return buttons;

            // Only a button the menu does not list yet is used, so Pin recipients and Instagram keep theirs.
            Set<String> present = new HashSet<>();
            for (Object button : buttons) present.add(String.valueOf(button));
            Class<? extends Enum> type = ((Enum) buttons.get(0)).getDeclaringClass();
            for (String candidate : MENU_BUTTONS) {
                if (present.contains(candidate)) continue;
                List extended = new ArrayList(buttons);
                extended.add(Enum.valueOf(type, candidate));
                menuButton = candidate;
                menuTitle = title;
                menuButtonAdds = !StoryUserList.DM_GHOST.all().containsKey(chat);
                labelPending = true;
                return extended;
            }
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
    public static boolean onMenuButton(Object button, Object threadKey) {
        try {
            if (menuButton == null || !menuButton.equals(String.valueOf(button))) return false;
            if (!(threadKey instanceof DirectThreadKey)) return false;
            String chat = ((DirectThreadKey) threadKey).A00;
            if (chat == null) return false;
            menuButton = null;
            if (menuButtonAdds) {
                StoryUserList.DM_GHOST.add(chat, menuTitle == null || menuTitle.isEmpty() ? chat : menuTitle);
            } else {
                StoryUserList.DM_GHOST.remove(chat);
            }
            PikoUtils.toast(str(menuButtonAdds ? "spookd_dm_ghost_chosen" : "spookd_dm_ghost_unchosen"));
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "DmGhostChats.onMenuButton failed", t);
            return false;
        }
    }

    /** The list screen in the Ghost settings: tap a chat to remove it. */
    public static void showList(Context context) {
        StoryUserList.DM_GHOST.showList(context);
    }
}
