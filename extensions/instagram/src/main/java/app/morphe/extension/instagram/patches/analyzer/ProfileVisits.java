package app.morphe.extension.instagram.patches.analyzer;

import android.content.Context;

import com.instagram.common.session.UserSession;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.entity.ProfileInfo;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Records the profiles each own account opens, for the analyzer's visited list. */
final class ProfileVisits {

    // The header binds again while a profile stays open; one write per profile and minute is enough.
    static final long REBIND_WINDOW_MS = 60_000;
    /** The one serial queue of visit writes and the analyzer screen's database jobs. */
    static final Executor DB_QUEUE = Executors.newSingleThreadExecutor();
    // Main thread.
    private static String lastKey;
    private static long lastAt;

    private ProfileVisits() {
    }

    /** True when this bind is a new visit rather than a rebind of the profile just recorded. */
    static boolean isNewVisit(String previousKey, long previousAt, String key, long now) {
        return !key.equals(previousKey) || now - previousAt >= REBIND_WINDOW_MS || now < previousAt;
    }

    /** Main thread, inside Instagram's profile header bind of another account's profile. */
    static void onVisited(ProfileInfo profile) {
        try {
            UserSession session = profile.getUserSession();
            String accountId = session == null ? null : session.getUserId();
            if (accountId == null || accountId.isEmpty()) return;
            UserData user = profile.getUserData();
            String userId = user.getUserId();
            if (userId == null || userId.isEmpty() || userId.equals(accountId)) return;
            long now = System.currentTimeMillis();
            String key = accountId + ":" + userId;
            if (!isNewVisit(lastKey, lastAt, key, now)) return;
            lastKey = key;
            lastAt = now;
            String username = user.getUsername();
            Context context = Utils.getContext();
            if (context == null) return;
            DB_QUEUE.execute(() -> {
                try {
                    SpookdFollowDb.getInstance(context).recordVisit(accountId, userId, username, now);
                } catch (Exception e) {
                    String error = e.getClass().getSimpleName();
                    Logger.printException(() -> "ProfileVisits: write failed: " + error);
                }
            });
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            Logger.printException(() -> "ProfileVisits: visit failed: " + error);
        }
    }
}
