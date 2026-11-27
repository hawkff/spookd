/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.patches.CopiedLink;
import app.morphe.extension.instagram.patches.HomeLongPress;
import app.morphe.extension.instagram.patches.instants.InstantsMenu;
import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch.Tab;
import app.morphe.extension.instagram.settings.ActivityHook;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.StorageScreen;
import app.morphe.extension.instagram.settings.StringSetting;
import app.morphe.extension.instagram.settings.preference.fragments.FragmentHook;
import app.morphe.extension.instagram.settings.preference.widgets.NavigationSettingsAccessPolicy;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** What holding a bottom tab opens, chosen per tab in the Navigation bar screen. */
@SuppressWarnings("unused")
public final class TabShortcuts {
    public static final String DEFAULT = "default";
    public static final String MENU = "menu";
    public static final String SETTINGS = "settings";
    public static final String GALLERY = "gallery";
    public static final String DELETED_MESSAGES = "deleted_messages";
    public static final String DOWNLOAD_HISTORY = "download_history";
    public static final String PROFILE_ANALYZER = "profile_analyzer";
    public static final String STORAGE = "storage";
    public static final String INSTANTS = "instants";
    public static final String SAVED = "saved";
    public static final String COPIED_LINK = "copied_link";

    /** Every choice in list order: stored value, label string. */
    public static final String[][] CHOICES = {
            {DEFAULT, "spookd_tab_shortcut_default"},
            {MENU, "spookd_tab_shortcut_menu"},
            {SETTINGS, "spookd_title_settings"},
            {GALLERY, "spookd_private_gallery"},
            {DELETED_MESSAGES, "spookd_view_deleted_messages"},
            {DOWNLOAD_HISTORY, "spookd_download_history"},
            {PROFILE_ANALYZER, "spookd_profile_analyzer"},
            {STORAGE, "spookd_storage"},
            {INSTANTS, "spookd_instants_title"},
            {SAVED, "spookd_tab_shortcut_saved"},
            {COPIED_LINK, "spookd_tab_shortcut_copied_link"},
    };

    private static final String SAVED_POSTS_LINK = "instagram://saved";

    private TabShortcuts() {
    }

    public static StringSetting setting(Tab tab) {
        switch (tab) {
            case HOME:
                return Settings.TAB_SHORTCUT_HOME;
            case REELS:
                return Settings.TAB_SHORTCUT_REELS;
            case MESSAGES:
                return Settings.TAB_SHORTCUT_MESSAGES;
            case SEARCH:
                return Settings.TAB_SHORTCUT_SEARCH;
            case PROFILE:
                return Settings.TAB_SHORTCUT_PROFILE;
            case NOTIFICATIONS:
                return Settings.TAB_SHORTCUT_NOTIFICATIONS;
            default:
                return Settings.TAB_SHORTCUT_CREATE;
        }
    }

    /** The setting for a tab Instagram built, by its enum name; Home and Profile are hooked in their own handlers. */
    static StringSetting builtTabSetting(String name) {
        switch (name) {
            case "CLIPS":
                return Settings.TAB_SHORTCUT_REELS;
            case "DIRECT":
                return Settings.TAB_SHORTCUT_MESSAGES;
            case "SEARCH":
                return Settings.TAB_SHORTCUT_SEARCH;
            case "NEWS":
                return Settings.TAB_SHORTCUT_NOTIFICATIONS;
            case "SHARE":
            case "CREATION":
                return Settings.TAB_SHORTCUT_CREATE;
            default:
                return null;
        }
    }

    /** The values, or the labels, of the choices this build can open; main thread. */
    public static CharSequence[] choices(boolean labels) {
        List<CharSequence> result = new ArrayList<>();
        for (String[] choice : CHOICES) {
            if (available(choice[0])) result.add(labels ? str(choice[1]) : choice[0]);
        }
        return result.toArray(new CharSequence[0]);
    }

    private static boolean available(String choice) {
        switch (choice) {
            case GALLERY:
            case DOWNLOAD_HISTORY:
                return SettingsStatus.downloadSection();
            case DELETED_MESSAGES:
                return SettingsStatus.saveDeletedMessages;
            case PROFILE_ANALYZER:
                return SettingsStatus.profileAnalyzer;
            case INSTANTS:
                return SettingsStatus.instantsDownload || SettingsStatus.instantsSeen;
            default:
                for (String[] known : CHOICES) {
                    if (known[0].equals(choice)) return true;
                }
                return false;
        }
    }

