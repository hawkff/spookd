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
 * Remembers the own session and counts from the own profile header, starts a scan from the
 * settings row, and shows its progress in the row, a notification and toasts.
 */
public final class ProfileAnalyzer {

    static final String NOTIFICATION_CHANNEL_ID = "piko_profile_analyzer";
    private static final int NOTIFICATION_ID = 0x70f11e;
    // An ongoing notification outlives a killed process; this removes it within minutes.
    private static final long NOTIFICATION_TIMEOUT_MS = 5 * 60 * 1000L;

    static final String ROW_START = "piko_profile_analyzer_start";
    static final String ROW_SCANNING = "piko_profile_analyzer_scanning";
    static final String ROW_PAUSED = "piko_profile_analyzer_paused";
    static final String ROW_NEXT_SCAN = "piko_profile_analyzer_next_scan";
    static final String ROW_OPEN_PROFILE = "piko_profile_analyzer_open_profile";
    static final String ROW_TOO_MANY = "piko_profile_analyzer_too_many";

    private static final class Account {
        final WeakReference<UserSession> session;
        final int followers;
        final int following;

        Account(UserSession session, int followers, int following) {
            this.session = new WeakReference<>(session);
            this.followers = followers;
            this.following = following;
        }
    }

    private static final Map<String, Account> ACCOUNTS = new ConcurrentHashMap<>();
    /** The account whose own profile header bound last. */
    private static volatile String lastAccountId;
    // Main thread.
    private static FollowScan scan;
    private static WeakReference<Preference> row = new WeakReference<>(null);
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

    /** Main thread, inside Instagram's profile header bind: records the own session and counts. */
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
            ACCOUNTS.put(accountId, new Account(session, followers, following));
            lastAccountId = accountId;
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "ProfileAnalyzer: profile header failed: " + error);
        }
    }

    /** Any thread. The own session of this account, if its profile header bound in this process. */
    static UserSession session(String accountId) {
        Account account = accountId == null ? null : ACCOUNTS.get(accountId);
        return account == null ? null : account.session.get();
    }

    /** Main thread, from the settings screen: shows the summary and keeps it current. */
    public static Preference bindRow(Preference preference) {
        row = new WeakReference<>(preference);
        refreshRow();
        return preference;
    }

    /** Main thread, from a tap on the settings row in a resumed activity. */
    public static void onRowClicked(Activity activity) {
        try {
            String accountId = lastAccountId;
            Account account = accountId == null ? null : ACCOUNTS.get(accountId);
            if (account == null || account.session.get() == null) {
                Utils.showToastShort(str(ROW_OPEN_PROFILE));
                return;
            }
            FollowScan engine = scan(activity);
            FollowScan.Refusal refusal = engine.start(accountId, account.followers,
                    account.following, ProfileAnalyzer::onProgress);
            if (refusal == FollowScan.Refusal.TOO_MANY_ACCOUNTS) {
                Utils.showToastLong(str(ROW_TOO_MANY));
            } else if (refusal == FollowScan.Refusal.ALREADY_RUNNING) {
                Utils.showToastShort(str("piko_profile_analyzer_running"));
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

    private static void refreshRow() {
        try {
            Preference preference = row.get();
            if (preference == null) return;
            Context context = preference.getContext();
            String accountId = lastAccountId;
            Account account = accountId == null ? null : ACCOUNTS.get(accountId);
            boolean knowsAccount = account != null && account.session.get() != null;
            boolean tooMany = knowsAccount
                    && (long) account.followers + account.following > FollowScan.MAX_ACCOUNTS;
            // Before the first start only the last start time is read, so no tracking is set up yet.
            FollowScan reader = scan != null ? scan
                    : new FollowScan(context, null, null, null, System::currentTimeMillis, null, null);
            long next = knowsAccount ? reader.nextAllowedStart(accountId) : 0;
            FollowScan.State current;
            int currentList, currentReceived, currentExpected;
            synchronized (ProfileAnalyzer.class) {
                current = state;
                currentList = list;
                currentReceived = received;
                currentExpected = expected;
            }
            String key = rowSummaryKey(current, knowsAccount, tooMany, next);
            String summary;
            if (ROW_SCANNING.equals(key)) {
                summary = str(key, progress(currentList, currentReceived, currentExpected));
            } else if (ROW_NEXT_SCAN.equals(key)) {
                summary = str(key, time(context, next));
            } else {
                summary = str(key);
            }
            preference.setSummary(summary);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "ProfileAnalyzer: row failed: " + error);
        }
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
            Utils.showToastLong(str("piko_profile_analyzer_finished", count(followers), count(received)));
        } else if (state == FollowScan.State.FAILED) {
            Utils.showToastLong(str("piko_profile_analyzer_failed"));
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
                        str("piko_profile_analyzer"), NotificationManager.IMPORTANCE_LOW));
                channelCreated = true;
            }
            int icon = context.getApplicationInfo().icon;
            int max = Math.max(expected, received);
            Notification notification = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_sys_download)
                    .setContentTitle(str("piko_profile_analyzer"))
                    .setContentText(text)
                    .setProgress(max, received, max == 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
                    .build();
            manager.notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "ProfileAnalyzer: notification failed: " + error);
        }
    }

    private static String progress(int list, int received, int expected) {
        return str(list == PikoFollowDb.LIST_FOLLOWERS ? "piko_profile_analyzer_followers_progress"
                : "piko_profile_analyzer_following_progress", count(received), count(expected));
    }

    private static String count(int value) {
        return NumberFormat.getIntegerInstance().format(value);
    }

    private static String time(Context context, long millis) {
        return DateFormat.getTimeFormat(context).format(new Date(millis));
    }
}
