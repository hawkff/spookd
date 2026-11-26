/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import app.morphe.extension.shared.Utils;

/**
 * The screen an Instant send started on, watched until its question is shown, so a send whose screen
 * was left meanwhile is dropped instead of going out unasked.
 */
final class InstantsOrigin implements Application.ActivityLifecycleCallbacks {
    private final Activity activity;
    private volatile boolean paused;

    private InstantsOrigin(Activity activity) {
        this.activity = activity;
    }

    /** Any thread: the screen shown now, watched for a pause. */
    static InstantsOrigin current() {
        InstantsOrigin origin = new InstantsOrigin(Utils.getActivity());
        if (origin.activity != null) origin.activity.getApplication().registerActivityLifecycleCallbacks(origin);
        return origin;
    }

    /** Main thread, once: stops watching; true when the screen is gone, paused or no longer shown. */
    boolean left() {
        if (activity == null) return true;
        activity.getApplication().unregisterActivityLifecycleCallbacks(this);
        return wasLeft(activity.isFinishing() || activity.isDestroyed(), paused, activity == Utils.getActivity());
    }

    static boolean wasLeft(boolean finishing, boolean paused, boolean stillShown) {
        return finishing || paused || !stillShown;
    }

    @Override
    public void onActivityPaused(Activity pausedActivity) {
        if (pausedActivity == activity) paused = true;
    }

    @Override
    public void onActivityCreated(Activity created, Bundle state) {
    }

    @Override
    public void onActivityStarted(Activity started) {
    }

    @Override
    public void onActivityResumed(Activity resumed) {
    }

    @Override
    public void onActivityStopped(Activity stopped) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity saved, Bundle state) {
    }

    @Override
    public void onActivityDestroyed(Activity destroyed) {
    }
}
