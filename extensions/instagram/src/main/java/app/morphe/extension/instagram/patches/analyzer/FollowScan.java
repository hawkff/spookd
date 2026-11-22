package app.morphe.extension.instagram.patches.analyzer;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

import app.morphe.extension.shared.Logger;

/**
 * Pages through the own followers, then the own following, on its own worker thread, and stores
 * both as one snapshot only when both lists finished.
 */
public final class FollowScan {

    public static final int MAX_ACCOUNTS = 13_000;
    public static final long MIN_START_INTERVAL_MS = 60 * 60 * 1000L;
    static final String PREFS_NAME = "spookd_profile_analyzer";
    static final long[] BACKOFF_MS = {10_000L, 20_000L, 40_000L};
    static final int MIN_PAGE_WAIT_MS = 2_000;
    static final int MAX_PAGE_WAIT_MS = 4_000;
    /** An empty last page ends a list only once this share of the profile's count arrived. */
    static final int COMPLETE_PERCENT = 90;

    public enum Refusal { TOO_MANY_ACCOUNTS, ALREADY_RUNNING, TOO_SOON }

    public enum State { RUNNING, PAUSED, BACKING_OFF, FINISHED, FAILED, STOPPED }

    public interface Sleeper {
        /** Must throw when the calling thread is interrupted. */
        void sleep(long millis) throws InterruptedException;
    }

    public interface Listener {
        /** Called on the scan worker thread; FINISHED, FAILED and STOPPED come last, once. */
        void onProgress(int list, int received, int expected, State state);
    }

    private final SharedPreferences prefs;
    private final PikoFollowDb db;
    private final PageSource source;
    private final Foreground foreground;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final Random random;

    // Guarded by this.
    private Thread worker;
    private boolean fetching;
    private boolean committed;
    private volatile boolean stopped;

