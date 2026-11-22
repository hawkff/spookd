package app.morphe.extension.instagram.patches.dm.vault;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** All SQL on the saved_media table. Every state change is one conditional statement. */
final class VaultStore {
    static final String QUEUED = "queued";
    static final String STORED = "stored";

    // Kept media: its message was unsent, so neither eviction nor retention may drop it.
    private static final String KEPT = "EXISTS (SELECT 1 FROM saved_messages s"
            + " WHERE s.server_id = saved_media.item_id AND s.server_id != '' AND s.is_deleted = 1)";
    private static final String COLUMNS = "saved_media.id, saved_media.state, saved_media.url,"
            + " saved_media.url_expires_at, saved_media.attempts, saved_media.size_bytes, saved_media.kind";

    /** One saved_media row. */
    static final class Media {
        final long id;
        final String state;
        final String url;
        final long expiresAt;
        final int attempts;
        final long sizeBytes;
        final String kind;

        private Media(Cursor cursor) {
            id = cursor.getLong(0);
            state = cursor.getString(1);
            url = cursor.getString(2);
            expiresAt = cursor.getLong(3);
            attempts = cursor.getInt(4);
            sizeBytes = cursor.getLong(5);
            kind = cursor.getString(6);
        }
    }

    private final SQLiteDatabase db;

    VaultStore(SQLiteDatabase db) {
        this.db = db;
    }

    /** Inserts a queued row; returns its id, or -1 when the item already has a row. */
    long insertQueued(String itemId, String accountId, String kind, String url, long expiresAt,
                      long msgTimestamp) {
        ContentValues values = new ContentValues();
        values.put("item_id", itemId);
        values.put("account_id", accountId);
        values.put("kind", kind);
        values.put("url", url);
        values.put("url_expires_at", expiresAt);
        values.put("msg_timestamp", msgTimestamp);
        values.put("state", QUEUED);
        return db.insertWithOnConflict("saved_media", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    Media row(long id) {
        return first("SELECT " + COLUMNS + " FROM saved_media WHERE saved_media.id = ?",
                String.valueOf(id));
    }

    Media rowForMessage(String messageId) {
        return first("SELECT " + COLUMNS + " FROM saved_media JOIN saved_messages s"
                + " ON s.server_id = saved_media.item_id"
                + " WHERE s.message_id = ? AND s.server_id != ''", messageId);
    }

    /** Counts an attempt before any network I/O; false when expired, used up or no longer queued. */
    boolean countAttempt(long id, long nextAttemptAt, long now) {
        return update("UPDATE saved_media SET attempts = attempts + 1, next_attempt_at = ?"
                        + " WHERE id = ? AND state = 'queued' AND attempts < ? AND url_expires_at > ?",
                nextAttemptAt, id, MediaVault.MAX_ATTEMPTS, now) == 1;
    }

    int fail(long id) {
        return update("UPDATE saved_media SET state = 'failed', url = NULL"
                + " WHERE id = ? AND state = 'queued'", id);
    }

    int publish(long id, long sizeBytes, String mime) {
        return update("UPDATE saved_media SET state = 'stored', size_bytes = ?, mime = ?, url = NULL"
                + " WHERE id = ? AND state = 'queued'", sizeBytes, mime, id);
    }

    /** Eviction and retention: drops a stored row only while it is still not kept. */
    int dropStored(long id, long before) {
        return update("UPDATE saved_media SET state = 'dropped', size_bytes = 0, mime = NULL"
                + " WHERE id = ? AND state = 'stored' AND msg_timestamp < ? AND NOT " + KEPT, id, before);
    }

    /** User delete. */
    int drop(long id) {
        return update("UPDATE saved_media SET state = 'dropped', url = NULL, size_bytes = 0, mime = NULL"
                + " WHERE id = ? AND state != 'dropped'", id);
    }

    /** A stored row whose file is gone. */
    int markLost(long id) {
        return update("UPDATE saved_media SET state = 'failed', size_bytes = 0, mime = NULL"
                + " WHERE id = ? AND state = 'stored'", id);
    }

    int dropQueuedBefore(long before) {
        return update("UPDATE saved_media SET state = 'dropped', url = NULL"
                + " WHERE state = 'queued' AND msg_timestamp < ? AND NOT " + KEPT, before);
    }

    int deleteTombstonesBefore(long before) {
        return update("DELETE FROM saved_media"
                + " WHERE state IN ('dropped', 'failed') AND msg_timestamp < ?", before);
    }

    /** Up to 50 unkept stored rows older than {@code before} as {id, size_bytes}, oldest first. */
    List<long[]> evictionCandidates(long before) {
        return pairs("SELECT id, size_bytes FROM saved_media WHERE state = 'stored'"
                + " AND msg_timestamp < ? AND NOT " + KEPT + " ORDER BY msg_timestamp LIMIT 50",
                String.valueOf(before));
    }

    /** Queued rows as {id, next_attempt_at}. */
    List<long[]> queued() {
        return pairs("SELECT id, next_attempt_at FROM saved_media WHERE state = 'queued'");
    }

    /** Stored rows as {id, size_bytes}. */
    List<long[]> stored() {
        return pairs("SELECT id, size_bytes FROM saved_media WHERE state = 'stored'");
    }

    long storedBytes() {
        return DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(SUM(size_bytes), 0) FROM saved_media WHERE state = 'stored'", null);
    }

    long keptBytes() {
        return DatabaseUtils.longForQuery(db, "SELECT COALESCE(SUM(size_bytes), 0) FROM saved_media"
                + " WHERE state = 'stored' AND " + KEPT, null);
    }

    /** Ids of unsent saved messages whose media is stored. */
    Set<String> storedMessageIds() {
        Set<String> ids = new HashSet<>();
        try (Cursor cursor = db.rawQuery("SELECT s.message_id FROM saved_messages s"
                + " JOIN saved_media m ON m.item_id = s.server_id"
                + " WHERE s.server_id != '' AND s.is_deleted = 1 AND m.state = 'stored'", null)) {
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        return ids;
    }

    /** Every stored row with its message's sender, kept or not, newest first. */
    List<MediaVault.Item> storedItems() {
        List<MediaVault.Item> items = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT m.id, m.kind, m.mime, m.size_bytes, m.msg_timestamp,"
                + " m.account_id, s.sender_username, s.sender_id FROM saved_media m"
                + " LEFT JOIN saved_messages s ON s.server_id = m.item_id AND s.server_id != ''"
                + " WHERE m.state = 'stored' ORDER BY m.msg_timestamp DESC", null)) {
            while (cursor.moveToNext()) {
                items.add(new MediaVault.Item(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), cursor.getLong(4), cursor.getString(5), cursor.getString(6),
                        cursor.getString(7)));
            }
        }
        return items;
    }

    private Media first(String sql, String arg) {
        try (Cursor cursor = db.rawQuery(sql, new String[]{arg})) {
            return cursor.moveToFirst() ? new Media(cursor) : null;
        }
    }

    private List<long[]> pairs(String sql, String... args) {
        List<long[]> rows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(sql, args)) {
            while (cursor.moveToNext()) rows.add(new long[]{cursor.getLong(0), cursor.getLong(1)});
        }
        return rows;
    }

    private int update(String sql, Object... args) {
        SQLiteStatement statement = db.compileStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null) statement.bindNull(i + 1);
                else if (arg instanceof Number) statement.bindLong(i + 1, ((Number) arg).longValue());
                else statement.bindString(i + 1, (String) arg);
            }
            return statement.executeUpdateDelete();
        } finally {
            statement.close();
        }
    }
}
