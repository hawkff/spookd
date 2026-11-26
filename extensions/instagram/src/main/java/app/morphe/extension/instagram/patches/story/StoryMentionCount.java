/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import java.util.HashSet;
import java.util.Set;

/** The number on the story mentions menu entry. */
final class StoryMentionCount {
    private StoryMentionCount() {
    }

    /** Distinct people among mention records, by nonempty user id; one person can be mentioned twice. */
    static int people(Iterable<String> userIds) {
        Set<String> distinct = new HashSet<>();
        for (String userId : userIds) {
            if (userId != null && !userId.isEmpty()) distinct.add(userId);
        }
        return distinct.size();
    }
}
