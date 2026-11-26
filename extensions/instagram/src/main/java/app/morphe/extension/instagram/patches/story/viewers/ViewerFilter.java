package app.morphe.extension.instagram.patches.story.viewers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Which loaded story viewers match a search and a filter. */
final class ViewerFilter {
    static final int ALL = 0;
    static final int FOLLOWERS = 1;
    static final int NOT_FOLLOWING_BACK = 2;
    static final int STARRED = 3;

    /** One viewer as Instagram's list holds it; any field may be null. */
    static final class Viewer {
        final String id;
        final String username;
        final String fullName;

        Viewer(String id, String username, String fullName) {
            this.id = id;
            this.username = username;
            this.fullName = fullName;
        }
    }

    private ViewerFilter() {
    }

    /** The viewers in {@code filter} whose username or name contains the query, in list order. */
    static List<Viewer> filter(List<Viewer> viewers, String query, int filter, Set<String> followers,
                               Set<String> notFollowingBack, Set<String> starred) {
        String needle = needle(query);
        List<Viewer> out = new ArrayList<>();
        for (Viewer viewer : viewers) {
            Set<String> group = filter == FOLLOWERS ? followers
                    : filter == NOT_FOLLOWING_BACK ? notFollowingBack
                    : filter == STARRED ? starred : null;
            if (group != null && (viewer.id == null || !group.contains(viewer.id))) continue;
            if (!needle.isEmpty() && !contains(viewer.username, needle) && !contains(viewer.fullName, needle)) {
                continue;
            }
            out.add(viewer);
        }
        return out;
    }

    /** The query without spaces around it or a leading @, in lower case. */
    static String needle(String query) {
        String needle = query == null ? "" : query.trim();
        if (needle.startsWith("@")) needle = needle.substring(1);
        return needle.toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }
}
