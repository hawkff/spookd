/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.instagram.common.session.UserSession;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.morphe.extension.crimera.settings.Setting;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.patches.focusLock.FocusLock;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.InstaUtils;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Keeps settings per account: the live file holds the current account's values, and each other
 * account's per-account values are parked in {@code piko_settings_<userId>}.
 */
public final class AccountSettings {

    public static final String OWNER_KEY = "piko_account_owner";
    private static final String ACCOUNTS_PREF_NAME = Constants.SHARED_PREF_NAME + "_accounts";
    private static final String ADOPT_LIVE_FILE_KEY = "adopt_live_file";

    /** Device-level keys the swap leaves alone. Every other key travels with its account. */
    private static final Set<String> GLOBAL_KEYS = new HashSet<>(Arrays.asList(
            OWNER_KEY,
            "piko_debug",
            "material_you_theme",
            "amoled_theme",
            "instagram_theme_mode",
            "instagram_theme_mode_synchronized",
            "use_system_font",
            "first_time_piko",
            "enable_developer_options",
            "directly_open_metaconfig",
            "focus_lock_duration_minutes",
            "focus_lock_until",
            "focus_lock_format",
            "focus_lock_unlock_requested_at",
            "focus_lock_block_reels",
            "focus_lock_block_explore",
            "custom_download_path",
            // The folder grant behind custom_download_path; the two must switch together.
            "custom_download_tree_uri",
            "external_downloader_package_name",
            "download_with_external_downloader",
            "save_downloads_to_phone_storage",
            "gallery_max_age_days",
            "gallery_max_size_gb",
            "marked_story_keys_v2",
            "inbox_lock",
            "inbox_lock_notifications",
            "hidden_chats",
            "hidden_chats_taps",
            "hidden_chats_mute"
    ));

    enum Action { NONE, ADOPT, SWAP }

    private static String pendingId;
    // Account whose swap failed to reach the disk, so its next event swaps again.
    private static String retryId;
    private static boolean callbacksRegistered;
    // Set once an import replaced the live file; the cached preferences would overwrite it before the restart.
    static boolean liveFileReplaced;
    private static volatile boolean restartPending;
    private static Map<String, Object> settingDefaults;

    private AccountSettings() {
    }

    /** Called just before Instagram's session manager returns the session it made current. */
    public static synchronized void onCurrentSession(UserSession session) {
        try {
            String id = session == null ? null : session.getUserId();
            if (id == null || id.trim().isEmpty()) return;
            Context context = Utils.getContext();
            if (context == null) {
                pendingId = id;
                return;
            }
            switchTo(context, id);
        } catch (Throwable t) {
            logFailure("onCurrentSession failure", t);
        }
    }

