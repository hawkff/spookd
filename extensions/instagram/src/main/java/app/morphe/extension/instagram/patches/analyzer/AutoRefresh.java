package app.morphe.extension.instagram.patches.analyzer;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Rescans the current account once per app process, after the app stayed on screen for
 * {@link #SETTLE_DELAY_MS}, when its last finished scan is older than the chosen interval. A
 * refused, failed or throttled scan waits for the next process.
 */
public final class AutoRefresh {

    static final long SETTLE_DELAY_MS = 30_000L;
    static final long DAY_MS = 24 * 60 * 60 * 1000L;

    // Main thread.
    private static int resumedActivities;
    private static WeakReference<Activity> lastResumed = new WeakReference<>(null);
    private static boolean checkPending;
    private static boolean attempted;

    private AutoRefresh() {
    }

    /** Whether a scan that finished at lastFinishedAt is due again; a clock set back makes it due. */
    static boolean isDue(long lastFinishedAt, int days, long now) {
        return days > 0 && lastFinishedAt > 0
                && (now >= lastFinishedAt + days * DAY_MS || now < lastFinishedAt);
    }

    /** Main thread, from ProfileAnalyzer.onAppStart in InstagramAppShell.onCreate. */
    static void install(Context context) {
        try {
            Context app = context == null ? null : context.getApplicationContext();
            if (!(app instanceof Application)) return;
            ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) { onResumed(activity); }
                @Override public void onActivityPaused(Activity activity) { if (resumedActivities > 0) resumedActivities--; }
                @Override public void onActivityCreated(Activity a, Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "AutoRefresh: install failed: " + error);
        }
    }

    private static void onResumed(Activity activity) {
        resumedActivities++;
        lastResumed = new WeakReference<>(activity);
        if (attempted || checkPending) return;
        checkPending = true;
        Utils.runOnMainThreadDelayed(AutoRefresh::check, SETTLE_DELAY_MS);
    }

    /** Main thread. The resumed activity of the app, or null while none is. */
    private static Activity resumedActivity() {
        return resumedActivities > 0 ? lastResumed.get() : null;
    }

    /** Main thread: this process's one attempt, unless the app left the screen during the delay. */
    private static void check() {
        checkPending = false;
        Activity activity = resumedActivity();
        if (activity == null) return;
        attempted = true;
        try {
            int days = Pref.profileAnalyzerAutoRefreshDays();
            String accountId = ProfileAnalyzer.currentAccountId();
            if (days == 0 || accountId == null) return;
            Context context = activity.getApplicationContext();
            ProfileVisits.DB_QUEUE.execute(() -> {
                try {
                    SpookdFollowDb.Scan last = SpookdFollowDb.getInstance(context).latestScan(accountId);
                    if (last == null || !isDue(last.finishedAt, days, System.currentTimeMillis())) return;
                    Utils.runOnMainThread(() -> {
                        Activity resumed = resumedActivity();
                        if (resumed != null) ProfileAnalyzer.startAutomaticScan(resumed, accountId, last);
                    });
                } catch (Exception e) {
                    String error = e.getClass().getSimpleName();
                    Logger.printException(() -> "AutoRefresh: last scan read failed: " + error);
                }
            });
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "AutoRefresh: check failed: " + error);
        }
    }
}
