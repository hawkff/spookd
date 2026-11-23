package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class SpookdFollowDbResultsTest {
    private static final String A = "1001";
    private static final String B = "2002";

    private SpookdFollowDb db;

    @Before
    public void setUp() {
        db = new SpookdFollowDb(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void latestScanIsPerAccount() {
        assertNull(db.latestScan(A));
        commit(A, 1000, Arrays.asList(user("1", "a"), user("2", "b")), Collections.emptyList());
        commit(A, 2000, Collections.singletonList(user("1", "a")),
                Collections.singletonList(user("3", "c")));

        SpookdFollowDb.Scan scan = db.latestScan(A);
        assertEquals(2000, scan.finishedAt);
        assertEquals(1, scan.followerCount);
        assertEquals(1, scan.followingCount);
        assertNull(db.latestScan(B));
    }

    @Test
    public void historyIsNewestFirstWithTheLatestIdentity() {
        commit(A, 1000, Arrays.asList(user("1", "a"), user("2", "b")), Collections.emptyList());
        commit(A, 2000, Collections.singletonList(user("1", "a")), Collections.emptyList());
        commit(A, 3000, Collections.singletonList(
                new FollowUser("1", "a2", "Name", true, null)), Collections.emptyList());

        List<SpookdFollowDb.Change> history = db.history(A);
        assertEquals(3, history.size());
        // Within one scan the name change is recorded after the username change.
        assertEquals(SpookdFollowDb.FULL_NAME, history.get(0).kind);
        assertEquals("Name", history.get(0).user.fullName);
        assertTrue(history.get(0).user.verified);
        assertEquals(SpookdFollowDb.USERNAME, history.get(1).kind);
        assertEquals("a2", history.get(1).user.username);
        // The lost follower is still in a kept scan, so its identity is still stored.
        assertEquals(SpookdFollowDb.LOST_FOLLOWER, history.get(2).kind);
        assertEquals("b", history.get(2).user.username);
    }

    @Test
    public void historyRowsOutliveTheIdentity() {
        commit(A, 1, Arrays.asList(user("1", "a"), user("9", "z")), Collections.emptyList());
        for (int i = 2; i <= 12; i++) {
            commit(A, i, Collections.singletonList(user("1", "a")), Collections.emptyList());
        }

        List<SpookdFollowDb.Change> history = db.history(A);
        assertEquals(1, history.size());
        assertNull(history.get(0).user);
        assertEquals("z", history.get(0).newValue);
    }

    private void commit(String account, long at, List<FollowUser> followers,
                        List<FollowUser> following) {
        db.commitScan(account, at, followers, following, () -> true);
    }

    private static FollowUser user(String id, String username) {
        return new FollowUser(id, username, null, false, null);
    }
}
