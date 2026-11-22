package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class PikoFollowDbTest {
    private static final String A = "1001";
    private static final String B = "2002";

    private Context context;
    private PikoFollowDb db;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // A fresh helper per test; the singleton would keep an earlier test's context.
        db = new PikoFollowDb(context);
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void baselineScanWritesNoChanges() {
        db.commitScan(A, 1000, users("1", "2"), users("2", "3"));

        assertTrue(db.history(A).isEmpty());
        assertEquals(Arrays.asList("3"), ids(db.notFollowingBack(A)));
        assertEquals(Arrays.asList("1"), ids(db.followersNotFollowedBack(A)));
        assertEquals(Arrays.asList("2"), ids(db.mutuals(A)));
        assertEquals(1, count("SELECT COUNT(*) FROM follow_scans WHERE account_id = ?"
                + " AND follower_count = 2 AND following_count = 2", A));
    }

    @Test
    public void listChangesBetweenScans() {
        db.commitScan(A, 1000, users("1", "2"), users("2", "3"));
        db.commitScan(A, 2000, users("2", "4"), users("3", "5"));

        assertEquals(Arrays.asList(
                "lost_follower 1 null user1 2000",
                "new_follower 4 null user4 2000",
                "started_following 5 null user5 2000",
                "stopped_following 2 null user2 2000"), changes(A));
        assertEquals(Arrays.asList("3", "5"), ids(db.notFollowingBack(A)));
        assertEquals(Arrays.asList("2", "4"), ids(db.followersNotFollowedBack(A)));
        assertTrue(db.mutuals(A).isEmpty());
    }

    @Test
    public void identityChangesAreRecordedAndStored() {
        db.commitScan(A, 1000, Collections.singletonList(
                new FollowUser("2", "bob", "Bob", false, "p1")), Collections.emptyList());
        db.commitScan(A, 2000, Collections.singletonList(
                new FollowUser("2", "bobby", "Robert", true, "p2")), Collections.emptyList());
        // A missing picture id keeps the stored one and is not a change.
        db.commitScan(A, 3000, Collections.singletonList(
                new FollowUser("2", "bobby", "Robert", true, null)), Collections.emptyList());

        assertEquals(Arrays.asList(
                "full_name 2 Bob Robert 2000",
                "picture 2 p1 p2 2000",
                "username 2 bob bobby 2000"), changes(A));
        FollowUser stored = db.followersNotFollowedBack(A).get(0);
        assertEquals("bobby", stored.username);
        assertEquals("Robert", stored.fullName);
        assertTrue(stored.verified);
        assertEquals("p2", stored.picId);
    }

    @Test
    public void eleventhScanPrunesOldestAndHistorySurvives() {
        db.commitScan(A, 1, users("1", "9"), users());
        db.commitScan(A, 2, users("1", "2"), users());
        for (int i = 3; i <= 11; i++) db.commitScan(A, i, users("1", "2"), users());

        assertEquals(10, count("SELECT COUNT(*) FROM follow_scans WHERE account_id = ?", A));
        assertEquals(2, count("SELECT MIN(id) FROM follow_scans WHERE account_id = ?", A));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_scan_members WHERE scan_id = 1"));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_users WHERE user_id = '9'"));

        db.commitScan(A, 12, users("1", "2"), users());
        assertEquals(3, count("SELECT MIN(id) FROM follow_scans WHERE account_id = ?", A));
        // Both rows came from scan 2, which is pruned now.
        assertEquals(Arrays.asList("lost_follower 9 null user9 2", "new_follower 2 null user2 2"),
                changes(A));
    }

    @Test
    public void clearHistoryKeepsScans() {
        twoScans(A);
        twoScans(B);

        db.clearHistory(A);

        assertTrue(db.history(A).isEmpty());
        assertEquals(4, db.history(B).size());
        assertEquals(2, count("SELECT COUNT(*) FROM follow_scans WHERE account_id = ?", A));
        assertEquals(Arrays.asList("3", "5"), ids(db.notFollowingBack(A)));
    }

    @Test
    public void deleteAccountLeavesOtherAccounts() {
        twoScans(A);
        twoScans(B);
        List<String> before = changes(B);

        db.deleteAccount(A);

        assertTrue(db.history(A).isEmpty());
        assertTrue(db.notFollowingBack(A).isEmpty());
        assertEquals(0, count("SELECT COUNT(*) FROM follow_scans WHERE account_id = ?", A));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_users WHERE account_id = ?", A));
        assertEquals(count("SELECT COUNT(*) FROM follow_scan_members m JOIN follow_scans s"
                        + " ON s.id = m.scan_id WHERE s.account_id = ?", B),
                count("SELECT COUNT(*) FROM follow_scan_members"));
        assertEquals(before, changes(B));
        assertEquals(Arrays.asList("3", "5"), ids(db.notFollowingBack(B)));
    }

    @Test
    public void deleteAllRemovesTheFile() {
        twoScans(A);
        db.close();

        assertTrue(PikoFollowDb.deleteAll(context));
        assertFalse(context.getDatabasePath(PikoFollowDb.DB_NAME).exists());
    }

    private void twoScans(String account) {
        db.commitScan(account, 1000, users("1", "2"), users("2", "3"));
        db.commitScan(account, 2000, users("2", "4"), users("3", "5"));
    }

    private static List<FollowUser> users(String... ids) {
        List<FollowUser> out = new ArrayList<>();
        for (String id : ids) out.add(new FollowUser(id, "user" + id, "Name " + id, false, "pic" + id));
        return out;
    }

    private static List<String> ids(List<FollowUser> users) {
        List<String> out = new ArrayList<>();
        for (FollowUser user : users) out.add(user.id);
        Collections.sort(out);
        return out;
    }

    /** "kind user old new seenAt", sorted. */
    private List<String> changes(String account) {
        List<String> out = new ArrayList<>();
        for (PikoFollowDb.Change c : db.history(account)) {
            out.add(c.kind + " " + c.userId + " " + c.oldValue + " " + c.newValue + " " + c.seenAt);
        }
        Collections.sort(out);
        return out;
    }

    private long count(String sql, String... args) {
        return DatabaseUtils.longForQuery(db.getReadableDatabase(), sql, args);
    }
}
