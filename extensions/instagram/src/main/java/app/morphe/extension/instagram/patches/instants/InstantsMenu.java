/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.AlertDialog;
import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.patches.story.StoryUserList;
import app.morphe.extension.instagram.settings.SafeStartup;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;

/**
 * Quick Instants toggles and the seen lists, from the Home long-press menu and the Instants settings.
 * Instagram's Instants viewer is drawn in Compose with no menu to add to. Everything runs on the main thread.
 */
public final class InstantsMenu {
    private InstantsMenu() {
    }

    /** Whether the Home long-press menu offers the Instants toggles; never while all features are off. */
    public static boolean available() {
        return !SafeStartup.featuresOff() && (SettingsStatus.instantsDownload || SettingsStatus.instantsSeen);
    }

    public static void show(Context context) {
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (SettingsStatus.instantsDownload) {
            boolean saving = SharedPref.getBooleanPref(Settings.INSTANTS_DOWNLOAD);
            items.add(str(saving ? "spookd_instants_menu_stop_saving" : "spookd_instants_menu_start_saving"));
            actions.add(() -> SharedPref.setBooleanPref(Settings.INSTANTS_DOWNLOAD.key, !saving));
        }
        if (SettingsStatus.instantsSeen) {
            boolean hidden = SharedPref.getBooleanPref(Settings.INSTANTS_DONT_MARK_SEEN);
            items.add(str(hidden ? "spookd_instants_menu_start_marking" : "spookd_instants_menu_stop_marking"));
            actions.add(() -> SharedPref.setBooleanPref(Settings.INSTANTS_DONT_MARK_SEEN.key, !hidden));
            items.add(str("spookd_instants_always_seen_users"));
            actions.add(() -> showUserList(context, true));
            items.add(str("spookd_instants_never_seen_users"));
            actions.add(() -> showUserList(context, false));
        }
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(str("spookd_instants_title"))
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton(str("spookd_close"), null)
                .show();
    }

    /**
     * The Always or Never mark seen list: its senders and those of this session's Instants, ticked when
     * listed. A sender is on at most one of the two lists.
     */
    public static void showUserList(Context context, boolean always) {
        StoryUserList list = always ? InstantsSeen.ALWAYS : InstantsSeen.NEVER;
        StoryUserList other = always ? InstantsSeen.NEVER : InstantsSeen.ALWAYS;
        Map<String, String> listed = list.all();
        Map<String, String> users = new LinkedHashMap<>(listed);
        for (Map.Entry<String, String> sender : InstantsSeen.recentSenders().entrySet()) {
            users.putIfAbsent(sender.getKey(), sender.getValue());
        }
        List<String> ids = new ArrayList<>(users.keySet());
        CharSequence[] names = new CharSequence[ids.size()];
        boolean[] ticked = new boolean[ids.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = "@" + users.get(ids.get(i));
            ticked[i] = listed.containsKey(ids.get(i));
        }

        AlertDialog.Builder dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(str(always ? "spookd_instants_always_seen_users" : "spookd_instants_never_seen_users"))
                .setNegativeButton(str("spookd_close"), null);
        if (ids.isEmpty()) {
            dialog.setMessage(str("spookd_instants_seen_users_empty"));
        } else {
            dialog.setMultiChoiceItems(names, ticked, (shown, which, isChecked) -> {
                String userId = ids.get(which);
                if (isChecked) {
                    other.remove(userId);
                    list.add(userId, users.get(userId));
                } else {
                    list.remove(userId);
                }
            });
        }
        dialog.show();
    }
}
