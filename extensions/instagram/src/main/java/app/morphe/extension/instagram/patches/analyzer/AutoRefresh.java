package app.morphe.extension.instagram.patches.analyzer;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;
import android.text.format.DateUtils;
import android.view.View;

import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.preference.widgets.ListPref;
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
    private static final String[] DAYS = {"0", "1", "3", "7", "14"};
    private static final String[] ENTRY_KEYS = {
            "spookd_profile_analyzer_auto_refresh_off",
            "spookd_profile_analyzer_auto_refresh_daily",
            "spookd_profile_analyzer_auto_refresh_3_days",
            "spookd_profile_analyzer_auto_refresh_weekly",
            "spookd_profile_analyzer_auto_refresh_2_weeks",
    };
    private static final int DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
            | DateUtils.FORMAT_ABBREV_MONTH;

    // Main thread.
    private static int resumedActivities;
    private static WeakReference<Activity> lastResumed = new WeakReference<>(null);
    private static boolean checkPending;
    // Bumped when the app leaves the screen, so older delayed checks, reads and waits start nothing.
    private static int generation;
    // True once the wait completed in the current foreground stretch; every automatic start needs it.
    static boolean settled;
    // True once this process started its automatic scan or found that none is needed.
    static boolean attempted;
    // An account whose due scan waits for the counts of its own profile header.
    private static String waitingAccountId;
    private static WeakReference<Row> row = new WeakReference<>(null);

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
                @Override public void onActivityPaused(Activity activity) { onPaused(); }
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

    static void onResumed(Activity activity) {
        resumedActivities++;
        lastResumed = new WeakReference<>(activity);
        Row current = row.get();
        // The settings page came back, maybe after a scan or a delete in the analyzer.
        if (current != null && current.host == activity) current.readLastScan();
        if (attempted || settled || checkPending) return;
        checkPending = true;
        int scheduled = generation;
        Utils.runOnMainThreadDelayed(() -> check(scheduled), SETTLE_DELAY_MS);
    }

    static void onPaused() {
        if (resumedActivities > 0) resumedActivities--;
        if (resumedActivities > 0) return;
        generation++;
        settled = false;
        checkPending = false;
    }

    /** Main thread. The resumed activity of the app, or null while none is. */
    private static Activity resumedActivity() {
        return resumedActivities > 0 ? lastResumed.get() : null;
    }

    /** Main thread: the wait completed; makes this process's one attempt or resumes a deferred one. */
    private static void check(int scheduled) {
        if (scheduled != generation) return;
        checkPending = false;
        Activity activity = resumedActivity();
        if (activity == null) return;
        settled = true;
        waitingAccountId = null;
        try {
            String accountId = Pref.profileAnalyzerAutoRefreshDays() == 0 ? null
                    : ProfileAnalyzer.currentAccountId();
            if (accountId == null) {
                attempted = true;
                return;
            }
            readAndStart(activity.getApplicationContext(), accountId, scheduled);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "AutoRefresh: check failed: " + error);
        }
    }

    /** Main thread: reads the last scan on the analyzer database queue, then decides on the main thread. */
    private static void readAndStart(Context context, String accountId, int foreground) {
        ProfileVisits.DB_QUEUE.execute(() -> {
            try {
                // Read on the queue that deletes run on, so a later delete changes the generation.
                int dataGeneration = ProfileAnalyzer.dataGeneration();
                SpookdFollowDb.Scan last = SpookdFollowDb.getInstance(context).latestScan(accountId);
                long finishedAt = last == null ? 0 : last.finishedAt;
                Utils.runOnMainThread(() -> startIfStillDue(accountId, finishedAt, dataGeneration, foreground));
            } catch (Exception e) {
                String error = e.getClass().getSimpleName();
                Logger.printException(() -> "AutoRefresh: last scan read failed: " + error);
            }
        });
    }

    /**
     * Main thread: starts the scan unless the setting changed during the read. Leaving the app
     * during the read defers the start to the next completed wait, which reads again.
     */
    private static void startIfStillDue(String accountId, long lastFinishedAt, int dataGeneration,
                                        int foreground) {
        if (attempted || foreground != generation) return;
        Activity activity = resumedActivity();
        if (activity == null) return;
        if (!isDue(lastFinishedAt, Pref.profileAnalyzerAutoRefreshDays(), System.currentTimeMillis())) {
            attempted = true;
            return;
        }
        ProfileAnalyzer.Counts counts = ProfileAnalyzer.countsFor(accountId);
        if (counts == null) {
            // The scan's account-size limit needs current counts, which only the own profile header gives.
            waitingAccountId = accountId;
            return;
        }
        attempted = true;
        ProfileAnalyzer.startAutomaticScan(activity, accountId, counts, dataGeneration);
    }

    /**
     * Main thread, inside Instagram's own profile header bind, once its counts were recorded. Before
     * the wait completed in this foreground stretch, the wait's own check resumes the attempt.
     */
    static void onOwnCounts(String accountId) {
        if (!settled || !accountId.equals(waitingAccountId)) return;
        Activity activity = resumedActivity();
        if (activity == null) return;
        waitingAccountId = null;
        // Read again, since the data may have been deleted or rescanned while waiting.
        readAndStart(activity.getApplicationContext(), accountId, generation);
    }

    /** Any thread, after a scan was stored or data was deleted: the displayed row shows its new next scan. */
    static void onDataChanged() {
        Utils.runOnMainThread(() -> {
            Row current = row.get();
            if (current != null) current.readLastScan();
        });
    }

    /** Main thread: the settings row; its summary shows the interval, the next scan and the risk. */
    public static Preference preference(Context context) {
        Row preference = new Row(context);
        String title = str("spookd_profile_analyzer_auto_refresh");
        preference.setTitle(title);
        preference.setDialogTitle(title);
        preference.setKey(Settings.PROFILE_ANALYZER_AUTO_REFRESH_DAYS.key);
        preference.setDefaultValue(Settings.PROFILE_ANALYZER_AUTO_REFRESH_DAYS.defaultValue);
        return preference;
    }

    private static final class Row extends ListPref {
        // The settings page the row is on.
        final Context host;
        // Main thread; -1 until read, 0 when this account has no scan.
        private long lastFinishedAt = -1;

        Row(Context context) {
            super(context);
            host = context;
        }

        @Override
        protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
            super.onSetInitialValue(restoreValue, defaultValue);
            CharSequence[] entries = new CharSequence[ENTRY_KEYS.length];
            for (int i = 0; i < entries.length; i++) entries[i] = str(ENTRY_KEYS[i]);
            setEntries(entries);
            setEntryValues(DAYS);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            if (row.get() == this) return;
            row = new WeakReference<>(this);
            readLastScan();
        }

        /** Main thread: reads the last scan of the current account off the main thread, then rebinds. */
        void readLastScan() {
            String accountId = ProfileAnalyzer.currentAccountId();
            if (accountId == null) return;
            Context app = getContext().getApplicationContext();
            ProfileVisits.DB_QUEUE.execute(() -> {
                try {
                    SpookdFollowDb.Scan last = SpookdFollowDb.getInstance(app).latestScan(accountId);
                    long finishedAt = last == null ? 0 : last.finishedAt;
                    Utils.runOnMainThread(() -> {
                        lastFinishedAt = finishedAt;
                        notifyChanged();
                    });
                } catch (Exception e) {
                    String error = e.getClass().getSimpleName();
                    Logger.printException(() -> "AutoRefresh: row read failed: " + error);
                }
            });
        }

        @Override
        public CharSequence getSummary() {
            return summary(getContext(), getEntry(), Pref.profileAnalyzerAutoRefreshDays(), lastFinishedAt);
        }
    }

    private static String summary(Context context, CharSequence entry, int days, long lastFinishedAt) {
        StringBuilder out = new StringBuilder();
        if (entry != null) out.append(entry).append('\n');
        if (days > 0 && lastFinishedAt == 0) {
            out.append(str("spookd_profile_analyzer_auto_refresh_first")).append('\n');
        } else if (days > 0 && lastFinishedAt > 0) {
            out.append(isDue(lastFinishedAt, days, System.currentTimeMillis())
                    ? str("spookd_profile_analyzer_auto_refresh_due")
                    : str("spookd_profile_analyzer_auto_refresh_next", DateUtils.formatDateTime(context,
                    lastFinishedAt + days * DAY_MS, DATE_FLAGS))).append('\n');
        }
        return out.append(str("spookd_profile_analyzer_auto_refresh_warning")).toString();
    }
}
