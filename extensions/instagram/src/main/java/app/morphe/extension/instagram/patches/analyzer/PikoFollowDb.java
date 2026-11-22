package app.morphe.extension.instagram.patches.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Follower and following snapshots per account, and the history of changes between them. */
public final class PikoFollowDb extends SQLiteOpenHelper {

    static final String DB_NAME = "piko_follow_snapshots.db";
    private static final int DB_VERSION = 1;
    static final int KEPT_SCANS = 10;

    public static final int LIST_FOLLOWERS = 0;
    public static final int LIST_FOLLOWING = 1;

    public static final String NEW_FOLLOWER = "new_follower";
    public static final String LOST_FOLLOWER = "lost_follower";
    public static final String STARTED_FOLLOWING = "started_following";
    public static final String STOPPED_FOLLOWING = "stopped_following";
    public static final String USERNAME = "username";
    public static final String FULL_NAME = "full_name";
    public static final String PICTURE = "picture";

    private static PikoFollowDb instance;

    /** One row of the history, newest first. */
    public static final class Change {
        public final String userId;
        public final String kind;
        public final String oldValue;
        /** The username at that time for list changes, the new value for identity changes. */
        public final String newValue;
        public final long seenAt;

        Change(String userId, String kind, String oldValue, String newValue, long seenAt) {
            this.userId = userId;
            this.kind = kind;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.seenAt = seenAt;
        }
    }

    static String[] schemaStatements() {
        return new String[]{
                "CREATE TABLE follow_scans ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "account_id TEXT NOT NULL,"
                        + "finished_at INTEGER NOT NULL,"
                        + "follower_count INTEGER NOT NULL,"
                        + "following_count INTEGER NOT NULL"
                        + ")",
                "CREATE INDEX idx_scans_account ON follow_scans(account_id, id)",
                "CREATE TABLE follow_scan_members ("
                        + "scan_id INTEGER NOT NULL,"
                        + "list INTEGER NOT NULL,"
                        + "user_id TEXT NOT NULL,"
                        + "PRIMARY KEY (scan_id, list, user_id)"
                        + ") WITHOUT ROWID",
                "CREATE TABLE follow_users ("
                        + "account_id TEXT NOT NULL,"
                        + "user_id TEXT NOT NULL,"
                        + "username TEXT,"
                        + "full_name TEXT,"
                        + "verified INTEGER NOT NULL DEFAULT 0,"
                        + "pic_id TEXT,"
                        + "PRIMARY KEY (account_id, user_id)"
                        + ") WITHOUT ROWID",
                "CREATE TABLE follow_changes ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "account_id TEXT NOT NULL,"
                        + "scan_id INTEGER NOT NULL,"
                        + "user_id TEXT NOT NULL,"
                        + "kind TEXT NOT NULL,"
                        + "old_value TEXT,"
                        + "new_value TEXT,"
                        + "seen_at INTEGER NOT NULL"
                        + ")",
                "CREATE INDEX idx_changes_account ON follow_changes(account_id, id)"
        };
    }

    public static synchronized PikoFollowDb getInstance(Context context) {
        if (instance == null) {
            instance = new PikoFollowDb(context.getApplicationContext());
        }
        return instance;
    }

    /** Deletes the data of every account. Stop a running scan first, or its commit fails. */
    public static synchronized boolean deleteAll(Context context) {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        return context.deleteDatabase(DB_NAME);
    }

    PikoFollowDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        for (String statement : schemaStatements()) {
            db.execSQL(statement);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    /**
     * Stores a finished scan, the changes since the previous scan of this account and the latest
     * identities, then prunes to the newest {@link #KEPT_SCANS} scans, all in one transaction.
     */
    public long commitScan(String accountId, long finishedAt,
                           Collection<FollowUser> followers, Collection<FollowUser> following) {
        Map<String, FollowUser> followerById = byId(followers);
        Map<String, FollowUser> followingById = byId(following);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long previous = latestScanId(db, accountId);
            ContentValues scan = new ContentValues();
            scan.put("account_id", accountId);
            scan.put("finished_at", finishedAt);
            scan.put("follower_count", followerById.size());
            scan.put("following_count", followingById.size());
            long scanId = db.insertOrThrow("follow_scans", null, scan);

            try (SQLiteStatement member = db.compileStatement(
                    "INSERT INTO follow_scan_members (scan_id, list, user_id) VALUES (?, ?, ?)")) {
                insertMembers(member, scanId, LIST_FOLLOWERS, followerById);
                insertMembers(member, scanId, LIST_FOLLOWING, followingById);
            }

            Map<String, String[]> known = knownUsers(db, accountId);
            try (SQLiteStatement change = db.compileStatement("INSERT INTO follow_changes"
                    + " (account_id, scan_id, user_id, kind, old_value, new_value, seen_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)");
                 SQLiteStatement upsert = db.compileStatement("INSERT OR REPLACE INTO follow_users"
                         + " (account_id, user_id, username, full_name, verified, pic_id)"
                         + " VALUES (?, ?, ?, ?, ?, ?)")) {
                Changes changes = new Changes(change, accountId, scanId, finishedAt);
                Map<String, FollowUser> everyone = merge(followerById, followingById);
                if (previous != -1) {
                    listChanges(db, changes, previous, LIST_FOLLOWERS, followerById, everyone,
                            known, NEW_FOLLOWER, LOST_FOLLOWER);
                    listChanges(db, changes, previous, LIST_FOLLOWING, followingById, everyone,
                            known, STARTED_FOLLOWING, STOPPED_FOLLOWING);
                }

                for (FollowUser user : everyone.values()) {
                    String[] old = known.get(user.id);
                    String username = user.username;
                    String fullName = user.fullName;
                    String picId = user.picId;
                    if (old != null) {
                        username = identity(changes, user.id, USERNAME, old[0], username);
                        fullName = identity(changes, user.id, FULL_NAME, old[1], fullName);
                        picId = identity(changes, user.id, PICTURE, old[2], picId);
                    }
                    upsert.clearBindings();
                    upsert.bindString(1, accountId);
                    upsert.bindString(2, user.id);
                    bind(upsert, 3, username);
                    bind(upsert, 4, fullName);
                    upsert.bindLong(5, user.verified ? 1 : 0);
                    bind(upsert, 6, picId);
                    upsert.executeInsert();
                }
            }

            prune(db, accountId);
            db.setTransactionSuccessful();
            return scanId;
        } finally {
            db.endTransaction();
        }
    }

    /** Following accounts that do not follow back, from the latest scan. */
    public List<FollowUser> notFollowingBack(String accountId) {
        return latestMembers(accountId, LIST_FOLLOWING, LIST_FOLLOWERS, false);
    }

    /** Followers the account does not follow back, from the latest scan. */
    public List<FollowUser> followersNotFollowedBack(String accountId) {
        return latestMembers(accountId, LIST_FOLLOWERS, LIST_FOLLOWING, false);
    }

    public List<FollowUser> mutuals(String accountId) {
        return latestMembers(accountId, LIST_FOLLOWING, LIST_FOLLOWERS, true);
    }

