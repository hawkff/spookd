/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.settings.preference.fragments.FragmentHook;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.InstaUtils;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Disable all, safe startup mode and the launch-time tools. Their state is device-wide, in
 * {@code spookd_startup}, not in the per-account settings file. A foreground cold start opens an
 * attempt that the first resumed Instagram activity closes; a start that finds the previous
 * attempt still open counts it as failed, whatever killed it. After
 * {@link #FAILED_STARTS_FOR_SAFE_MODE} in a row the start runs with every feature off.
 */
public final class SafeStartup {
    static final int FAILED_STARTS_FOR_SAFE_MODE = 3;
    static final String FAILED_STARTS_KEY = "failed_starts";
    static final String ATTEMPT_OPEN_KEY = "start_attempt_open";
    static final String SAFE_MODE_KEY = "safe_mode";
    /** Also the key of the settings row. */
    public static final String DISABLE_ALL_KEY = "disable_all_features";
    private static final String PREFS_NAME = "spookd_startup";
    private static final long CACHE_CLEANUP_DELAY_MS = 5000;
    private static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    // Startup-state saves that a restart must wait for run here, in order.
    private static final ExecutorService SAVES = Executors.newSingleThreadExecutor();

    private static volatile Boolean featuresOff;
    // Guarded by the class lock.
    private static boolean safeModeThisProcess;
    // Main thread only.
    private static boolean anyResumed;
    private static boolean mainResumed;

    private SafeStartup() {
    }

    /**
     * Whether Disable all or safe startup mode is on, so feature getters read as off. Fixed for the
     * process at the first read with a context; any thread, never throws.
     */
    public static boolean featuresOff() {
        Boolean off = featuresOff;
        return off != null ? off : loadFeaturesOff();
    }

    private static synchronized boolean loadFeaturesOff() {
        if (featuresOff != null) return featuresOff;
        Context context = Utils.getContext();
        if (context == null) return false;
        boolean off;
        try {
            SharedPreferences startup = prefs(context);
            safeModeThisProcess = startsInSafeMode(startup, isForegroundStart());
            off = safeModeThisProcess || readBoolean(startup, DISABLE_ALL_KEY);
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup: unreadable startup state, starting in safe mode", t);
            // Starting as safe mode also shows its dialog, which offers a way back.
            safeModeThisProcess = true;
            off = true;
        }
        featuresOff = off;
        return off;
    }

    /** Called on the main thread in {@code InstagramAppShell.onCreate}, so the decision is made at process start. */
    public static void install() {
        featuresOff();
    }

    /** Called on the main thread at the end of every Instagram activity's onResume. */
    public static void onActivityResumed(Activity activity) {
        try {
            if (activity == null || mainResumed) return;
            if (!anyResumed) {
                anyResumed = true;
                // The first screen shown ends the start; a process started in the background never opened one.
                Context context = activity.getApplicationContext();
                closeAttempt(prefs(context));
                Utils.runOnMainThreadDelayed(() -> cleanCacheLater(context), CACHE_CLEANUP_DELAY_MS);
            }
            if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
            mainResumed = true;
            if (safeModeThisProcess()) {
                Utils.runOnMainThread(() -> showSafeModeDialog(activity));
            } else if (Pref.openSettingsOnLaunch()) {
                Utils.runOnMainThread(FragmentHook::startSettings);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup.onActivityResumed failure", t);
        }
    }

    /** The stored Disable all switch, for the settings row; any thread, never throws. */
    public static boolean disableAllStored() {
        try {
            Context context = Utils.getContext();
            return context != null && readBoolean(prefs(context), DISABLE_ALL_KEY);
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup.disableAllStored failure", t);
            return false;
        }
    }

    /** Runs a startup-state save, or a restart that must follow every save queued before it, off the main thread. */
    public static void runSave(Runnable task) {
        SAVES.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Logger.printException(() -> "SafeStartup: save failed", t);
            }
        });
    }

    /** Background thread, from the settings row; true once the value is on disk. */
    public static boolean setDisableAll(boolean enabled) {
        try {
            Context context = Utils.getContext();
            return context != null && prefs(context).edit().putBoolean(DISABLE_ALL_KEY, enabled).commit();
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup.setDisableAll failure", t);
            return false;
        }
    }

    /** Background thread, part of resetting spookd settings; true once Disable all and safe mode are off on disk. */
    public static boolean resetStoredState() {
        try {
            Context context = Utils.getContext();
            return context != null && prefs(context).edit().clear().commit();
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup.resetStoredState failure", t);
            return false;
        }
    }

    private static synchronized boolean safeModeThisProcess() {
        featuresOff();
        return safeModeThisProcess;
    }

    private static void cleanCacheLater(Context context) {
        try {
            if (!featuresOff()) StorageScreen.autoClearCache(context);
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup: cache cleanup failure", t);
        }
    }

    /** Whether the process was started to show an activity, not for a push, job or other background work. */
    private static boolean isForegroundStart() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static void showSafeModeDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        SharedPreferences startup = prefs(activity);
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(str("spookd_safe_mode_title"))
                .setMessage(str("spookd_safe_mode_message"))
                // Disable all takes over, so later launches stay off without this dialog.
                .setPositiveButton(str("spookd_safe_mode_keep"), (dialog, which) -> startup.edit()
                        .putBoolean(DISABLE_ALL_KEY, true).putBoolean(SAFE_MODE_KEY, false).apply())
                .setNegativeButton(str("spookd_reset_pref"),
                        (dialog, which) -> InstaUtils.showResetSettingsDialog(activity))
                .setNeutralButton(str("spookd_safe_mode_turn_on"), (dialog, which) ->
                        runSave(() -> {
                            boolean saved = startup.edit()
                                    .putBoolean(SAFE_MODE_KEY, false).putBoolean(DISABLE_ALL_KEY, false).commit();
                            Utils.runOnMainThread(() -> {
                                if (saved) InstaUtils.restartApp(activity);
                                else Utils.showToastShort(str("spookd_setting_save_failed"));
                            });
                        }))
                .show();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** A stored boolean; a value of another type reads as false and is rewritten as false. */
    static boolean readBoolean(SharedPreferences startup, String key) {
        try {
            return startup.getBoolean(key, false);
        } catch (ClassCastException e) {
            startup.edit().putBoolean(key, false).apply();
            return false;
        }
    }

    /** A stored int; a value of another type reads as 0 and is rewritten as 0. */
    static int readInt(SharedPreferences startup, String key) {
        try {
            return startup.getInt(key, 0);
        } catch (ClassCastException e) {
            startup.edit().putInt(key, 0).apply();
            return 0;
        }
    }

    /**
     * The process's safe-mode decision: repairs startup keys stored with a wrong type, which then
     * start this process in safe mode, opens the attempt of a foreground start, and reads the flag.
     */
    static boolean startsInSafeMode(SharedPreferences startup, boolean foreground) {
        boolean malformed = repairMalformed(startup);
        if (foreground) openAttempt(startup);
        return malformed || readBoolean(startup, SAFE_MODE_KEY);
    }

    /** Rewrites each startup key of the wrong type with its default; true when one was found. Absent keys are fine. */
    static boolean repairMalformed(SharedPreferences startup) {
        Map<String, ?> all = startup.getAll();
        SharedPreferences.Editor editor = startup.edit();
        boolean malformed = false;
        for (String key : new String[]{SAFE_MODE_KEY, DISABLE_ALL_KEY, ATTEMPT_OPEN_KEY}) {
            Object value = all.get(key);
            if (value != null && !(value instanceof Boolean)) {
                editor.putBoolean(key, false);
                malformed = true;
            }
        }
        Object failed = all.get(FAILED_STARTS_KEY);
        if (failed != null && !(failed instanceof Integer)) {
            editor.putInt(FAILED_STARTS_KEY, 0);
            malformed = true;
        }
        if (malformed) editor.apply();
        return malformed;
    }

    /** Counts a still-open previous attempt as failed, turns safe mode on at the limit and opens this one. */
    static void openAttempt(SharedPreferences startup) {
        int failed = readInt(startup, FAILED_STARTS_KEY) + (readBoolean(startup, ATTEMPT_OPEN_KEY) ? 1 : 0);
        SharedPreferences.Editor editor = startup.edit()
                .putBoolean(ATTEMPT_OPEN_KEY, true)
                .putInt(FAILED_STARTS_KEY, failed);
        if (failed >= FAILED_STARTS_FOR_SAFE_MODE) {
            editor.putBoolean(SAFE_MODE_KEY, true).putInt(FAILED_STARTS_KEY, 0);
        }
        // The one synchronous write at cold start, so a native crash right after still counts this start.
        editor.commit();
    }

    static void closeAttempt(SharedPreferences startup) {
        if (readBoolean(startup, ATTEMPT_OPEN_KEY) || readInt(startup, FAILED_STARTS_KEY) != 0) {
            startup.edit().putBoolean(ATTEMPT_OPEN_KEY, false).putInt(FAILED_STARTS_KEY, 0).apply();
        }
    }
}
