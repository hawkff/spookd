package app.morphe.extension.instagram.patches.analyzer;

import com.instagram.common.session.UserSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.shared.Logger;

/**
 * Fetches a page of the own followers or following with the GraphQL request Instagram's own list
 * screens send, which adds the session itself. The native stubs are rewritten at patch time.
 */
public final class FollowScanRequest implements PageSource {

    static final long FETCH_TIMEOUT_MS = 30_000L;
    static final String FETCH_THREAD_NAME = "piko-analyzer-fetch";
    // Cached, so a request that ignores the cancel cannot hold back the next page.
    private static final ExecutorService FETCHER = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, FETCH_THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    private interface Getter<T> {
        T get() throws Exception;
    }

    /** Scan worker thread: the app's inline runner refuses the main thread. */
    @Override
    public Page fetchPage(String accountId, int list, String maxId, String rankToken) {
        try {
            UserSession session = ProfileAnalyzer.session(accountId);
            if (session == null || !accountId.equals(session.getUserId())) return null;
            boolean followers = list == PikoFollowDb.LIST_FOLLOWERS;
            Object page = withTimeout(() -> followers
                    ? fetchFollowersNative(session, accountId, maxId, rankToken)
                    : fetchFollowingNative(session, accountId, maxId, rankToken), FETCH_TIMEOUT_MS);
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

    /**
     * Scan worker thread. Runs {@code fetch} on a helper thread, since the app's request waits for its
     * callback without a time limit, and returns its result, or null after {@code timeoutMs} or a failure.
     */
    static <T> T withTimeout(Callable<T> fetch, long timeoutMs) {
        Future<T> future = FETCHER.submit(fetch);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            Logger.printInfo(() -> "FollowScanRequest: TimeoutException");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            Logger.printInfo(() -> "FollowScanRequest: InterruptedException");
        } catch (ExecutionException e) {
            String error = e.getCause() == null ? "ExecutionException" : e.getCause().getClass().getSimpleName();
            Logger.printInfo(() -> "FollowScanRequest failed: " + error);
        }
        return null;
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

    /** Builds the app's uncached list request, runs it inline and returns the list model, else null. */
    private static Object fetchFollowersNative(Object session, String userId, String maxId,
                                               String rankToken) {
        return null;
    }

    private static Object fetchFollowingNative(Object session, String userId, String maxId,
                                               String rankToken) {
        return null;
    }

    private static List<?> pageUsersNative(Object page) {
        return null;
    }

    private static String pageNextMaxIdNative(Object page) {
        return null;
    }
}
