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
    private static boolean attempted;
    // An account whose due scan waits for the counts of its own profile header.
    private static String waitingAccountId;
    private static SpookdFollowDb.Scan waitingLast;
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
                    Utils.runOnMainThread(() -> startIfStillDue(accountId, last));
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

    /** Main thread: starts the scan unless the app left the screen or the setting changed during the read. */
    private static void startIfStillDue(String accountId, SpookdFollowDb.Scan last) {
        Activity activity = resumedActivity();
        if (activity == null || !isDue(last.finishedAt, Pref.profileAnalyzerAutoRefreshDays(),
                System.currentTimeMillis())) return;
        ProfileAnalyzer.Counts counts = ProfileAnalyzer.countsFor(accountId);
        if (counts == null) {
            // The scan's account-size limit needs current counts, which only the own profile header gives.
            waitingAccountId = accountId;
            waitingLast = last;
            return;
        }
        ProfileAnalyzer.startAutomaticScan(activity, accountId, counts);
    }

    /** Main thread, inside Instagram's own profile header bind, once its counts were recorded. */
    static void onOwnCounts(String accountId) {
        if (!accountId.equals(waitingAccountId)) return;
        SpookdFollowDb.Scan last = waitingLast;
        waitingAccountId = null;
        waitingLast = null;
        // Posted, so the scan does not start inside Instagram's bind.
        Utils.runOnMainThread(() -> startIfStillDue(accountId, last));
    }

    /** Scan worker thread, after a scan was stored: the displayed row shows its new next scan. */
    static void onScanFinished() {
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
        // Main thread; -1 until read, 0 when this account has no scan.
        private long lastFinishedAt = -1;

        Row(Context context) {
            super(context);
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
