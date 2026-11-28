/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Lock spookd settings: settings screens open once the device's biometric or screen lock confirms
 * it, then freely until the app goes to the background, which closes them and locks again. Reads
 * the stored switch, like Inbox lock, so it holds while features are off. Main thread only.
 */
public final class SettingsLock {
    // Package-private so tests can start from a fresh process state and stand in the switch.
    static boolean installed;
    static boolean unlocked;
    static int startedActivities;
    static BooleanSupplier stored = Pref::settingsLock;
    private static final Set<Activity> screens = Collections.newSetFromMap(new WeakHashMap<>());

    private SettingsLock() {
    }

    /** Whether the lock applies: the switch is on and a secure screen lock exists, so it never locks the user out. */
    static boolean enabled(Context context) {
        return stored.getAsBoolean() && InboxLock.canConfirmCredential(context);
    }

    /** From the settings switch: false, with a toast, when there is no secure screen lock to ask for. */
    public static boolean allowTurningOn(Context context) {
        if (!InboxLock.canConfirmCredential(context)) {
            Utils.showToastShort(str("spookd_settings_lock_no_lock"));
            return false;
        }
        // Turned on from inside settings, so this session is already past the lock.
        unlocked = true;
        return true;
    }

    /** Called with the first resumed activity, the only started one then, so leaving the app can be told apart. Never throws. */
    static void install(Activity first) {
        if (installed) return;
        installed = true;
        startedActivities = 1;
        try {
            register(first.getApplication());
        } catch (Throwable t) {
            Logger.printException(() -> "Settings lock: cannot watch the app's activities", t);
        }
    }

    private static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { startedActivities++; }

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
                // A rotation stops and restarts the activity; only really leaving the app locks again.
                if (startedActivities == 0 && !activity.isChangingConfigurations()) lock(activity);
            }

            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    private static void lock(Context context) {
        unlocked = false;
        try {
            if (!enabled(context)) return;
            for (Activity screen : screens) screen.finish();
            screens.clear();
        } catch (Throwable t) {
            Logger.printException(() -> "Settings lock: cannot close settings", t);
        }
    }

    /**
     * From the settings launch: runs {@code setup} now, or once the screen lock confirms it;
     * cancelling closes the screen. A gate that fails opens settings anyway.
     */
    static void open(Activity activity, Runnable setup) {
        boolean ask;
        try {
            ask = !unlocked && enabled(activity);
        } catch (Throwable t) {
            Logger.printException(() -> "Settings lock: cannot read its state, opening settings", t);
            ask = false;
        }
        if (!ask) {
            show(activity, setup);
            return;
        }
        if (InboxLock.isPrompting()) {
            // A prompt that confirmed the device lock counts for settings too.
            InboxLock.afterPrompt(confirmed -> {
                if (confirmed) unlocked = true;
                if (!activity.isFinishing() && !activity.isDestroyed()) open(activity, setup);
            });
            return;
        }
        try {
            InboxLock.confirmViewerWithCredential(activity, () -> {
                unlocked = true;
                if (!activity.isFinishing() && !activity.isDestroyed()) show(activity, setup);
            }, "spookd_settings_lock_prompt");
        } catch (Throwable t) {
            Logger.printException(() -> "Settings lock: prompt failed, opening settings", t);
            show(activity, setup);
        }
    }

    /**
     * Runs {@code reset}, which deletes settings, now or once the screen lock confirms it while the
     * lock applies. Cancelling, a prompt already up or a failed prompt runs nothing.
     */
    static void confirmReset(Activity activity, Runnable reset) {
        try {
            if (unlocked || !enabled(activity)) {
                reset.run();
                return;
            }
            InboxLock.confirm(activity, () -> {
                unlocked = true;
                reset.run();
            });
        } catch (Throwable t) {
            Logger.printException(() -> "Settings lock: reset prompt failed, nothing was reset", t);
        }
    }

    private static void show(Activity activity, Runnable setup) {
        screens.add(activity);
        setup.run();
    }
}