    public List<Change> history(String accountId) {
        List<Change> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT user_id, kind, old_value, new_value, seen_at FROM follow_changes"
                        + " WHERE account_id = ? ORDER BY id DESC", new String[]{accountId})) {
            while (c.moveToNext()) {
                out.add(new Change(c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getLong(4)));
            }
        }
        return out;
    }

    /** Deletes this account's history and keeps its scans. */
    public void clearHistory(String accountId) {
        getWritableDatabase().delete("follow_changes", "account_id = ?", new String[]{accountId});
    }

    /** Deletes every scan, identity and change of one account. */
    public void deleteAccount(String accountId) {
        String[] args = {accountId};
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM follow_scan_members WHERE scan_id IN"
                    + " (SELECT id FROM follow_scans WHERE account_id = ?)", args);
            db.delete("follow_scans", "account_id = ?", args);
            db.delete("follow_users", "account_id = ?", args);
            db.delete("follow_changes", "account_id = ?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private List<FollowUser> latestMembers(String accountId, int list, int other, boolean inOther) {
        List<FollowUser> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        long scanId = latestScanId(db, accountId);
        if (scanId == -1) return out;
        String scan = String.valueOf(scanId);
        try (Cursor c = db.rawQuery(
                "SELECT u.user_id, u.username, u.full_name, u.verified, u.pic_id"
                        + " FROM follow_scan_members m JOIN follow_users u"
                        + " ON u.account_id = ? AND u.user_id = m.user_id"
                        + " WHERE m.scan_id = ? AND m.list = ? AND m.user_id "
                        + (inOther ? "IN" : "NOT IN")
                        + " (SELECT user_id FROM follow_scan_members WHERE scan_id = ? AND list = ?)"
                        + " ORDER BY u.username COLLATE NOCASE",
                new String[]{accountId, scan, String.valueOf(list), scan, String.valueOf(other)})) {
            while (c.moveToNext()) {
                out.add(new FollowUser(c.getString(0), c.getString(1), c.getString(2),
                        c.getInt(3) != 0, c.getString(4)));
            }
        }
        return out;
    }

    private static long latestScanId(SQLiteDatabase db, String accountId) {
        try (Cursor c = db.rawQuery("SELECT id FROM follow_scans WHERE account_id = ?"
                + " ORDER BY id DESC LIMIT 1", new String[]{accountId})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    private static Map<String, FollowUser> byId(Collection<FollowUser> users) {
        Map<String, FollowUser> out = new LinkedHashMap<>();
        for (FollowUser user : users) {
            if (user != null && user.id != null) out.put(user.id, user);
        }
        return out;
    }

    private static void insertMembers(SQLiteStatement member, long scanId, int list,
                                      Map<String, FollowUser> users) {
        for (String userId : users.keySet()) {
            member.bindLong(1, scanId);
            member.bindLong(2, list);
            member.bindString(3, userId);
            member.executeInsert();
        }
    }

    /** user_id to {username, full_name, pic_id} as stored before this scan. */
    private static Map<String, String[]> knownUsers(SQLiteDatabase db, String accountId) {
        Map<String, String[]> out = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT user_id, username, full_name, pic_id FROM follow_users"
                + " WHERE account_id = ?", new String[]{accountId})) {
            while (c.moveToNext()) {
                out.put(c.getString(0), new String[]{c.getString(1), c.getString(2), c.getString(3)});
            }
        }
        return out;
    }

    /** One identity per user; for a mutual, a non-null field wins, and the followers list first. */
    private static Map<String, FollowUser> merge(Map<String, FollowUser> followers,
                                                 Map<String, FollowUser> following) {
        Map<String, FollowUser> out = new LinkedHashMap<>(followers);
        for (FollowUser user : following.values()) {
            FollowUser seen = out.get(user.id);
            out.put(user.id, seen == null ? user : new FollowUser(user.id,
                    either(seen.username, user.username), either(seen.fullName, user.fullName),
                    seen.verified, either(seen.picId, user.picId)));
        }
        return out;
    }

    private static String either(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static void listChanges(SQLiteDatabase db, Changes changes, long previous, int list,
                                    Map<String, FollowUser> current,
                                    Map<String, FollowUser> everyone, Map<String, String[]> known,
                                    String gained, String lost) {
        Set<String> before = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT user_id FROM follow_scan_members"
                        + " WHERE scan_id = ? AND list = ?",
                new String[]{String.valueOf(previous), String.valueOf(list)})) {
            while (c.moveToNext()) before.add(c.getString(0));
        }
        for (FollowUser user : current.values()) {
            if (!before.contains(user.id)) {
                changes.add(user.id, gained, null, everyone.get(user.id).username);
            }
        }
        for (String userId : before) {
            if (current.containsKey(userId)) continue;
            String[] identity = known.get(userId);
            changes.add(userId, lost, null, identity != null ? identity[0] : null);
        }
    }

    /** Records a change when a reported value differs from the stored one; returns the value to store. */
    private static String identity(Changes changes, String userId, String kind,
                                   String stored, String reported) {
        if (reported == null) return stored;
        if (!reported.equals(stored)) changes.add(userId, kind, stored, reported);
        return reported;
    }

    private static void prune(SQLiteDatabase db, String accountId) {
        Object[] args = {accountId, accountId};
        String older = "SELECT id FROM follow_scans WHERE account_id = ? AND id NOT IN"
                + " (SELECT id FROM follow_scans WHERE account_id = ? ORDER BY id DESC LIMIT "
                + KEPT_SCANS + ")";
        db.execSQL("DELETE FROM follow_scan_members WHERE scan_id IN (" + older + ")", args);
        db.execSQL("DELETE FROM follow_scans WHERE id IN (" + older + ")", args);
        db.execSQL("DELETE FROM follow_users WHERE account_id = ? AND user_id NOT IN"
                + " (SELECT m.user_id FROM follow_scan_members m JOIN follow_scans s"
                + " ON s.id = m.scan_id WHERE s.account_id = ?)", args);
    }

    private static void bind(SQLiteStatement statement, int index, String value) {
        if (value == null) statement.bindNull(index);
        else statement.bindString(index, value);
    }

    private static final class Changes {
        private final SQLiteStatement statement;
        private final String accountId;
        private final long scanId;
        private final long seenAt;

        Changes(SQLiteStatement statement, String accountId, long scanId, long seenAt) {
            this.statement = statement;
            this.accountId = accountId;
            this.scanId = scanId;
            this.seenAt = seenAt;
        }

        void add(String userId, String kind, String oldValue, String newValue) {
            statement.bindString(1, accountId);
            statement.bindLong(2, scanId);
            statement.bindString(3, userId);
            statement.bindString(4, kind);
            bind(statement, 5, oldValue);
            bind(statement, 6, newValue);
            statement.bindLong(7, seenAt);
            statement.executeInsert();
        }
    }
}
