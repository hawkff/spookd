package app.morphe.extension.instagram.patches.analyzer;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.preference.Preference;
import android.text.format.DateFormat;

import com.instagram.common.session.UserSession;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.instagram.entity.ProfileInfo;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Follows the current session, remembers each own account's counts from its profile header,
 * starts a scan from the results screen, and shows its progress in the settings row, the results
 * screen, a notification and toasts.
 */
public final class ProfileAnalyzer {

    static final String NOTIFICATION_CHANNEL_ID = "piko_profile_analyzer";
    private static final int NOTIFICATION_ID = 0x70f11e;

    static final String ROW_START = "spookd_profile_analyzer_start";
    static final String ROW_SCANNING = "spookd_profile_analyzer_scanning";
    static final String ROW_PAUSED = "spookd_profile_analyzer_paused";
    static final String ROW_NEXT_SCAN = "spookd_profile_analyzer_next_scan";
    static final String ROW_OPEN_PROFILE = "spookd_profile_analyzer_open_profile";
    static final String ROW_TOO_MANY = "spookd_profile_analyzer_too_many";

    /** Follower and following counts from an own profile header. */
    static final class Counts {
        final int followers;
        final int following;

        Counts(int followers, int following) {
            this.followers = followers;
            this.following = following;
        }
    }

    private static final Map<String, Counts> COUNTS = new ConcurrentHashMap<>();
    private static volatile WeakReference<UserSession> currentSession = new WeakReference<>(null);
    // Main thread.
    private static FollowScan scan;
    private static WeakReference<Preference> row = new WeakReference<>(null);
    private static WeakReference<Runnable> screenRefresh = new WeakReference<>(null);
    private static long refreshScheduledFor;
    // Guarded by ProfileAnalyzer.class; written on the scan worker.
    private static FollowScan.State state;
    private static int list;
    private static int received;
    private static int expected;
    private static int followersReceived;
    // Scan worker.
    private static boolean channelCreated;

    private ProfileAnalyzer() {
    }

    /** Called just before Instagram's session manager returns the session it made current. */
    public static void onCurrentSession(UserSession session) {
        currentSession = new WeakReference<>(session);
    }

    /** App start, from SettingsStatus.load(): removes a progress notification a killed process left. */
    public static void onAppStart() {
        updateNotification(null, 0, 0);
    }

