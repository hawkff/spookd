/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.preference.fragments.FragmentHook;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.InstaUtils;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Disable all, safe startup mode and the launch-time tools. Crashes are counted until the main
 * activity has been resumed for {@link #STARTUP_OK_MS}; after {@link #CRASHES_FOR_SAFE_MODE} in a
 * row the next launch starts with every feature off, as Disable all does.
 */
public final class SafeStartup {
    static final int CRASHES_FOR_SAFE_MODE = 3;
    static final String CRASHES_KEY = "crashes_before_main_screen";
    private static final String PREFS_NAME = "spookd_startup";
    private static final long STARTUP_OK_MS = 5000;
    private static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    private static volatile Boolean featuresOff;
    private static volatile boolean startupDone;
    // Main thread only.
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

    // Unsynchronized: its writes notify listeners that lock cached settings; racing first reads agree.
    private static boolean loadFeaturesOff() {
        Context context = Utils.getContext();
        if (context == null) return false;
        boolean off = false;
        try {
            SharedPreferences startup = prefs(context);
            if (safeModeDue(startup)) {
                SharedPref.setBooleanPref(Settings.SAFE_STARTUP_MODE.key, true);
                SharedPref.flush();
                resetCrashes(startup);
            }
            off = SharedPref.getBooleanPref(Settings.DISABLE_ALL_FEATURES)
                    || SharedPref.getBooleanPref(Settings.SAFE_STARTUP_MODE);
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup.featuresOff failure", t);
        }
        featuresOff = off;
        return off;
    }

    /** Called on the main thread in {@code InstagramAppShell.onCreate}, after the context is set. */
    public static void install() {
        try {
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    Context context = Utils.getContext();
                    if (!startupDone && context != null) recordCrash(prefs(context));
                } catch (Throwable ignored) {
                    // Counting must never stand between a crash and the system's handler.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            });
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup.install failure", t);
        }
    }

    /** Called on the main thread at the end of every Instagram activity's onResume. */
    public static void onActivityResumed(Activity activity) {
        try {
            if (mainResumed || activity == null || !MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
            mainResumed = true;
            Utils.runOnMainThreadDelayed(SafeStartup::onStartupSucceeded, STARTUP_OK_MS);
            if (SharedPref.getBooleanPref(Settings.SAFE_STARTUP_MODE)) {
                Utils.runOnMainThread(() -> showSafeModeDialog(activity));
            } else if (Pref.openSettingsOnLaunch()) {
                Utils.runOnMainThread(FragmentHook::startSettings);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "SafeStartup.onActivityResumed failure", t);
        }
    }

    private static void onStartupSucceeded() {
        startupDone = true;
        Context context = Utils.getContext();
        if (context == null) return;
        resetCrashes(prefs(context));
        if (!featuresOff()) StorageScreen.autoClearCache(context);
    }

    private static void showSafeModeDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(str("spookd_safe_mode_title"))
                .setMessage(str("spookd_safe_mode_message"))
                .setPositiveButton(str("spookd_safe_mode_keep"), (dialog, which) -> {
                    // Disable all takes over, so later launches stay off without this dialog.
                    SharedPref.setBooleanPref(Settings.DISABLE_ALL_FEATURES.key, true);
                    SharedPref.setBooleanPref(Settings.SAFE_STARTUP_MODE.key, false);
                })
                .setNegativeButton(str("spookd_reset_pref"),
                        (dialog, which) -> InstaUtils.showResetSettingsDialog(activity))
                .setNeutralButton(str("spookd_safe_mode_turn_on"), (dialog, which) -> {
                    SharedPref.setBooleanPref(Settings.SAFE_STARTUP_MODE.key, false);
                    SharedPref.flush();
                    InstaUtils.restartApp(activity);
                })
                .show();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Synchronous, as the process ends right after. */
    static void recordCrash(SharedPreferences startup) {
        startup.edit().putInt(CRASHES_KEY, startup.getInt(CRASHES_KEY, 0) + 1).commit();
    }

    static boolean safeModeDue(SharedPreferences startup) {
        return startup.getInt(CRASHES_KEY, 0) >= CRASHES_FOR_SAFE_MODE;
    }

    static void resetCrashes(SharedPreferences startup) {
        if (startup.getInt(CRASHES_KEY, 0) != 0) startup.edit().putInt(CRASHES_KEY, 0).apply();
    }
}
