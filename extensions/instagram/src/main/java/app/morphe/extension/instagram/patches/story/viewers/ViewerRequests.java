package app.morphe.extension.instagram.patches.story.viewers;

import java.util.Objects;

/** Whether a page request Instagram sent for a viewer list is still out. */
final class ViewerRequests {
    // A request that failed leaves no trace, so it counts as out only this long.
    static final long LIMIT_MS = 20_000;

    /** One request: when it was sent and the cursor it asked for. */
    static final class Request {
        final long startedAt;
        final String cursor;

        Request(long startedAt, String cursor) {
            this.startedAt = startedAt;
            this.cursor = cursor;
        }
    }

    private ViewerRequests() {
    }

    /** Out until its page arrives, which moves the cursor, or until {@link #LIMIT_MS} passed. */
    static boolean busy(Request request, String cursor, long now) {
        return request != null && Objects.equals(request.cursor, cursor) && now - request.startedAt < LIMIT_MS;
    }
}