    /** The stored choice, or the tab's default when this build cannot open it; the default while Disable all is on. */
    private static String choice(StringSetting setting) {
        String choice = Pref.value(setting);
        return choice != null && available(choice) ? choice : setting.defaultValue;
    }

    public static boolean opensSettings(String choice) {
        return MENU.equals(choice) || SETTINGS.equals(choice);
    }

    /** Main thread. What holding Home opens. */
    public static String homeChoice() {
        String choice = choice(Settings.TAB_SHORTCUT_HOME);
        return opensSettings(choice) ? choice
                : homeChoice(choice, NavigationSettingsAccessPolicy.reachableWithoutHomeLongPress());
    }

    /** Home keeps the menu while no other way into settings is left. */
    public static String homeChoice(String stored, boolean reachableElsewhere) {
        return opensSettings(stored) || reachableElsewhere ? stored : MENU;
    }

    /** Main thread. Whether holding Home is a way into settings. */
    public static boolean homeOpensSettings() {
        return SettingsStatus.homeLongPressSettings && opensSettings(homeChoice());
    }

    /** Called on the main thread at the end of Instagram's tab button builder. */
    public static void onTabCreated(View view, Object tab) {
        try {
            StringSetting setting = tab instanceof Enum<?> ? builtTabSetting(((Enum<?>) tab).name()) : null;
            // Instagram's own listener stays for Default; the setting asks for a restart when it changes.
            if (setting == null || DEFAULT.equals(choice(setting))) return;
            view.setOnLongClickListener(held -> onHold(held, setting));
        } catch (Throwable t) {
            logFailure("Tab shortcut setup failed", t);
        }
    }

    private static boolean onHold(View view, StringSetting setting) {
        try {
            return open(view, choice(setting), false);
        } catch (Throwable t) {
            logFailure("Tab shortcut failed", t);
            return false;
        }
    }

    /**
     * Called on the main thread at the start of the Profile tab's hold gesture.
     *
     * @return true to skip Instagram's account switcher.
     */
    public static boolean onProfileLongPress(View view) {
        try {
            if (!open(view, choice(Settings.TAB_SHORTCUT_PROFILE), false)) return false;
            // A gesture, not a long click, so the view gives no feedback of its own.
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        } catch (Throwable t) {
            logFailure("Profile tab shortcut failed", t);
            return false;
        }
    }

    /**
     * Main thread. Opens {@code choice}; {@code home} adds Developer options to the menu.
     *
     * @return false to leave the hold to Instagram.
     */
    public static boolean open(View view, String choice, boolean home) {
        Context context = view.getContext();
        switch (choice) {
            case MENU:
                HomeLongPress.showMenu(view, home);
                return true;
            case SETTINGS:
                FragmentHook.startSettings();
                return true;
            case GALLERY:
                // The gallery screen asks for the screen lock itself before showing anything.
                ActivityHook.startPrivateGalleryActivity(context);
                return true;
            case DELETED_MESSAGES:
                ActivityHook.startDeletedMessagesActivity(context);
                return true;
            case DOWNLOAD_HISTORY:
                ActivityHook.startDownloadHistoryActivity(context);
                return true;
            case PROFILE_ANALYZER:
                ActivityHook.startProfileAnalyzerActivity(context);
                return true;
            case STORAGE:
                StorageScreen.show(HomeLongPress.activityOf(context));
                return true;
            case INSTANTS:
                InstantsMenu.show(HomeLongPress.activityOf(context));
                return true;
            case SAVED:
                PikoUtils.openUrl(SAVED_POSTS_LINK, true);
                return true;
            case COPIED_LINK:
                if (!CopiedLink.onSearchLongClick(view)) Utils.showToastShort(str("spookd_tab_shortcut_no_link"));
                return true;
            default:
                return false;
        }
    }

    private static void logFailure(String message, Throwable t) {
        try {
            Logger.printException(() -> message, t);
        } catch (Throwable ignored) {
            // Logging must never throw into Instagram's tab handling.
        }
    }
}
