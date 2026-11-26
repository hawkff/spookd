package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class SpookdFollowDbVisitsTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private SpookdFollowDb db;

    @After
    public void tearDown() {
        if (db != null) db.close();
    }

    @Test
    public void upgradesV1WithRowsIntactAndAddsVisits() {
        SQLiteDatabase v1 = context.openOrCreateDatabase(SpookdFollowDb.DB_NAME, Context.MODE_PRIVATE, null);
        for (String statement : SpookdFollowDb.schemaStatements()) v1.execSQL(statement);
        v1.execSQL("INSERT INTO follow_scans (account_id, finished_at, follower_count, following_count)"
                + " VALUES ('1001', 1000, 1, 1)");
        v1.execSQL("INSERT INTO follow_scan_members VALUES (1, 0, '7'), (1, 1, '7')");
        v1.execSQL("INSERT INTO follow_users VALUES ('1001', '7', 'seven', 'Seven', 1, 'pic')");
        v1.execSQL("INSERT INTO follow_changes (account_id, scan_id, user_id, kind, old_value, new_value,"
                + " seen_at) VALUES ('1001', 1, '7', 'username', 'old', 'seven', 1000)");
        v1.setVersion(1);
        List<String> before = dump(v1);
        v1.close();

        db = new SpookdFollowDb(context);
        SQLiteDatabase upgraded = db.getWritableDatabase();
        assertEquals(SpookdFollowDb.DB_VERSION, upgraded.getVersion());
        assertEquals(before, dump(upgraded));
        assertTrue(db.visits("1001").isEmpty());
        db.recordVisit("1001", "8", "eight", 2000);
        assertEquals("eight", db.visits("1001").get(0).username);
    }

    @Test
    public void keepsNewestVisitsPerAccountAndOneRowPerProfile() {
        db = new SpookdFollowDb(context);
        for (int i = 0; i < SpookdFollowDb.KEPT_VISITS + 5; i++) {
            db.recordVisit("1001", String.valueOf(i), "user" + i, 1000 + i);
        }
        db.recordVisit("1001", "0", "renamed", 5000);
        db.recordVisit("2002", "9", "other", 10);

        List<SpookdFollowDb.Visit> visits = db.visits("1001");
        assertEquals(SpookdFollowDb.KEPT_VISITS, visits.size());
        assertEquals("0", visits.get(0).userId);
        assertEquals("renamed", visits.get(0).username);
        assertEquals(5000, visits.get(0).visitedAt);
        // The oldest five, apart from the revisited one, are gone.
        assertEquals("504", visits.get(1).userId);
        assertEquals("6", visits.get(visits.size() - 1).userId);
        assertEquals(1, db.visits("2002").size());

        db.deleteAccount("1001");
        assertTrue(db.visits("1001").isEmpty());
        assertEquals(1, db.visits("2002").size());
    }

    @Test
    public void rebindOfTheSameProfileIsNotANewVisit() {
        assertTrue(ProfileVisits.isNewVisit(null, 0, "1:2", 1000));
        assertFalse(ProfileVisits.isNewVisit("1:2", 1000, "1:2", 1000 + ProfileVisits.REBIND_WINDOW_MS - 1));
        assertTrue(ProfileVisits.isNewVisit("1:2", 1000, "1:2", 1000 + ProfileVisits.REBIND_WINDOW_MS));
        assertTrue(ProfileVisits.isNewVisit("1:2", 1000, "1:3", 1001));
        assertTrue(ProfileVisits.isNewVisit("1:2", 5000, "1:2", 1000));
    }

    /** Every row of the version 1 tables. */
    private static List<String> dump(SQLiteDatabase db) {
        List<String> out = new ArrayList<>();
        for (String table : new String[]{"follow_scans", "follow_scan_members", "follow_users", "follow_changes"}) {
            try (Cursor rows = db.rawQuery("SELECT * FROM " + table, null)) {
                while (rows.moveToNext()) {
                    StringBuilder row = new StringBuilder(table);
                    for (int i = 0; i < rows.getColumnCount(); i++) {
                        row.append('|').append(rows.isNull(i) ? "NULL" : rows.getString(i));
                    }
                    out.add(row.toString());
                }
            }
        }
        return out;
    }
}
