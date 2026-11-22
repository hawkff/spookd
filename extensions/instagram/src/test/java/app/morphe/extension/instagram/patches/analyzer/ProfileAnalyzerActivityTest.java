package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ProfileAnalyzerActivityTest {

    @Test
    public void sectionsComeInScreenOrderWithCountsAndEmptyTexts() {
        FollowUser a = new FollowUser("1", "a", null, false, null);
        FollowUser b = new FollowUser("2", "b", null, false, null);
        PikoFollowDb.Change newer = change(PikoFollowDb.NEW_FOLLOWER, null, "b", null);
        PikoFollowDb.Change older = change(PikoFollowDb.LOST_FOLLOWER, null, "c", null);

        List<Object> rows = ProfileAnalyzerActivity.rows(Arrays.asList(a, b),
                Collections.emptyList(), Collections.singletonList(a), Arrays.asList(newer, older));

        assertEquals(Arrays.asList(
                "spookd_profile_analyzer_not_following_back 2", "1", "2",
                "spookd_profile_analyzer_not_followed_back 0",
                "spookd_profile_analyzer_not_followed_back_empty -1",
                "spookd_profile_analyzer_mutuals 1", "1",
                "spookd_profile_analyzer_history 2", PikoFollowDb.NEW_FOLLOWER,
                PikoFollowDb.LOST_FOLLOWER), describe(rows));
        assertSame(newer, rows.get(8));
    }

    @Test
    public void initialsComeFromTheNameThenTheUsername() {
        assertEquals("JD", ProfileAnalyzerActivity.initials("jane", "Jane  Doe Smith"));
        assertEquals("J", ProfileAnalyzerActivity.initials("x", " jane "));
        assertEquals("X", ProfileAnalyzerActivity.initials("_x9", "\uD83D\uDE00"));
        assertEquals("9", ProfileAnalyzerActivity.initials("._9", null));
        assertEquals("?", ProfileAnalyzerActivity.initials(null, ""));
        assertEquals("?", ProfileAnalyzerActivity.initials("._", null));
    }

    @Test
    public void historyTextFitsTheValuesThatExist() {
        assertEquals("spookd_profile_analyzer_username_changed",
                ProfileAnalyzerActivity.historyKey(PikoFollowDb.USERNAME, "old", "new"));
        assertEquals("spookd_profile_analyzer_username_set",
                ProfileAnalyzerActivity.historyKey(PikoFollowDb.USERNAME, null, "new"));
        assertEquals("spookd_profile_analyzer_name_changed",
                ProfileAnalyzerActivity.historyKey(PikoFollowDb.FULL_NAME, "Bob", "Robert"));
        assertEquals("spookd_profile_analyzer_name_set",
                ProfileAnalyzerActivity.historyKey(PikoFollowDb.FULL_NAME, "", "Robert"));
        assertEquals("spookd_profile_analyzer_name_removed",
                ProfileAnalyzerActivity.historyKey(PikoFollowDb.FULL_NAME, "Bob", ""));
        assertEquals("spookd_profile_analyzer_lost_follower",
                ProfileAnalyzerActivity.historyKey(PikoFollowDb.LOST_FOLLOWER, null, "c"));
        assertEquals("spookd_profile_analyzer_picture_changed",
                ProfileAnalyzerActivity.historyKey(PikoFollowDb.PICTURE, "p1", "p2"));
        assertNull(ProfileAnalyzerActivity.historyKey("other", null, null));
        assertNull(ProfileAnalyzerActivity.historyKey(null, null, null));
    }

    @Test
    public void aRenamedUserWhoLeftAndWasPrunedOpensTheNewestUsername() {
        // Newest first: renamed a to b, then b to c, then stopped following; the identity is pruned.
        PikoFollowDb.Change aToB = change("1", PikoFollowDb.USERNAME, "a", "b", null);
        List<PikoFollowDb.Change> history = Arrays.asList(
                change("1", PikoFollowDb.LOST_FOLLOWER, null, "c", null),
                change("1", PikoFollowDb.USERNAME, "b", "c", null),
                aToB);

        assertEquals("c", ProfileAnalyzerActivity.latestUsernames(history).get(aToB.userId));
    }

    @Test
    public void latestUsernamesPreferTheStoredIdentityThenTheNewestUsernameEvent() {
        FollowUser stored = new FollowUser("3", "z", null, false, null);
        Map<String, String> usernames = ProfileAnalyzerActivity.latestUsernames(Arrays.asList(
                // The new value of a name or picture change is not a username.
                change("2", PikoFollowDb.PICTURE, "p1", "p2", null),
                change("2", PikoFollowDb.USERNAME, "x", "y", null),
                change("3", PikoFollowDb.NEW_FOLLOWER, null, "then", stored),
                change("4", PikoFollowDb.FULL_NAME, "Bob", "Robert", null),
                change("5", PikoFollowDb.LOST_FOLLOWER, null, null, null)));

        assertEquals("y", usernames.get("2"));
        assertEquals("z", usernames.get("3"));
        assertNull(usernames.get("4"));
        assertNull(usernames.get("5"));
    }

    private static PikoFollowDb.Change change(String kind, String oldValue, String newValue,
                                              FollowUser user) {
        return change("1", kind, oldValue, newValue, user);
    }

    private static PikoFollowDb.Change change(String userId, String kind, String oldValue,
                                              String newValue, FollowUser user) {
        return new PikoFollowDb.Change(userId, kind, oldValue, newValue, 1000, user);
    }

    private static List<String> describe(List<Object> rows) {
        List<String> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof ProfileAnalyzerActivity.Label) {
                ProfileAnalyzerActivity.Label label = (ProfileAnalyzerActivity.Label) row;
                out.add(label.key + " " + label.count);
            } else if (row instanceof FollowUser) {
                out.add(((FollowUser) row).id);
            } else {
                out.add(((PikoFollowDb.Change) row).kind);
            }
        }
        return out;
    }
}