    /** Called from {@code SettingsStatus.load()}, before any startup reads of the settings. */
    public static synchronized void onAppStart() {
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            if (pendingId != null) {
                String id = pendingId;
                pendingId = null;
                switchTo(context, id);
            }
            registerRestartNotice(context.getApplicationContext());
        } catch (Throwable t) {
            logFailure("onAppStart failure", t);
        }
    }

    /** Replaces the live file with an imported one, which the account active after the restart takes over. */
    public static synchronized boolean replaceLiveFile(File source, File destination) {
        boolean replaced = replaceUnderMarker(AccountSettings::adoptLiveFile, () -> source.renameTo(destination),
                AccountSettings::clearAdoptMarker);
        if (replaced) Pref.invalidateCaches();
        return replaced;
    }

    /** Replaces only once the marker is on disk, and takes the marker back if either step fails. */
    static boolean replaceUnderMarker(BooleanSupplier setMarker, BooleanSupplier replace, Runnable clearMarker) {
        boolean replaced = false;
        try {
            replaced = setMarker.getAsBoolean() && replace.getAsBoolean();
        } finally {
            if (replaced) {
                liveFileReplaced = true;
            } else {
                clearMarker.run();
            }
        }
        return replaced;
    }

    private static synchronized boolean adoptLiveFile() {
        try {
            Context context = Utils.getContext();
            return context != null
                    && prefs(context, ACCOUNTS_PREF_NAME).edit().putBoolean(ADOPT_LIVE_FILE_KEY, true).commit();
        } catch (Throwable t) {
            logFailure("adoptLiveFile failure", t);
            return false;
        }
    }

    private static synchronized void clearAdoptMarker() {
        try {
            Context context = Utils.getContext();
            if (context != null) prefs(context, ACCOUNTS_PREF_NAME).edit().remove(ADOPT_LIVE_FILE_KEY).commit();
        } catch (Throwable t) {
            logFailure("clearAdoptMarker failure", t);
        }
    }

    private static void switchTo(Context context, String id) {
        SharedPreferences live = prefs(context, Constants.SHARED_PREF_NAME);
        SharedPreferences accounts = prefs(context, ACCOUNTS_PREF_NAME);
        boolean adopt = accounts.getBoolean(ADOPT_LIVE_FILE_KEY, false);
        String owner = live.getString(OWNER_KEY, null);

        switch (action(owner, id, adopt, id.equals(retryId))) {
            case NONE:
                if (liveFileReplaced) logFailure("Account change ignored until the restart after an import", null);
                return;
            case ADOPT:
                boolean adopted = live.edit().putString(OWNER_KEY, id).commit();
                if (adopted) Pref.invalidateCaches();
                if (adopted && adopt) {
                    accounts.edit().remove(ADOPT_LIVE_FILE_KEY).commit();
                }
                return;
            case SWAP:
                Map<String, Object> outgoing = snapshot(live.getAll(), owner);
                SharedPreferences.Editor park = prefs(context, snapshotName(owner)).edit().clear();
                putAll(park, outgoing);
                if (!park.commit()) {
                    logFailure("Failed to park the settings of the previous account", null);
                    return;
                }

                Map<String, ?> incoming = prefs(context, snapshotName(id)).getAll();
                boolean restart = restartRequired(outgoing, incoming, AccountSettings::settingDefault);
                // Caches are dropped here because change callbacks for writes off the main thread arrive later.
                if (commit(live, keysToRemove(outgoing, incoming), keysToPut(incoming, id))) {
                    Pref.invalidateCaches();
                    retryId = null;
                    if (restart) restartPending = true;
                    return;
                }
                // commit() changes memory before the disk write fails, so readers already see the incoming values.
                logFailure("Failed to write the settings of the current account", null);
                retryId = id;
                if (!commit(live, keysToRemove(incoming, outgoing), keysToPut(outgoing, owner))) {
                    restartPending = true;
                }
                // Both commits changed memory whether or not they reached the disk.
                Pref.invalidateCaches();
        }
    }

    static Action action(String owner, String id, boolean adoptLiveFile, boolean retry) {
        if (liveFileReplaced) return Action.NONE;
        if (owner == null || adoptLiveFile) return Action.ADOPT;
        return id.equals(owner) && !retry ? Action.NONE : Action.SWAP;
    }

    static boolean isGlobal(String key) {
        return GLOBAL_KEYS.contains(key) || key.startsWith(FocusLock.SELECTION_PREFIX);
    }

    static Map<String, Object> perAccount(Map<String, ?> values) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!isGlobal(entry.getKey())) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** The owner's per-account values plus the owner key, so a parked account is never empty. */
    static Map<String, Object> snapshot(Map<String, ?> live, String owner) {
        Map<String, Object> result = perAccount(live);
        result.put(OWNER_KEY, owner);
        return result;
    }

    /** Live keys to drop: those the incoming account lacks. A new account keeps a copy of everything. */
    static Set<String> keysToRemove(Map<String, ?> outgoing, Map<String, ?> incoming) {
        Set<String> result = new HashSet<>();
        if (incoming.isEmpty()) return result;
        for (String key : outgoing.keySet()) {
            if (!isGlobal(key) && !incoming.containsKey(key)) result.add(key);
        }
        return result;
    }

    static Map<String, Object> keysToPut(Map<String, ?> incoming, String id) {
        Map<String, Object> result = perAccount(incoming);
        result.put(OWNER_KEY, id);
        return result;
    }

    /** Compares effective values: a missing entry counts as {@code defaults}' value, null if unknown. */
    static boolean restartRequired(Map<String, ?> outgoing, Map<String, ?> incoming,
                                   Function<String, Object> defaults) {
        if (incoming.isEmpty()) return false;
        Set<String> keys = new HashSet<>(outgoing.keySet());
        keys.addAll(incoming.keySet());
        for (String key : keys) {
            if (!isGlobal(key) && isRestartRequired(key)
                    && !Objects.equals(effective(outgoing, key, defaults), effective(incoming, key, defaults))) {
                return true;
            }
        }
        return false;
    }

    private static Object effective(Map<String, ?> values, String key, Function<String, Object> defaults) {
        return values.containsKey(key) ? values.get(key) : defaults.apply(key);
    }

    // Only reached for registered keys, so Settings is already loaded.
    private static Object settingDefault(String key) {
        if (settingDefaults == null) {
            Map<String, Object> defaults = new HashMap<>();
            try {
                for (Field field : Settings.class.getFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && Setting.class.isAssignableFrom(field.getType())) {
                        Setting<?> setting = (Setting<?>) field.get(null);
                        defaults.put(setting.key, setting.defaultValue);
                    }
                }
            } catch (Throwable t) {
                logFailure("Failed to read setting defaults", t);
            }
            settingDefaults = defaults;
        }
        return settingDefaults.get(key);
    }

    // Keys register when Settings loads; before that nothing has cached a value, so no restart is needed.
    private static boolean isRestartRequired(String key) {
        try {
            return SettingRegistry.restartPolicyFor(key) == RestartPolicy.REQUIRED;
        } catch (NullPointerException e) {
            return false;
        }
    }

    private static String snapshotName(String id) {
        return Constants.SHARED_PREF_NAME + "_" + id;
    }

    private static SharedPreferences prefs(Context context, String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static boolean commit(SharedPreferences prefs, Set<String> remove, Map<String, ?> put) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : remove) {
            editor.remove(key);
        }
        putAll(editor, put);
        return editor.commit();
    }

    @SuppressWarnings("unchecked")
    private static void putAll(SharedPreferences.Editor editor, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
        }
    }

    private static void registerRestartNotice(Context context) {
        if (callbacksRegistered || !(context instanceof Application)) return;
        ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override
            public void onActivityResumed(Activity activity) {
                if (restartPending) showRestartNotice(activity);
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
        callbacksRegistered = true;
    }

    private static void showRestartNotice(Activity activity) {
        try {
            if (activity.isFinishing() || ActivityHook.isSpookd(activity)) return;
            restartPending = false;
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setTitle(str("spookd_restart_app"))
                    .setPositiveButton(str("spookd_ok"), (dialog, which) -> InstaUtils.restartApp(activity))
                    .setNegativeButton(str("spookd_cancel"), null)
                    .show();
        } catch (Throwable t) {
            logFailure("Failed to show the account restart notice", t);
        }
    }

    private static void logFailure(String message, Throwable t) {
        try {
            Logger.printException(() -> message, t);
        } catch (Throwable ignored) {
        }
    }
}
