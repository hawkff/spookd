package app.morphe.extension.instagram.patches.analyzer;

import com.instagram.common.session.UserSession;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.shared.Logger;

/**
 * Fetches a page of the own followers or following with Instagram's own request, which adds the
 * session itself. The native stubs are rewritten at patch time.
 */
public final class FollowScanRequest implements PageSource {

    private interface Getter<T> {
        T get() throws Exception;
    }

    /** Scan worker thread: the app's inline runner refuses the main thread. */
    @Override
    public Page fetchPage(String accountId, int list, String maxId, String rankToken) {
        try {
            UserSession session = ProfileAnalyzer.session(accountId);
            if (session == null || !accountId.equals(session.getUserId())) return null;
            String path = "friendships/" + accountId
                    + (list == PikoFollowDb.LIST_FOLLOWERS ? "/followers/" : "/following/");
            Object page = fetchPageNative(session, path, maxId, rankToken);
            List<?> users = page == null ? null : pageUsersNative(page);
            if (users == null) {
                Logger.printInfo(() -> "FollowScanRequest: no page");
                return null;
            }
            List<FollowUser> rows = new ArrayList<>(users.size());
            for (Object user : users) {
                FollowUser row = toFollowUser(user);
                if (row != null) rows.add(row);
            }
            String next = pageNextMaxIdNative(page);
            return new Page(rows, next == null || next.isEmpty() ? null : next);
        } catch (Throwable t) {
            String error = t.getClass().getSimpleName();
            Logger.printInfo(() -> "FollowScanRequest failed: " + error);
            return null;
        }
    }

    private static FollowUser toFollowUser(Object user) {
        if (user == null) return null;
        UserData data = new UserData(user);
        return followUser(orNull(data::getUserId), orNull(data::getUsername),
                orNull(data::getRawFullName), Boolean.TRUE.equals(orNull(data::isVerified)),
                orNull(data::getProfilePicId));
    }

    /** Null without an id; an empty full name is missing. */
    static FollowUser followUser(String id, String username, String rawFullName, boolean verified,
                                 String picId) {
        if (id == null || id.isEmpty()) return null;
        String fullName = rawFullName == null || rawFullName.isEmpty() ? null : rawFullName;
        return new FollowUser(id, username, fullName, verified, picId);
    }

    private static <T> T orNull(Getter<T> getter) {
        try {
            return getter.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** Builds the app's follow-list request, runs it inline and returns the parsed page, else null. */
    private static Object fetchPageNative(Object session, String path, String maxId, String rankToken) {
        return null;
    }

    private static List<?> pageUsersNative(Object page) {
        return null;
    }

    private static String pageNextMaxIdNative(Object page) {
        return null;
    }
}
