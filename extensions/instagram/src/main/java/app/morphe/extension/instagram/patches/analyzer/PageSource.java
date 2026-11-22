package app.morphe.extension.instagram.patches.analyzer;

import java.util.List;

/** Fetches one page of the own followers or following list. Called on the scan worker thread. */
public interface PageSource {

    /**
     * @param list      {@link PikoFollowDb#LIST_FOLLOWERS} or {@link PikoFollowDb#LIST_FOLLOWING}
     * @param maxId     cursor from the previous page, or null for the first page
     * @param rankToken one random token per scan
     * @return the page, or null for any outcome that is not a success page (a throttle)
     */
    Page fetchPage(String accountId, int list, String maxId, String rankToken);

    final class Page {
        public final List<FollowUser> users;
        /** Null when this was the last page. */
        public final String nextMaxId;

        public Page(List<FollowUser> users, String nextMaxId) {
            this.users = users;
            this.nextMaxId = nextMaxId;
        }
    }
}
