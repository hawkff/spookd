/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class SpookdMessageDb extends SQLiteOpenHelper {

    // Legacy stored name: existing installs keep their database under it.
    private static final String DB_NAME = "piko_dm_vault.db";
    static final int DB_VERSION = 8;
    /** The message type of a removed reaction row in {@link #getDeletedMessages()}. */
    public static final String REMOVED_REACTION = "removed_reaction";
    /** The id prefix of a removed reaction row in {@link #getDeletedMessages()}. */
    public static final String REACTION_ID_PREFIX = "reaction:";
    private static final String TABLE = "saved_messages";
    // sender_id → username directory. MQTT-delivered items carry only a numeric sender_id;
    // the REST path occasionally carries a full UserInfo. Every time we resolve a username we
    // persist it here so later MQTT-only items can show the real handle.
    private static final String DIR_TABLE = "user_directory";

    private static volatile SpookdMessageDb instance;

    static String normalizeIdentity(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static String canonicalMessageId(String serverId, String clientContext) {
        String server = normalizeIdentity(serverId);
        return server != null ? server : normalizeIdentity(clientContext);
    }

    static String[] identityIndexStatements() {
        return new String[]{
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_server_id ON " + TABLE
                        + "(server_id) WHERE server_id IS NOT NULL AND server_id != ''",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_client_context ON " + TABLE
                        + "(client_context) WHERE client_context IS NOT NULL AND client_context != ''"
        };
    }

    // Plain CREATE: a pre-existing saved_media table makes the upgrade fail and roll back.
    static String[] savedMediaStatements() {
        return new String[]{
                "CREATE TABLE saved_media ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "item_id TEXT NOT NULL UNIQUE,"
                        + "account_id TEXT,"
                        + "kind TEXT NOT NULL,"
                        + "url TEXT,"
                        + "url_expires_at INTEGER NOT NULL,"
                        + "msg_timestamp INTEGER NOT NULL,"
                        + "state TEXT NOT NULL,"
                        + "attempts INTEGER NOT NULL DEFAULT 0,"
                        + "next_attempt_at INTEGER NOT NULL DEFAULT 0,"
                        + "size_bytes INTEGER NOT NULL DEFAULT 0,"
                        + "mime TEXT"
                        + ")",
                "CREATE INDEX idx_media_state_time ON saved_media(state, msg_timestamp)"
        };
    }

    // Version 6: the original view mode of the item (once, replayable, ...); NULL when unknown.
    static final String SAVED_MEDIA_VIEW_MODE = "ALTER TABLE saved_media ADD COLUMN view_mode TEXT";

    // Version 7: the reactions seen on saved messages; removed_at is set once another user removed one.
    static String[] reactionStatements() {
        return new String[]{
                "CREATE TABLE message_reactions ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "message_id TEXT NOT NULL,"
                        + "sender_id TEXT NOT NULL,"
                        + "emoji TEXT NOT NULL,"
                        + "removed_at INTEGER"
                        + ")",
                "CREATE UNIQUE INDEX idx_reactions_live ON message_reactions(message_id, sender_id, emoji)"
                        + " WHERE removed_at IS NULL",
                "CREATE INDEX idx_reactions_message ON message_reactions(message_id)"
        };
    }

    // Version 8: the sender's profile picture link, NULL until one is known.
    static final String USER_DIRECTORY_PIC_URL = "ALTER TABLE user_directory ADD COLUMN pic_url TEXT";

    static String[] upgradeStatements(int oldVersion) {
        List<String> statements = new ArrayList<>();
        if (oldVersion < 3) {
            statements.add("ALTER TABLE " + TABLE + " ADD COLUMN server_id TEXT");
            statements.add("ALTER TABLE " + TABLE + " ADD COLUMN client_context TEXT");
            statements.add("UPDATE " + TABLE + " SET server_id = message_id "
                    + "WHERE message_id GLOB '[0-9]*' AND message_id NOT GLOB '*[^0-9]*'");
            java.util.Collections.addAll(statements, identityIndexStatements());
        }
        if (oldVersion < 5) {
            java.util.Collections.addAll(statements, savedMediaStatements());
        }
        if (oldVersion < 6) {
            statements.add(SAVED_MEDIA_VIEW_MODE);
        }
        if (oldVersion < 7) {
            java.util.Collections.addAll(statements, reactionStatements());
        }
        if (oldVersion < 8) {
            statements.add(USER_DIRECTORY_PIC_URL);
        }
        return statements.toArray(new String[0]);
    }

    public static SpookdMessageDb getInstance(Context context) {
        if (instance == null) {
            synchronized (SpookdMessageDb.class) {
                if (instance == null) {
                    instance = new SpookdMessageDb(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SpookdMessageDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "message_id TEXT UNIQUE NOT NULL," +
            "server_id TEXT," +
            "client_context TEXT," +
            "thread_id TEXT NOT NULL," +
            "sender_id TEXT," +
            "sender_username TEXT," +
            "content TEXT," +
            "message_type TEXT," +
            "timestamp INTEGER NOT NULL," +
            "is_deleted INTEGER DEFAULT 0" +
            ")"
        );
        db.execSQL("CREATE INDEX idx_thread_id ON " + TABLE + "(thread_id)");
        db.execSQL("CREATE INDEX idx_is_deleted ON " + TABLE + "(is_deleted)");
        createIdentityIndexes(db);
        createDirTable(db);
        for (String statement : savedMediaStatements()) {
            db.execSQL(statement);
        }
        db.execSQL(SAVED_MEDIA_VIEW_MODE);
        for (String statement : reactionStatements()) {
            db.execSQL(statement);
        }
        db.execSQL(USER_DIRECTORY_PIC_URL);
    }

    private void createDirTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + DIR_TABLE + " (" +
            "sender_id TEXT PRIMARY KEY," +
            "username TEXT NOT NULL" +
            ")"
        );
    }

    private void createIdentityIndexes(SQLiteDatabase db) {
        for (String statement : identityIndexStatements()) {
            db.execSQL(statement);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createDirTable(db);
        for (String statement : upgradeStatements(oldVersion)) {
            db.execSQL(statement);
        }
    }

    /** Insert or enrich a captured message using exact Instagram identifiers. */
    public String upsertMessage(String serverId, String clientContext, String threadId,
                                String senderId, String senderUsername, String content,
                                String type, long timestamp) {
        String normalizedServer = normalizeIdentity(serverId);
        String normalizedClient = normalizeIdentity(clientContext);
        String canonicalId = canonicalMessageId(normalizedServer, normalizedClient);
        if (canonicalId == null) return null;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String messageId = resolveAndMergeMessageId(db, normalizedServer, normalizedClient);
            boolean freshInsert = false;
            if (messageId == null) {
                ContentValues values = new ContentValues();
                values.put("message_id", canonicalId);
                values.put("server_id", normalizedServer);
                values.put("client_context", normalizedClient);
                values.put("thread_id", threadId != null ? threadId : "");
                values.put("sender_id", senderId);
                values.put("sender_username", senderUsername != null ? senderUsername : "");
                values.put("content", content != null ? content : "");
                values.put("message_type", type != null ? type : "unknown");
                values.put("timestamp", timestamp);
                long inserted = db.insertWithOnConflict(
                        TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                if (inserted != -1) {
                    messageId = canonicalId;
                    freshInsert = true;
                } else {
                    messageId = resolveAndMergeMessageId(db, normalizedServer, normalizedClient);
                }
            }

            if (messageId == null) return null;

            // Skip re-checking columns the INSERT above just set from these same values - only an
            // existing row (from a concurrent insert or an earlier partial capture) can be missing
            // fields this call has.
            if (!freshInsert) {
                fillIfEmpty(db, messageId, "server_id", normalizedServer);
                fillIfEmpty(db, messageId, "client_context", normalizedClient);
                fillIfEmpty(db, messageId, "thread_id", threadId);
                fillIfEmpty(db, messageId, "sender_id", senderId);
                fillIfEmpty(db, messageId, "sender_username", senderUsername);
                fillIfEmpty(db, messageId, "message_type", type);
                if (content != null && content.startsWith("http")) {
                    upgradeContentToUrl(db, messageId, content);
                } else {
                    fillIfEmpty(db, messageId, "content", content);
                }
            }
            db.setTransactionSuccessful();
            return messageId;
        } finally {
            db.endTransaction();
        }
    }

    /** Resolve exact identifiers and merge separate server/client captures when both exist. */
    public String resolveAndMergeMessageId(String serverId, String clientContext) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String messageId = resolveAndMergeMessageId(db, serverId, clientContext);
            db.setTransactionSuccessful();
            return messageId;
        } finally {
            db.endTransaction();
        }
    }

    private String resolveAndMergeMessageId(SQLiteDatabase db, String serverId,
                                            String clientContext) {
        String normalizedServer = normalizeIdentity(serverId);
        String normalizedClient = normalizeIdentity(clientContext);
        String messageId = null;

        if (normalizedServer != null) {
            messageId = findMessageIdByColumn(db, "server_id", normalizedServer);
        }
        if (normalizedClient != null) {
            String clientMessageId = findMessageIdByColumn(
                    db, "client_context", normalizedClient);
            if (messageId == null) {
                messageId = clientMessageId;
            } else if (clientMessageId != null && !messageId.equals(clientMessageId)) {
                String olderMessageId = findOlderMessageId(db, messageId, clientMessageId);
                String newerMessageId = olderMessageId.equals(messageId)
                        ? clientMessageId : messageId;
                mergeRows(db, olderMessageId, newerMessageId);
                messageId = olderMessageId;
            }
        }

        if (messageId != null) {
            fillIfEmpty(db, messageId, "server_id", normalizedServer);
            fillIfEmpty(db, messageId, "client_context", normalizedClient);
        }
        return messageId;
    }

    private String findMessageIdByColumn(SQLiteDatabase db, String identityColumn,
                                         String identity) {
        // One indexed lookup per column; the "!= ''" term lets SQLite use the partial index.
        Cursor cursor = db.rawQuery(
                "SELECT message_id FROM (SELECT id, message_id FROM " + TABLE
                        + " WHERE " + identityColumn + " = ? AND " + identityColumn + " != ''"
                        + " UNION ALL SELECT id, message_id FROM " + TABLE
                        + " WHERE message_id = ?) ORDER BY id ASC LIMIT 1",
                new String[]{identity, identity}
        );
        String result = cursor.moveToFirst() ? cursor.getString(0) : null;
        cursor.close();
        return result;
    }

    private String findOlderMessageId(SQLiteDatabase db, String firstId, String secondId) {
        Cursor cursor = db.query(
                TABLE,
                new String[]{"message_id"},
                "message_id IN (?, ?)",
                new String[]{firstId, secondId},
                null,
                null,
                "id ASC",
                "1"
        );
        String result = cursor.moveToFirst() ? cursor.getString(0) : firstId;
        cursor.close();
        return result;
    }

    /** Merge server-only and client-only captures once a callback supplies both ids. */
    private void mergeRows(SQLiteDatabase db, String primaryId, String secondaryId) {
        Cursor cursor = db.query(
                TABLE,
                new String[]{
                        "server_id", "client_context", "thread_id", "sender_id",
                        "sender_username", "content", "message_type", "is_deleted"
                },
                "message_id = ?",
                new String[]{secondaryId},
                null,
                null,
                null,
                "1"
        );
        if (!cursor.moveToFirst()) {
            cursor.close();
            return;
        }

        String secondaryServer = cursor.getString(0);
        String secondaryClient = cursor.getString(1);
        String threadId = cursor.getString(2);
        String senderId = cursor.getString(3);
        String senderUsername = cursor.getString(4);
        String content = cursor.getString(5);
        String messageType = cursor.getString(6);
        boolean deleted = cursor.getInt(7) != 0;
        cursor.close();

        ContentValues released = new ContentValues();
        released.putNull("server_id");
        released.putNull("client_context");
        db.update(TABLE, released, "message_id = ?", new String[]{secondaryId});

        fillIfEmpty(db, primaryId, "server_id", secondaryServer);
        fillIfEmpty(db, primaryId, "client_context", secondaryClient);
        fillIfEmpty(db, primaryId, "thread_id", threadId);
        fillIfEmpty(db, primaryId, "sender_id", senderId);
        fillIfEmpty(db, primaryId, "sender_username", senderUsername);
        fillIfEmpty(db, primaryId, "message_type", messageType);
        if (content != null && content.startsWith("http")) {
            upgradeContentToUrl(db, primaryId, content);
        } else {
            fillIfEmpty(db, primaryId, "content", content);
        }
        if (deleted) {
            ContentValues values = new ContentValues();
            values.put("is_deleted", 1);
            db.update(TABLE, values, "message_id = ?", new String[]{primaryId});
        }
        // The secondary's reactions move over; a live one the primary also has is a duplicate.
        db.execSQL("DELETE FROM message_reactions WHERE message_id = ? AND removed_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM message_reactions p WHERE p.message_id = ?"
                + " AND p.removed_at IS NULL AND p.sender_id = message_reactions.sender_id"
                + " AND p.emoji = message_reactions.emoji)", new Object[]{secondaryId, primaryId});
        ContentValues reparented = new ContentValues();
        reparented.put("message_id", primaryId);
        db.update("message_reactions", reparented, "message_id = ?", new String[]{secondaryId});
        db.delete(TABLE, "message_id = ?", new String[]{secondaryId});
    }

    /** Overwrite stored content with a media url when the stored value isn't already an http link.
     *  A media DM is often captured first (MQTT real-time) before its CDN url is resolved, so the
     *  row lands with a caption or empty placeholder; a later pass (REST thread-history) that finds
     *  the url must be able to replace that so the item stays tappable / downloadable. */
    private void upgradeContentToUrl(SQLiteDatabase db, String messageId, String url) {
        ContentValues cv = new ContentValues();
        cv.put("content", url);
        db.update(TABLE, cv,
            "message_id = ? AND (content IS NULL OR content = '' OR content NOT LIKE 'http%')",
            new String[]{messageId});
    }

    /** Update a single column on the existing row only when the new value is non-empty AND
     *  the stored value is currently null/empty. Preserves is_deleted, timestamp, etc. */
    private void fillIfEmpty(SQLiteDatabase db, String messageId, String column, String value) {
        if (value == null || value.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put(column, value);
        String emptyCondition = column + " IS NULL OR " + column + " = ''";
        if ("message_type".equals(column)) {
            emptyCondition += " OR " + column + " = 'unknown'";
        }
        db.update(TABLE, cv,
            "message_id = ? AND (" + emptyCondition + ")",
            new String[]{messageId});
    }

    /** Persist a sender_id → username mapping (no-op for empty input). Latest non-empty wins. */
    public void putUsername(String senderId, String username) {
        if (senderId == null || senderId.isEmpty() || username == null || username.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("username", username);
        // Update first, since a replace would drop the stored picture.
        if (db.update(DIR_TABLE, cv, "sender_id = ?", new String[]{senderId}) == 0) {
            cv.put("sender_id", senderId);
            db.insertWithOnConflict(DIR_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        }
        // Backfill any stored messages from this sender that still lack a username.
        ContentValues mv = new ContentValues();
        mv.put("sender_username", username);
        db.update(TABLE, mv,
            "sender_id = ? AND (sender_username IS NULL OR sender_username = '')",
            new String[]{senderId});
    }

    /** Stores a sender's picture link; the username creates the directory row when there is none. */
    public boolean putPicture(String senderId, String username, String url) {
        if (senderId == null || senderId.isEmpty() || url == null || url.isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("pic_url", url);
        if (db.update(DIR_TABLE, cv, "sender_id = ?", new String[]{senderId}) == 1) return true;
        if (username == null || username.isEmpty()) return false;
        cv.put("sender_id", senderId);
        cv.put("username", username);
        return db.insertWithOnConflict(DIR_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** sender_id to stored picture link, for every sender that has one. */
    public java.util.Map<String, String> getPictureUrls() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        Cursor c = getReadableDatabase().query(DIR_TABLE, new String[]{"sender_id", "pic_url"},
                "pic_url IS NOT NULL AND pic_url != ''", null, null, null, null);
        while (c.moveToNext()) out.put(c.getString(0), c.getString(1));
        c.close();
        return out;
    }

    /** Look up a previously-resolved username for a sender_id, or null. */
    public String getUsername(String senderId) {
        if (senderId == null || senderId.isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(DIR_TABLE, new String[]{"username"},
                "sender_id = ?", new String[]{senderId}, null, null, null);
        String result = null;
        if (c.moveToFirst()) result = c.getString(0);
        c.close();
        return (result != null && !result.isEmpty()) ? result : null;
    }

    /** Permanently remove saved messages from the vault as one atomic operation. */
    public void deleteSaved(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String messageId : messageIds) {
                if (messageId == null) continue;
                if (messageId.startsWith(REACTION_ID_PREFIX)) {
                    db.delete("message_reactions", "id = ?",
                            new String[]{messageId.substring(REACTION_ID_PREFIX.length())});
                } else {
                    db.delete(TABLE, "message_id = ?", new String[]{messageId});
                    db.delete("message_reactions", "message_id = ?", new String[]{messageId});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Stores the reactions now on a saved message, each {sender id, emoji}, and marks the stored ones
     * that are gone as removed; the viewer's own are dropped instead. Returns how many were marked.
     */
    public int syncReactions(String messageId, List<String[]> current, String viewerId, long now) {
        if (messageId == null || current == null) return 0;
        java.util.Set<String> present = new java.util.HashSet<>();
        for (String[] reaction : current) present.add(reaction[0] + '\u0000' + reaction[1]);
        int marked = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT id, sender_id, emoji FROM message_reactions"
                    + " WHERE message_id = ? AND removed_at IS NULL", new String[]{messageId});
            try {
                while (c.moveToNext()) {
                    String sender = c.getString(1);
                    if (present.remove(sender + '\u0000' + c.getString(2))) continue;
                    String[] id = {c.getString(0)};
                    if (sender.equals(viewerId)) {
                        db.delete("message_reactions", "id = ?", id);
                    } else {
                        ContentValues removed = new ContentValues();
                        removed.put("removed_at", now);
                        marked += db.update("message_reactions", removed, "id = ?", id);
                    }
                }
            } finally {
                c.close();
            }
            for (String[] reaction : current) {
                if (!present.remove(reaction[0] + '\u0000' + reaction[1])) continue;
                ContentValues values = new ContentValues();
                values.put("message_id", messageId);
                values.put("sender_id", reaction[0]);
                values.put("emoji", reaction[1]);
                db.insertWithOnConflict("message_reactions", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return marked;
    }

    /** Marks a live row deleted and returns true only for the first successful transition. */
    public boolean markDeleted(String messageId) {
        if (messageId == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("is_deleted", 1);
        return db.update(TABLE, cv, "message_id = ? AND is_deleted = 0",
                new String[]{messageId}) == 1;
    }

    /** Returns the best available sender name for a notification. */
    public String getSenderDisplay(String messageId) {
        if (messageId == null) return null;
        Cursor cursor = getReadableDatabase().query(
                TABLE, new String[]{"sender_username", "sender_id"},
                "message_id = ?", new String[]{messageId}, null, null, null);
        String username = null;
        String senderId = null;
        if (cursor.moveToFirst()) {
            username = cursor.getString(0);
            senderId = cursor.getString(1);
        }
        cursor.close();
        if (username != null && !username.isEmpty()) return username;
        if (senderId == null || senderId.isEmpty()) return null;
        String directoryUsername = getUsername(senderId);
        return directoryUsername;
    }

    /** Returns the stored message type, or null when it is unknown. */
    public String getMessageType(String messageId) {
        if (messageId == null) return null;
        Cursor cursor = getReadableDatabase().query(
                TABLE, new String[]{"message_type"},
                "message_id = ?", new String[]{messageId}, null, null, null);
        String type = cursor.moveToFirst() ? cursor.getString(0) : null;
        cursor.close();
        return type != null && !type.isEmpty() && !"unknown".equals(type) ? type : null;
    }

    /** sender_id stored for a message, or null. */
    public String getSenderId(String messageId) {
        if (messageId == null) return null;
        Cursor c = getReadableDatabase().query(TABLE, new String[]{"sender_id"},
                "message_id = ?", new String[]{messageId}, null, null, null);
        String r = c.moveToFirst() ? c.getString(0) : null;
        c.close();
        return (r != null && !r.isEmpty()) ? r : null;
    }

    /** thread_id stored for a message, or null. */
    public String getThreadId(String messageId) {
        if (messageId == null) return null;
        Cursor c = getReadableDatabase().query(TABLE, new String[]{"thread_id"},
                "message_id = ?", new String[]{messageId}, null, null, null);
        String r = c.moveToFirst() ? c.getString(0) : null;
        c.close();
        return (r != null && !r.isEmpty()) ? r : null;
    }

    /** A previously stored username for this sender in this thread, or null. */
    public String getThreadUsername(String threadId, String senderId) {
        if (threadId == null || threadId.isEmpty()
                || senderId == null || senderId.isEmpty()) return null;
        Cursor c = getReadableDatabase().query(TABLE, new String[]{"sender_username"},
                "thread_id = ? AND sender_id = ?"
                        + " AND sender_username IS NOT NULL AND sender_username != ''",
                new String[]{threadId, senderId}, null, null, null, "1");
        String r = c.moveToFirst() ? c.getString(0) : null;
        c.close();
        return (r != null && !r.isEmpty()) ? r : null;
    }

    public String getStoredContent(String messageId) {
        if (messageId == null) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, new String[]{"content"}, "message_id = ?",
                new String[]{messageId}, null, null, null);
        String result = null;
        if (c.moveToFirst()) result = c.getString(0);
        c.close();
        return (result != null && !result.isEmpty()) ? result : null;
    }

    private static final String HAS_CONTENT =
        " AND (COALESCE(content, '') <> '' OR COALESCE(sender_id, '') <> ''"
            + " OR COALESCE(sender_username, '') <> '')";

    /** The largest page {@link #getDeletedMessages(int, int)} returns. */
    public static final int DELETED_PAGE_SIZE = 200;

    /** The first page of the deleted log. */
    public List<String[]> getDeletedMessages() {
        return getDeletedMessages(DELETED_PAGE_SIZE, 0);
    }

    /**
     * One page of the deleted log, newest first in a stable order, at most {@link #DELETED_PAGE_SIZE} rows.
     * Message rows are [messageId, threadId, senderUsername, content, messageType, timestamp, senderId];
     * removed reaction rows are [reaction:id, threadId, reactorUsername, messageContent, REMOVED_REACTION,
     * removedAt, reactorId, emoji, messageType].
     */
    public List<String[]> getDeletedMessages(int limit, int offset) {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        // Load the whole sender_id -> username directory once instead of letting each row
        // that lacks a stored username fall back to its own query - this list can grow to
        // hundreds/thousands of rows over time, which turned that fallback into an N+1 query.
        java.util.Map<String, String> directory = loadUsernameDirectory(db);
        int pageSize = Math.max(1, Math.min(limit, DELETED_PAGE_SIZE));
        // Stored view-once and view-twice media is listed too, whether or not its message was unsent.
        Cursor c = db.rawQuery("SELECT * FROM ("
                + "SELECT 0 AS kind, id AS row_id, message_id, thread_id, sender_username, content,"
                + " message_type, timestamp AS at, sender_id, NULL AS emoji, NULL AS reacted_type FROM " + TABLE
                + " WHERE (is_deleted = 1 OR server_id IN (SELECT item_id FROM saved_media"
                + " WHERE state = 'stored' AND view_mode IN ('once', 'replayable')))" + HAS_CONTENT
                + " UNION ALL SELECT 1, r.id, NULL, m.thread_id, NULL, m.content, NULL, r.removed_at,"
                + " r.sender_id, r.emoji, m.message_type FROM message_reactions r JOIN " + TABLE
                + " m ON m.message_id = r.message_id WHERE r.removed_at IS NOT NULL"
                + ") ORDER BY at DESC, kind DESC, row_id DESC LIMIT " + pageSize + " OFFSET " + Math.max(0, offset),
                null);
        try {
            while (c.moveToNext()) {
                String senderId = c.getString(8);
                if (c.getInt(0) == 0) {
                    result.add(new String[]{c.getString(2), c.getString(3),
                            resolveUsername(c.getString(4), senderId, directory), c.getString(5),
                            c.getString(6), String.valueOf(c.getLong(7)), senderId});
                } else {
                    result.add(new String[]{REACTION_ID_PREFIX + c.getLong(1), c.getString(3),
                            directory.get(senderId), c.getString(5), REMOVED_REACTION,
                            String.valueOf(c.getLong(7)), senderId, c.getString(9), c.getString(10)});
                }
            }
        } finally {
            c.close();
        }
        return result;
    }

    private java.util.Map<String, String> loadUsernameDirectory(SQLiteDatabase db) {
        java.util.Map<String, String> directory = new java.util.HashMap<>();
        Cursor c = db.query(DIR_TABLE, new String[]{"sender_id", "username"}, null, null, null, null, null);
        while (c.moveToNext()) {
            directory.put(c.getString(0), c.getString(1));
        }
        c.close();
        return directory;
    }

    private String resolveUsername(String storedUsername, String senderId, java.util.Map<String, String> directory) {
        if (storedUsername != null && !storedUsername.isEmpty()) return storedUsername;
        if (senderId != null && !senderId.isEmpty()) {
            String dir = directory.get(senderId);
            if (dir != null && !dir.isEmpty()) return dir;
        }
        return storedUsername;
    }

}
