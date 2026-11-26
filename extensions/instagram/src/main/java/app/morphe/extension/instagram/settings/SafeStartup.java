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

import app.morphe.extension.instagram.settings.preference.fragments.FragmentHook;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.InstaUtils;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Disable all, safe startup mode and the launch-time tools. Their state is device-wide, in
 * {@code spookd_startup}, not in the per-account settings file. A foreground start opens an
 * attempt that closes once an Instagram activity has been resumed for {@link #STARTUP_OK_MS}; a
 * start that finds the previous attempt still open counts it as failed, whatever killed it. After
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
    private static final long STARTUP_OK_MS = 5000;
    private static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    private static volatile Boolean featuresOff;
    // Guarded by the class lock.
    private static boolean safeModeThisProcess;
    private static boolean attemptOpened;
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
        boolean off = false;
        try {
            SharedPreferences startup = prefs(context);
            if (isForegroundStart()) openAttempt(startup);
            safeModeThisProcess = startup.getBoolean(SAFE_MODE_KEY, false);
            off = safeModeThisProcess || startup.getBoolean(DISABLE_ALL_KEY, false);
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup.featuresOff failure", t);
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
                // A process started in the background becomes a launch once something is shown.
                openAttemptOnce(activity);
                Utils.runOnMainThreadDelayed(SafeStartup::onStartupSucceeded, STARTUP_OK_MS);
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

    /** The stored Disable all switch, for the settings row; main thread. */
    public static boolean disableAllStored() {
        Context context = Utils.getContext();
        return context != null && prefs(context).getBoolean(DISABLE_ALL_KEY, false);
    }

    /** Main thread, from the settings row. Written at once, since a restart may kill the process next. */
    public static void setDisableAll(boolean enabled) {
        Context context = Utils.getContext();
        if (context == null) return;
        SharedPreferences startup = prefs(context);
        boolean previous = startup.getBoolean(DISABLE_ALL_KEY, false);
        if (startup.edit().putBoolean(DISABLE_ALL_KEY, enabled).commit()) {
            SettingsRestart.markChanged(DISABLE_ALL_KEY, RestartPolicy.REQUIRED, previous, enabled);
        }
    }

    /** Part of resetting spookd settings: turns Disable all and safe mode off. Any thread. */
    public static void resetStoredState() {
        Context context = Utils.getContext();
        if (context != null) prefs(context).edit().clear().commit();
    }

    private static synchronized boolean safeModeThisProcess() {
        featuresOff();
        return safeModeThisProcess;
    }

    private static synchronized void openAttemptOnce(Context context) {
        featuresOff();
        if (!attemptOpened) openAttempt(prefs(context));
    }

    private static void onStartupSucceeded() {
        Context context = Utils.getContext();
        if (context == null) return;
        closeAttempt(prefs(context));
        if (!featuresOff()) StorageScreen.autoClearCache(context);
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
                .setNeutralButton(str("spookd_safe_mode_turn_on"), (dialog, which) -> {
                    startup.edit().putBoolean(SAFE_MODE_KEY, false).commit();
                    InstaUtils.restartApp(activity);
                })
                .show();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Counts a still-open previous attempt as failed, turns safe mode on at the limit and opens this
     * one. Synchronous, so a start that dies right after still leaves its marker.
     */
    static void openAttempt(SharedPreferences startup) {
        attemptOpened = true;
        int failed = startup.getInt(FAILED_STARTS_KEY, 0) + (startup.getBoolean(ATTEMPT_OPEN_KEY, false) ? 1 : 0);
        SharedPreferences.Editor editor = startup.edit()
                .putBoolean(ATTEMPT_OPEN_KEY, true)
                .putInt(FAILED_STARTS_KEY, failed);
        if (failed >= FAILED_STARTS_FOR_SAFE_MODE) {
            editor.putBoolean(SAFE_MODE_KEY, true).putInt(FAILED_STARTS_KEY, 0);
        }
        editor.commit();
    }

    static void closeAttempt(SharedPreferences startup) {
        if (startup.getBoolean(ATTEMPT_OPEN_KEY, false) || startup.getInt(FAILED_STARTS_KEY, 0) != 0) {
            startup.edit().putBoolean(ATTEMPT_OPEN_KEY, false).putInt(FAILED_STARTS_KEY, 0).apply();
        }
    }
}