    /** Main thread, inside Instagram's profile header bind: records the own account's counts. */
    public static void onProfileHeaderBound(Object profileInfoObject) {
        if (!SettingsStatus.profileAnalyzer) return;
        try {
            ProfileInfo profile = new ProfileInfo(profileInfoObject);
            if (!profile.isSelfProfile()) return;
            UserSession session = profile.getUserSession();
            String accountId = session == null ? null : session.getUserId();
            if (accountId == null || accountId.isEmpty()) return;
            UserData user = profile.getUserData();
            Integer followers = user.getFollowerCount();
            Integer following = user.getFollowingCount();
            if (followers == null || following == null) return;
            recordCounts(accountId, followers, following);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "ProfileAnalyzer: profile header failed: " + error);
        }
    }

    static void recordCounts(String accountId, int followers, int following) {
        COUNTS.put(accountId, new Counts(followers, following));
    }

    /** The counts of this account only, or null until its own profile header bound in this process. */
    static Counts countsFor(String accountId) {
        return accountId == null ? null : COUNTS.get(accountId);
    }

    static String currentAccountId() {
        UserSession session = currentSession.get();
        String accountId = session == null ? null : session.getUserId();
        return accountId == null || accountId.isEmpty() ? null : accountId;
    }

    /** Any thread. The current session, if it belongs to this account. */
    static UserSession session(String accountId) {
        UserSession session = currentSession.get();
        return session != null && accountId != null && accountId.equals(session.getUserId())
                ? session : null;
    }

    /** Main thread, from the row's view binding: keeps the displayed row current. */
    public static void onRowDisplayed(Preference preference) {
        if (row.get() == preference) return;
        row = new WeakReference<>(preference);
        // Posted, since a summary change inside the bind would rebind the list.
        Utils.runOnMainThread(ProfileAnalyzer::refreshRow);
    }

    /** Main thread, from the resumed results screen: runs refresh whenever the row refreshes. */
    static void onScreenShown(Runnable refresh) {
        screenRefresh = new WeakReference<>(refresh);
    }

    /** Main thread, from a paused or destroyed results screen: frees the slot if that screen owns it. */
    static void onScreenHidden(Runnable refresh) {
        if (screenRefresh.get() == refresh) screenRefresh = new WeakReference<>(null);
    }

    /** Any thread. True from a start until the scan reported how it ended. */
    static boolean isScanning() {
        synchronized (ProfileAnalyzer.class) {
            return state == FollowScan.State.RUNNING || state == FollowScan.State.PAUSED
                    || state == FollowScan.State.BACKING_OFF;
        }
    }

    /** Main thread, from the 'Start scan' action of the results screen in a resumed activity. */
    static void startScan(Activity activity) {
        try {
            String accountId = currentAccountId();
            Counts counts = countsFor(accountId);
            if (counts == null) {
                Utils.showToastShort(str(ROW_OPEN_PROFILE));
                return;
            }
            FollowScan engine = scan(activity);
            FollowScan.Refusal refusal;
            // Held across the start, so the worker's first report comes after this state.
            synchronized (ProfileAnalyzer.class) {
                refusal = engine.start(accountId, counts.followers, counts.following,
                        ProfileAnalyzer::onProgress);
                if (refusal == null) {
                    state = FollowScan.State.RUNNING;
                    list = PikoFollowDb.LIST_FOLLOWERS;
                    received = 0;
                    expected = counts.followers;
                }
            }
            if (refusal == FollowScan.Refusal.TOO_MANY_ACCOUNTS) {
                Utils.showToastLong(str(ROW_TOO_MANY));
            } else if (refusal == FollowScan.Refusal.ALREADY_RUNNING) {
                Utils.showToastShort(str("spookd_profile_analyzer_running"));
            } else if (refusal == FollowScan.Refusal.TOO_SOON) {
                Utils.showToastLong(str(ROW_NEXT_SCAN,
                        time(activity, engine.nextAllowedStart(accountId))));
            }
            refreshRow();
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "ProfileAnalyzer: start failed: " + error);
        }
    }

    private static FollowScan scan(Activity activity) {
        if (scan == null) {
            Context context = activity.getApplicationContext();
            scan = new FollowScan(context, PikoFollowDb.getInstance(context), new FollowScanRequest(),
                    FollowScan.Foreground.get(activity), System::currentTimeMillis, Thread::sleep,
                    new Random());
        }
        return scan;
    }

    /** Main thread. When this account may start its next scan, or 0. */
    private static long nextAllowedStart(Context context, String accountId) {
        // Before the first start only the last start time is read, so no tracking is set up yet.
        FollowScan reader = scan != null ? scan
                : new FollowScan(context, null, null, null, System::currentTimeMillis, null, null);
        return reader.nextAllowedStart(accountId);
    }

    /** The key of the row summary; a running scan shows first, then why a start would be refused. */
    static String rowSummaryKey(FollowScan.State state, boolean knowsAccount, boolean tooMany,
                                long nextAllowedStart) {
        if (state == FollowScan.State.PAUSED) return ROW_PAUSED;
        if (state == FollowScan.State.RUNNING || state == FollowScan.State.BACKING_OFF) {
            return ROW_SCANNING;
        }
        if (!knowsAccount) return ROW_OPEN_PROFILE;
        if (tooMany) return ROW_TOO_MANY;
        return nextAllowedStart != 0 ? ROW_NEXT_SCAN : ROW_START;
    }

    /** Main thread: the row summary for the current account. */
    public static String rowSummary(Context context) {
        try {
            String accountId = currentAccountId();
            Counts counts = countsFor(accountId);
            boolean tooMany = counts != null
                    && (long) counts.followers + counts.following > FollowScan.MAX_ACCOUNTS;
            long next = counts == null ? 0 : nextAllowedStart(context, accountId);
            FollowScan.State current;
            int currentList, currentReceived, currentExpected;
            synchronized (ProfileAnalyzer.class) {
                current = state;
                currentList = list;
                currentReceived = received;
                currentExpected = expected;
            }
            String key = rowSummaryKey(current, counts != null, tooMany, next);
            if (ROW_SCANNING.equals(key)) {
                return str(key, progress(currentList, currentReceived, currentExpected));
            }
            if (ROW_NEXT_SCAN.equals(key)) {
                scheduleRefresh(next);
                return str(key, time(context, next));
            }
            return str(key);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "ProfileAnalyzer: row failed: " + error);
            return "";
        }
    }

    /** Main thread: refreshes the displayed row once the cooldown has passed. */
    private static void scheduleRefresh(long at) {
        if ((row.get() == null && screenRefresh.get() == null) || refreshScheduledFor == at) return;
        refreshScheduledFor = at;
        // The refresh holds no row; it reads the weak reference when it runs.
        Utils.runOnMainThreadDelayed(() -> {
            refreshScheduledFor = 0;
            refreshRow();
        }, Math.max(0, at - System.currentTimeMillis()) + 500);
    }

    private static void refreshRow() {
        Preference preference = row.get();
        if (preference != null) preference.setSummary(rowSummary(preference.getContext()));
        Runnable screen = screenRefresh.get();
        if (screen != null) screen.run();
    }

    /** Scan worker thread. */
    private static void onProgress(int list, int received, int expected, FollowScan.State state) {
        int followers;
        synchronized (ProfileAnalyzer.class) {
            if (list == PikoFollowDb.LIST_FOLLOWERS) followersReceived = received;
            ProfileAnalyzer.list = list;
            ProfileAnalyzer.received = received;
            ProfileAnalyzer.expected = expected;
            ProfileAnalyzer.state = state;
            followers = followersReceived;
        }
        if (state == FollowScan.State.FINISHED) {
            Utils.showToastLong(str("spookd_profile_analyzer_finished", count(followers), count(received)));
        } else if (state == FollowScan.State.FAILED) {
            Utils.showToastLong(str("spookd_profile_analyzer_failed"));
        }
        String text = null;
        if (state == FollowScan.State.PAUSED) {
            text = str(ROW_PAUSED);
        } else if (state == FollowScan.State.RUNNING || state == FollowScan.State.BACKING_OFF) {
            text = progress(list, received, expected);
        }
        updateNotification(text, received, expected);
        Utils.runOnMainThread(ProfileAnalyzer::refreshRow);
    }

    /** Shows the progress, or removes the notification when text is null. Nothing without permission. */
    private static void updateNotification(String text, int received, int expected) {
        try {
            Context context = Utils.getContext();
            NotificationManager manager = context == null ? null
                    : (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (text == null) {
                manager.cancel(NOTIFICATION_ID);
                return;
            }
            if (!manager.areNotificationsEnabled()) return;
            if (!channelCreated) {
                manager.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                        str("spookd_profile_analyzer"), NotificationManager.IMPORTANCE_LOW));
                channelCreated = true;
            }
            int icon = context.getApplicationInfo().icon;
            int max = Math.max(expected, received);
            Notification notification = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_sys_download)
                    .setContentTitle(str("spookd_profile_analyzer"))
                    .setContentText(text)
                    .setProgress(max, received, max == 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
            manager.notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "ProfileAnalyzer: notification failed: " + error);
        }
    }

    private static String progress(int list, int received, int expected) {
        return str(list == PikoFollowDb.LIST_FOLLOWERS ? "spookd_profile_analyzer_followers_progress"
                : "spookd_profile_analyzer_following_progress", count(received), count(expected));
    }

    static String count(int value) {
        return NumberFormat.getIntegerInstance().format(value);
    }

    private static String time(Context context, long millis) {
        return DateFormat.getTimeFormat(context).format(new Date(millis));
    }
}
