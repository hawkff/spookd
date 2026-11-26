package app.morphe.extension.instagram.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class SpookdMessageDbMigrationTest {
    private static final String DB_NAME = "piko_dm_vault.db";
    // Added in version 7.
    private static final String[] V7_NAMES = {"message_reactions", "idx_reactions_live", "idx_reactions_message"};

    @Test
    public void upgradesV4WithRowsIntact() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        createV4(context, false);
        List<String> before = dump(context);

        SpookdMessageDb helper = newHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        assertEquals(SpookdMessageDb.DB_VERSION, db.getVersion());
        assertEquals(Arrays.asList("id", "item_id", "account_id", "kind", "url", "url_expires_at",
                "msg_timestamp", "state", "attempts", "next_attempt_at", "size_bytes", "mime", "view_mode"),
                columns(db, "saved_media"));
        assertTrue(hasIndex(db, "idx_media_state_time"));
        helper.close();

        assertEquals(before, dump(context, concat(V7_NAMES, "saved_media", "idx_media_state_time")));
    }

    @Test
    public void existingSavedMediaTableFailsUpgradeAndKeepsV4() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        createV4(context, true);
        List<String> before = dump(context);

        SpookdMessageDb helper = newHelper(context);
        assertThrows(SQLiteException.class, helper::getWritableDatabase);
        helper.close();

        SQLiteDatabase db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        assertEquals(4, db.getVersion());
        assertEquals(Arrays.asList("stale"), columns(db, "saved_media"));
        assertFalse(hasIndex(db, "idx_media_state_time"));
        db.close();
        assertEquals(before, dump(context));
    }

    @Test
    public void upgradesV5WithRowsIntactAndNoViewMode() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        createV4(context, false);
        SQLiteDatabase v5 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        for (String statement : SpookdMessageDb.savedMediaStatements()) v5.execSQL(statement);
        v5.execSQL("INSERT INTO saved_media (item_id, account_id, kind, url, url_expires_at, msg_timestamp,"
                + " state, attempts, next_attempt_at, size_bytes, mime) VALUES"
                + " ('31000000000000000000000000001', '99', 'photo', NULL, 1700000000000, 1690000000000,"
                + " 'stored', 1, 1690000060000, 5000, 'image/jpeg'),"
                + " ('31000000000000000000000000003', NULL, 'voice', 'https://x.fbcdn.net/a.m4a?oe=65A1B2C3',"
                + " 1700000000000, 1690000002000, 'queued', 0, 0, 0, NULL)");
        v5.setVersion(5);
        v5.close();
        List<String> expected = new ArrayList<>();
        for (String line : dump(context)) {
            if (line.startsWith("table saved_media ")) continue;
            expected.add(line.startsWith("saved_media|") ? line + "|NULL" : line);
        }

        SpookdMessageDb helper = newHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        assertEquals(SpookdMessageDb.DB_VERSION, db.getVersion());
        List<String> media = columns(db, "saved_media");
        assertEquals("view_mode", media.get(media.size() - 1));
        helper.close();

        List<String> after = dump(context, V7_NAMES);
        after.removeIf(line -> line.startsWith("table saved_media "));
        assertEquals(expected, after);
        assertEquals(2, after.stream().filter(line -> line.startsWith("saved_media|")).count());
    }

    @Test
    public void upgradesV6WithEveryRowIntact() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        createV4(context, false);
        SQLiteDatabase v6 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        for (String statement : SpookdMessageDb.savedMediaStatements()) v6.execSQL(statement);
        v6.execSQL(SpookdMessageDb.SAVED_MEDIA_VIEW_MODE);
        v6.execSQL("INSERT INTO saved_media (item_id, kind, url_expires_at, msg_timestamp, state, view_mode)"
                + " VALUES ('31000000000000000000000000001', 'photo', 1700000000000, 1690000000000,"
                + " 'stored', 'once')");
        v6.setVersion(6);
        v6.close();
        List<String> before = dump(context);

        SpookdMessageDb helper = newHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        assertEquals(SpookdMessageDb.DB_VERSION, db.getVersion());
        assertEquals(Arrays.asList("id", "message_id", "sender_id", "emoji", "removed_at"),
                columns(db, "message_reactions"));
        helper.close();

        assertEquals(before, dump(context, V7_NAMES));
        assertEquals(3, before.stream().filter(line -> line.startsWith("saved_messages|")).count());
    }

    @Test
    public void v6FileOpenedAtV5ThrowsAndStaysUnchanged() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SpookdMessageDb current = newHelper(context);
        SQLiteDatabase created = current.getWritableDatabase();
        insertMessages(created);
        created.execSQL("INSERT INTO saved_media (item_id, kind, url_expires_at, msg_timestamp, state)"
                + " VALUES ('31000000000000000000000000001', 'photo', 1700000000000, 1690000000000, 'queued')");
        current.close();
        List<String> before = dump(context);

        SQLiteOpenHelper older = new SQLiteOpenHelper(context, DB_NAME, null, 5) {
            @Override
            public void onCreate(SQLiteDatabase db) {
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            }
        };
        assertThrows(SQLiteException.class, older::getWritableDatabase);
        older.close();

        SQLiteDatabase db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        assertEquals(SpookdMessageDb.DB_VERSION, db.getVersion());
        db.close();
        assertEquals(before, dump(context));
    }

    private static String[] concat(String[] first, String... second) {
        List<String> out = new ArrayList<>(Arrays.asList(first));
        out.addAll(Arrays.asList(second));
        return out.toArray(new String[0]);
    }

    private static SpookdMessageDb newHelper(Context context) throws Exception {
        // A fresh instance per test; the singleton would keep an earlier test's context.
        Constructor<SpookdMessageDb> constructor = SpookdMessageDb.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    /** Writes the version 4 schema exactly as the version 4 onCreate produced it, plus rows. */
    private static void createV4(Context context, boolean withSavedMediaTable) {
        SQLiteDatabase db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE saved_messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "message_id TEXT UNIQUE NOT NULL,"
                + "server_id TEXT,"
                + "client_context TEXT,"
                + "thread_id TEXT NOT NULL,"
                + "sender_id TEXT,"
                + "sender_username TEXT,"
                + "content TEXT,"
                + "message_type TEXT,"
                + "timestamp INTEGER NOT NULL,"
                + "is_deleted INTEGER DEFAULT 0"
                + ")");
        db.execSQL("CREATE INDEX idx_thread_id ON saved_messages(thread_id)");
        db.execSQL("CREATE INDEX idx_is_deleted ON saved_messages(is_deleted)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_server_id ON saved_messages"
                + "(server_id) WHERE server_id IS NOT NULL AND server_id != ''");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_client_context ON saved_messages"
                + "(client_context) WHERE client_context IS NOT NULL AND client_context != ''");
        db.execSQL("CREATE TABLE IF NOT EXISTS user_directory ("
                + "sender_id TEXT PRIMARY KEY,"
                + "username TEXT NOT NULL"
                + ")");
        insertMessages(db);
        if (withSavedMediaTable) {
            db.execSQL("CREATE TABLE saved_media (stale TEXT)");
            db.execSQL("INSERT INTO saved_media (stale) VALUES ('left over')");
        }
        db.setVersion(4);
        db.close();
    }

    private static void insertMessages(SQLiteDatabase db) {
        db.execSQL("INSERT INTO saved_messages (message_id, server_id, client_context, thread_id,"
                + " sender_id, sender_username, content, message_type, timestamp, is_deleted) VALUES"
                + " ('31000000000000000000000000001', '31000000000000000000000000001', 'ctx-1',"
                + " 'thread-1', '1234567', 'alice', 'hello', 'text', 1690000000000, 1),"
                + " ('ctx-2', NULL, 'ctx-2', 'thread-1', '7654321', '',"
                + " 'https://x.fbcdn.net/a.jpg?oe=65A1B2C3', 'media', 1690000001000, 0),"
                + " ('31000000000000000000000000003', '31000000000000000000000000003', NULL,"
                + " 'thread-2', NULL, NULL, NULL, 'unknown', 1690000002000, 0)");
        db.execSQL("INSERT INTO user_directory (sender_id, username)"
                + " VALUES ('1234567', 'alice'), ('7654321', 'bob')");
    }

    /** Schema and every row of every table and index except the skipped ones. */
    private static List<String> dump(Context context, String... skipped) {
        SQLiteDatabase db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        List<String> out = new ArrayList<>();
        Cursor tables = db.rawQuery("SELECT type, name, sql FROM sqlite_master"
                + " WHERE name NOT LIKE 'sqlite_%' ORDER BY name", null);
        List<String> names = new ArrayList<>();
        while (tables.moveToNext()) {
            if (Arrays.asList(skipped).contains(tables.getString(1))) continue;
            out.add(tables.getString(0) + " " + tables.getString(1) + " " + tables.getString(2));
            if ("table".equals(tables.getString(0))) names.add(tables.getString(1));
        }
        tables.close();
        for (String name : names) {
            Cursor rows = db.rawQuery("SELECT * FROM " + name + " ORDER BY rowid", null);
            while (rows.moveToNext()) {
                StringBuilder row = new StringBuilder(name);
                for (int i = 0; i < rows.getColumnCount(); i++) {
                    row.append('|').append(rows.isNull(i) ? "NULL" : rows.getString(i));
                }
                out.add(row.toString());
            }
            rows.close();
        }
        db.close();
        return out;
    }

    private static List<String> columns(SQLiteDatabase db, String table) {
        List<String> out = new ArrayList<>();
        Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        while (cursor.moveToNext()) {
            out.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
        }
        cursor.close();
        return out;
    }

    private static boolean hasIndex(SQLiteDatabase db, String index) {
        Cursor cursor = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?", new String[]{index});
        boolean found = cursor.moveToFirst();
        cursor.close();
        return found;
    }
}