    public FollowScan(Context context, PikoFollowDb db, PageSource source, Foreground foreground,
                      LongSupplier clock, Sleeper sleeper, Random random) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.db = db;
        this.source = source;
        this.foreground = foreground;
        this.clock = clock;
        this.sleeper = sleeper;
        this.random = random;
    }

    static String lastStartKey(String accountId) {
        return "last_scan_start_" + accountId;
    }

    /** When the next scan of this account may start, or 0 when it may start now. */
    public long nextAllowedStart(String accountId) {
        long last = prefs.getLong(lastStartKey(accountId), 0);
        long now = clock.getAsLong();
        // A clock set back before the last start does not block scans.
        boolean tooSoon = last > 0 && now >= last && now - last < MIN_START_INTERVAL_MS;
        return tooSoon ? last + MIN_START_INTERVAL_MS : 0;
    }

    /** Any thread. Returns null when the scan started, otherwise why it was refused. */
    public synchronized Refusal start(String accountId, int followerCount, int followingCount,
                                      Listener listener) {
        if ((long) followerCount + followingCount > MAX_ACCOUNTS) return Refusal.TOO_MANY_ACCOUNTS;
        if (worker != null) return Refusal.ALREADY_RUNNING;
        if (nextAllowedStart(accountId) != 0) return Refusal.TOO_SOON;
        prefs.edit().putLong(lastStartKey(accountId), clock.getAsLong()).apply();
        stopped = false;
        committed = false;
        worker = new Thread(() -> run(accountId, followerCount, followingCount, listener),
                "piko-follow-scan");
        worker.start();
        return null;
    }

    public synchronized boolean isRunning() {
        return worker != null;
    }

    /** Any thread. The scan ends after the page in flight and stores nothing. */
    public synchronized void stop() {
        if (worker == null || committed) return;
        stopped = true;
        // Never interrupt the app's own request code; the worker checks the flag after it.
        if (!fetching) worker.interrupt();
    }

    private void run(String accountId, int followerCount, int followingCount, Listener listener) {
        String rankToken = UUID.randomUUID().toString();
        Map<String, FollowUser> followers = new LinkedHashMap<>();
        Map<String, FollowUser> following = new LinkedHashMap<>();
        int list = PikoFollowDb.LIST_FOLLOWERS;
        State outcome = State.FAILED;
        try {
            if (scanList(accountId, list, followerCount, rankToken, followers, listener)) {
                list = PikoFollowDb.LIST_FOLLOWING;
                if (scanList(accountId, list, followingCount, rankToken, following, listener)) {
                    checkStopped();
                    long scanId = db.commitScan(accountId, clock.getAsLong(), followers.values(),
                            following.values(), this::mayCommit);
                    outcome = scanId == -1 ? State.STOPPED : State.FINISHED;
                }
            }
        } catch (InterruptedException e) {
            outcome = State.STOPPED;
        } catch (Throwable t) {
            String error = t.getClass().getSimpleName();
            Logger.printException(() -> "FollowScan failed: " + error);
        }
        // Still running until the last report returned, so no other scan's reports come in between.
        try {
            State end = outcome;
            int followerRows = followers.size();
            int followingRows = following.size();
            Logger.printInfo(() -> "FollowScan " + end + ": followers " + followerRows
                    + ", following " + followingRows);
            boolean onFollowers = list == PikoFollowDb.LIST_FOLLOWERS;
            report(listener, list, onFollowers ? followerRows : followingRows,
                    onFollowers ? followerCount : followingCount, end);
        } finally {
            synchronized (this) {
                worker = null;
            }
        }
    }

    /** Returns true when the list finished, false after the fourth throttle in a row. */
    private boolean scanList(String accountId, int list, int expected, String rankToken,
                             Map<String, FollowUser> staged, Listener listener)
            throws InterruptedException {
        String cursor = null;
        int throttles = 0;
        report(listener, list, 0, expected, State.RUNNING);
        while (true) {
            // Before each request, since a wait may have ended in the background.
            pauseInBackground(listener, list, staged.size(), expected);
            PageSource.Page page = fetch(accountId, list, cursor, rankToken);
            pauseInBackground(listener, list, staged.size(), expected);
            if (isThrottle(page, cursor, staged.size(), expected)) {
                if (throttles == BACKOFF_MS.length) {
                    Logger.printInfo(() -> "FollowScan: throttled " + (BACKOFF_MS.length + 1)
                            + " times in a row in list " + list);
                    return false;
                }
                report(listener, list, staged.size(), expected, State.BACKING_OFF);
                sleeper.sleep(BACKOFF_MS[throttles++]);
                continue;
            }
            throttles = 0;
            for (FollowUser user : page.users) {
                if (user != null && user.id != null) staged.put(user.id, user);
            }
            cursor = page.nextMaxId;
            int pageRows = page.users.size();
            Logger.printInfo(() -> "FollowScan page: list " + list + ", rows " + pageRows);
            report(listener, list, staged.size(), expected, State.RUNNING);
            if (cursor == null && list == PikoFollowDb.LIST_FOLLOWING) return true;
            sleeper.sleep(MIN_PAGE_WAIT_MS + random.nextInt(MAX_PAGE_WAIT_MS - MIN_PAGE_WAIT_MS + 1));
            if (cursor == null) return true;
        }
    }

    private void pauseInBackground(Listener listener, int list, int received, int expected)
            throws InterruptedException {
        if (foreground.isResumed()) return;
        report(listener, list, received, expected, State.PAUSED);
        foreground.awaitResumed();
        report(listener, list, received, expected, State.RUNNING);
    }

    /** Inside the commit transaction: a stop that came first rolls it back, a later one does nothing. */
    private synchronized boolean mayCommit() {
        if (stopped) return false;
        committed = true;
        return true;
    }

    private static boolean isThrottle(PageSource.Page page, String cursor, int received, int expected) {
        if (page == null || page.users == null) return true;
        // An empty page only ends a list, with no cursor, once nearly everything expected has arrived.
        if (page.users.isEmpty()) {
            return page.nextMaxId != null || received * 100L < (long) expected * COMPLETE_PERCENT;
        }
        return page.nextMaxId != null && page.nextMaxId.equals(cursor);
    }

    private PageSource.Page fetch(String accountId, int list, String cursor, String rankToken)
            throws InterruptedException {
        synchronized (this) {
            checkStopped();
            fetching = true;
        }
        PageSource.Page page;
        try {
            page = source.fetchPage(accountId, list, cursor, rankToken);
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName();
            Logger.printInfo(() -> "FollowScan page failed: " + error);
            page = null;
        } finally {
            synchronized (this) {
                fetching = false;
            }
        }
        checkStopped();
        return page;
    }

    private void checkStopped() throws InterruptedException {
        if (stopped) throw new InterruptedException();
    }

    private static void report(Listener listener, int list, int received, int expected, State state) {
        if (listener == null) return;
        try {
            listener.onProgress(list, received, expected, state);
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "FollowScan listener failed: " + error);
        }
    }

    /** Counts resumed activities of the app; a scan waits before its next page while none is. */
    public static final class Foreground {
        private static Foreground instance;
        private int resumed;

        /** Main thread, from a resumed activity: tracks every activity of the app from now on. */
        public static synchronized Foreground get(Activity current) {
            if (instance == null) {
                Foreground tracker = new Foreground();
                tracker.resumed = 1;
                current.getApplication().registerActivityLifecycleCallbacks(
                        new Application.ActivityLifecycleCallbacks() {
                            @Override public void onActivityResumed(Activity activity) { tracker.onResumed(); }
                            @Override public void onActivityPaused(Activity activity) { tracker.onPaused(); }
                            @Override public void onActivityCreated(Activity a, Bundle b) { }
                            @Override public void onActivityStarted(Activity a) { }
                            @Override public void onActivityStopped(Activity a) { }
                            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                            @Override public void onActivityDestroyed(Activity a) { }
                        });
                instance = tracker;
            }
            return instance;
        }

        synchronized void onResumed() {
            resumed++;
            notifyAll();
        }

        synchronized void onPaused() {
            if (resumed > 0) resumed--;
        }

        public synchronized boolean isResumed() {
            return resumed > 0;
        }

        /** Blocks until an activity resumes; stopping the scan interrupts the wait. */
        synchronized void awaitResumed() throws InterruptedException {
            while (resumed == 0) wait();
        }
    }
}
