package app.morphe.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class SpookdMigrationTest {
    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    // Plan computation.

    @Test
    public void planRenamesEveryOldNameInOrder() {
        assertEquals(Arrays.asList(
                "[RENAME shared_prefs/piko_dm.xml -> shared_prefs/spookd_dm.xml]",
                "[RENAME shared_prefs/piko_settings.xml.bak -> shared_prefs/spookd_settings.xml.bak,"
                        + " RENAME shared_prefs/piko_settings.xml -> shared_prefs/spookd_settings.xml]",
                "[RENAME shared_prefs/piko_settings_42.xml -> shared_prefs/spookd_settings_42.xml]",
                "[RENAME shared_prefs/piko_settings_accounts.xml -> shared_prefs/spookd_settings_accounts.xml]",
                "[RENAME databases/piko_dm_vault.db-wal -> databases/spookd_dm_vault.db-wal,"
                        + " RENAME databases/piko_dm_vault.db-shm -> databases/spookd_dm_vault.db-shm,"
                        + " RENAME databases/piko_dm_vault.db -> databases/spookd_dm_vault.db]",
                "[RENAME databases/piko_gallery.db -> databases/spookd_gallery.db]",
                "[RENAME no_backup/piko_vault -> no_backup/spookd_vault]",
                "[RENAME cache/piko_gallery_thumbs -> cache/spookd_gallery_thumbs]",
                "[DELETE cache/piko_share]",
                "[RENAME files/piko_recommended_flags_v3.json -> files/spookd_recommended_flags_v3.json]"
        ), plan(
                "shared_prefs/piko_settings_accounts.xml",
                "shared_prefs/piko_settings.xml",
                "shared_prefs/piko_settings.xml.bak",
                "shared_prefs/piko_settings_42.xml",
                "shared_prefs/piko_dm.xml",
                "shared_prefs/piko_unknown.xml",
                "databases/piko_dm_vault.db",
                "databases/piko_dm_vault.db-shm",
                "databases/piko_dm_vault.db-wal",
                "databases/piko_gallery.db",
                // A sidecar without its database is left alone.
                "databases/piko_instants_vault.db-journal",
                "no_backup/piko_vault",
                "cache/piko_gallery_thumbs",
                "cache/piko_share",
                "files/piko_recommended_flags_v3.json"));
    }

    @Test
    public void planMergesOrKeepsWhenBothNamesExist() {
        assertEquals(Arrays.asList(
                "[MERGE_PREFS shared_prefs/piko_hidden_chats -> shared_prefs/spookd_hidden_chats]",
                "[MERGE_PREFS shared_prefs/piko_settings -> shared_prefs/spookd_settings]",
                "[KEEP_BOTH databases/piko_dm_vault.db -> databases/spookd_dm_vault.db]",
                "[KEEP_BOTH databases/piko_gallery.db -> databases/spookd_gallery.db]",
                "[MERGE_DIRECTORY no_backup/piko_gallery -> no_backup/spookd_gallery]",
                "[DELETE files/piko_recommended_flags_v3.json]"
        ), plan(
                "shared_prefs/piko_settings.xml",
                "shared_prefs/spookd_settings.xml",
                "shared_prefs/piko_hidden_chats.xml",
                "shared_prefs/spookd_hidden_chats.xml.bak",
                "databases/piko_dm_vault.db",
                "databases/spookd_dm_vault.db",
                "databases/piko_gallery.db",
                "databases/piko_gallery.db-wal",
                "databases/spookd_gallery.db-wal",
                "no_backup/piko_gallery",
                "no_backup/spookd_gallery",
                "files/piko_recommended_flags_v3.json",
                "files/spookd_recommended_flags_v3.json"));
    }

    @Test
    public void planFinishesOnlyWhatAPartialRunLeft() {
        assertEquals(Collections.emptyList(), plan(
                "shared_prefs/spookd_settings.xml",
                "shared_prefs/spookd_settings_accounts.xml",
                "databases/spookd_dm_vault.db",
                "databases/spookd_dm_vault.db-wal",
                "no_backup/spookd_vault"));
        assertEquals(Arrays.asList(
                "[RENAME shared_prefs/piko_hidden_chats.xml -> shared_prefs/spookd_hidden_chats.xml]",
                "[RENAME databases/piko_dm_vault.db -> databases/spookd_dm_vault.db]"
        ), plan(
                "shared_prefs/spookd_settings.xml",
                "shared_prefs/piko_hidden_chats.xml",
                // The sidecars moved, the database did not.
                "databases/piko_dm_vault.db",
                "databases/spookd_dm_vault.db-wal",
                "databases/spookd_dm_vault.db-shm",
                "no_backup/spookd_vault"));
    }

    // Import of old exports.

    @Test
    public void renameLegacyKeysRenamesOnlyTheThreeNames() {
        String export = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                + "    <string name=\"piko_account_owner\">17</string>\n"
                + "    <boolean name=\"piko_debug\" value=\"true\" />\n"
                + "    <boolean name=\"first_time_piko\" value=\"false\" />\n"
                + "    <boolean name=\"piko_debug_extra\" value=\"true\" />\n"
                + "    <string name=\"note\">name=&quot;piko_debug&quot;</string>\n"
                + "</map>\n";
        String renamed = SpookdMigration.renameLegacyKeys(export);
        assertEquals(export
                .replace("name=\"piko_account_owner\"", "name=\"spookd_account_owner\"")
                .replace("name=\"piko_debug\"", "name=\"spookd_debug\"")
                .replace("name=\"first_time_piko\"", "name=\"first_time_spookd\""), renamed);

        writeFile(prefsFile("spookd_settings.xml"), renamed);
        SharedPreferences prefs = prefs("spookd_settings");
        assertEquals("17", prefs.getString("spookd_account_owner", null));
        assertTrue(prefs.getBoolean("spookd_debug", false));
        assertFalse(prefs.getBoolean("first_time_spookd", true));
        assertTrue(prefs.getBoolean("piko_debug_extra", false));
        assertEquals("name=\"piko_debug\"", prefs.getString("note", null));
        assertEquals(5, prefs.getAll().size());
    }

    // The whole run on disk.

    @Test
    public void runMovesEveryStoredName() throws Exception {
        writePrefs("piko_settings.xml", "<string name=\"piko_account_owner\">1</string>"
                + "<boolean name=\"piko_debug\" value=\"true\" />"
                + "<boolean name=\"first_time_piko\" value=\"false\" />"
                + "<int name=\"hidden_chats_taps\" value=\"5\" />"
                + "<long name=\"focus_lock_until\" value=\"99\" />"
                + "<set name=\"action_bar_main_feed\"><string>A</string><string>B</string></set>");
        writePrefs("piko_settings_2.xml", "<string name=\"piko_account_owner\">2</string>"
                + "<boolean name=\"disable_ads\" value=\"false\" />");
        writePrefs("piko_settings_3.xml", "<string name=\"piko_account_owner\">3</string>");
        writePrefs("piko_settings_accounts.xml", "<boolean name=\"adopt_live_file\" value=\"true\" />");
        String[] fixed = {"recommended_flags_v3", "hidden_chats", "ephemeral_media_viewed", "ephemeral_modes",
                "profile_analyzer", "dm"};
        for (String name : fixed) {
            writePrefs("piko_" + name + ".xml", "<string name=\"k\">" + name + "</string>");
        }

        SpookdMigration.run(app);

        SharedPreferences live = prefs("spookd_settings");
        assertEquals("1", live.getString("spookd_account_owner", null));
        assertTrue(live.getBoolean("spookd_debug", false));
        assertFalse(live.getBoolean("first_time_spookd", true));
        assertEquals(5, live.getInt("hidden_chats_taps", 0));
        assertEquals(99L, live.getLong("focus_lock_until", 0));
        assertEquals(new HashSet<>(Arrays.asList("A", "B")), live.getStringSet("action_bar_main_feed", null));
        assertEquals(6, live.getAll().size());
        assertEquals("2", prefs("spookd_settings_2").getString("spookd_account_owner", null));
        assertFalse(prefs("spookd_settings_2").getBoolean("disable_ads", true));
        assertFalse(prefs("spookd_settings_2").contains("piko_account_owner"));
        assertEquals("3", prefs("spookd_settings_3").getString("spookd_account_owner", null));
        assertTrue(prefs("spookd_settings_accounts").getBoolean("adopt_live_file", false));
        for (String name : fixed) {
            assertEquals(name, prefs("spookd_" + name).getString("k", null));
            assertFalse(prefsFile("piko_" + name + ".xml").exists());
        }
        for (String name : new String[]{"piko_settings", "piko_settings_2", "piko_settings_3", "piko_settings_accounts"}) {
            assertFalse(prefsFile(name + ".xml").exists());
        }
        assertTrue(marker().exists());
    }

    @Test
    public void backupFilesMoveAndStayAuthoritative() throws Exception {
        writePrefs("piko_hidden_chats.xml.bak", "<string name=\"k\">bak only</string>");
        writePrefs("piko_dm.xml", "<string name=\"k\">stale</string>");
        writePrefs("piko_dm.xml.bak", "<string name=\"k\">bak</string>");

        SpookdMigration.run(app);

        assertTrue(prefsFile("spookd_hidden_chats.xml.bak").exists());
        assertTrue(prefsFile("spookd_dm.xml.bak").exists());
        assertTrue(prefsFile("spookd_dm.xml").exists());
        assertFalse(prefsFile("piko_hidden_chats.xml.bak").exists());
        assertFalse(prefsFile("piko_dm.xml.bak").exists());
        assertFalse(prefsFile("piko_dm.xml").exists());
        assertEquals("bak only", prefs("spookd_hidden_chats").getString("k", null));
        assertEquals("bak", prefs("spookd_dm").getString("k", null));
    }

    @Test
    public void databaseWithSidecarsOpensUnderTheNewName() throws Exception {
        File old = app.getDatabasePath("piko_dm_vault.db");
        old.getParentFile().mkdirs();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(old, null);
        db.execSQL("CREATE TABLE t (v TEXT)");
        db.execSQL("INSERT INTO t (v) VALUES ('a'), ('b')");
        db.close();
        writeFile(new File(old.getPath() + "-wal"), "");
        writeFile(new File(old.getPath() + "-shm"), "");

        SpookdMigration.run(app);

        File moved = app.getDatabasePath("spookd_dm_vault.db");
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            assertFalse(new File(old.getPath() + suffix).exists());
            assertTrue(new File(moved.getPath() + suffix).exists());
        }
        db = SQLiteDatabase.openDatabase(moved.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        List<String> rows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT v FROM t ORDER BY v", null)) {
            while (cursor.moveToNext()) rows.add(cursor.getString(0));
        }
        db.close();
        assertEquals(Arrays.asList("a", "b"), rows);
    }

    @Test
    public void directoriesKeepTheirFilesAndTheShareCacheIsDeleted() throws Exception {
        writeFile(new File(app.getNoBackupFilesDir(), "piko_vault/a.bin"), "A");
        writeFile(new File(app.getNoBackupFilesDir(), "piko_gallery/b.jpg"), "B");
        writeFile(new File(app.getCacheDir(), "piko_gallery_thumbs/c.jpg"), "C");
        writeFile(new File(app.getCacheDir(), "piko_share/f00/d.jpg"), "D");
        writeFile(new File(app.getFilesDir(), "piko_recommended_flags_v3.json"), "[]");

        SpookdMigration.run(app);

        assertEquals("A", readFile(new File(app.getNoBackupFilesDir(), "spookd_vault/a.bin")));
        assertEquals("B", readFile(new File(app.getNoBackupFilesDir(), "spookd_gallery/b.jpg")));
        assertEquals("C", readFile(new File(app.getCacheDir(), "spookd_gallery_thumbs/c.jpg")));
        assertEquals("[]", readFile(new File(app.getFilesDir(), "spookd_recommended_flags_v3.json")));
        assertFalse(new File(app.getNoBackupFilesDir(), "piko_vault").exists());
        assertFalse(new File(app.getNoBackupFilesDir(), "piko_gallery").exists());
        assertFalse(new File(app.getCacheDir(), "piko_gallery_thumbs").exists());
        assertFalse(new File(app.getCacheDir(), "piko_share").exists());
        assertFalse(new File(app.getFilesDir(), "piko_recommended_flags_v3.json").exists());
    }

    @Test
    public void halfMigratedStateCompletesAndASecondRunChangesNothing() throws Exception {
        writePrefs("spookd_settings.xml", "<string name=\"piko_account_owner\">1</string>"
                + "<boolean name=\"spookd_debug\" value=\"false\" />"
                + "<boolean name=\"piko_debug\" value=\"true\" />");
        writePrefs("piko_hidden_chats.xml", "<string name=\"k\">v</string>");
        File db = app.getDatabasePath("piko_dm_vault.db");
        writeFile(db, "main");
        writeFile(new File(app.getDatabasePath("spookd_dm_vault.db").getPath() + "-wal"), "wal");

        SpookdMigration.run(app);

        SharedPreferences live = prefs("spookd_settings");
        assertEquals("1", live.getString("spookd_account_owner", null));
        assertFalse(live.getBoolean("spookd_debug", true));
        assertEquals(2, live.getAll().size());
        assertEquals("v", prefs("spookd_hidden_chats").getString("k", null));
        assertEquals("main", readFile(app.getDatabasePath("spookd_dm_vault.db")));
        assertFalse(db.exists());
        assertTrue(marker().exists());

        Map<String, String> before = snapshot();
        SpookdMigration.run(app);
        assertTrue(marker().delete());
        SpookdMigration.run(app);
        assertEquals(before, snapshot());
        assertTrue(marker().exists());
    }

    @Test
    public void afterTheMarkerOldNamesAreLeftAlone() throws Exception {
        SpookdMigration.run(app);
        assertTrue(marker().exists());
        writePrefs("piko_hidden_chats.xml", "<string name=\"k\">v</string>");

        SpookdMigration.run(app);

        assertTrue(prefsFile("piko_hidden_chats.xml").exists());
        assertFalse(prefsFile("spookd_hidden_chats.xml").exists());
    }

    @Test
    public void bothNamesFollowTheConflictRules() throws Exception {
        writePrefs("piko_hidden_chats.xml", "<string name=\"a\">old</string><string name=\"b\">old</string>");
        writePrefs("spookd_hidden_chats.xml", "<string name=\"b\">new</string>");
        writePrefs("piko_settings.xml", "<string name=\"piko_account_owner\">1</string>"
                + "<string name=\"k\">one</string>");
        writePrefs("spookd_settings.xml", "<string name=\"spookd_account_owner\">2</string>");
        writeFile(app.getDatabasePath("piko_gallery.db"), "old db");
        writeFile(app.getDatabasePath("spookd_gallery.db"), "new db");
        writeFile(new File(app.getNoBackupFilesDir(), "piko_vault/a"), "old a");
        writeFile(new File(app.getNoBackupFilesDir(), "piko_vault/b"), "old b");
        writeFile(new File(app.getNoBackupFilesDir(), "spookd_vault/b"), "new b");

        SpookdMigration.run(app);

        SharedPreferences chats = prefs("spookd_hidden_chats");
        assertEquals("old", chats.getString("a", null));
        assertEquals("new", chats.getString("b", null));
        assertFalse(prefsFile("piko_hidden_chats.xml").exists());

        assertTrue(prefsFile("piko_settings.xml").exists());
        SharedPreferences live = prefs("spookd_settings");
        assertEquals("2", live.getString("spookd_account_owner", null));
        assertFalse(live.contains("k"));

        assertEquals("old db", readFile(app.getDatabasePath("piko_gallery.db")));
        assertEquals("new db", readFile(app.getDatabasePath("spookd_gallery.db")));

        assertEquals("old a", readFile(new File(app.getNoBackupFilesDir(), "spookd_vault/a")));
        assertEquals("new b", readFile(new File(app.getNoBackupFilesDir(), "spookd_vault/b")));
        assertEquals("old b", readFile(new File(app.getNoBackupFilesDir(), "piko_vault/b")));
        assertTrue(marker().exists());
    }

    @Test
    public void aFailedStepKeepsTheOldDataAndTheMarkerUnwritten() throws Exception {
        writeFile(new File(app.getNoBackupFilesDir(), "piko_vault/a"), "A");
        // A file where the directory should be makes every child move fail.
        writeFile(new File(app.getNoBackupFilesDir(), "spookd_vault"), "not a directory");
        writePrefs("piko_dm.xml", "<string name=\"k\">v</string>");

        SpookdMigration.run(app);

        assertEquals("A", readFile(new File(app.getNoBackupFilesDir(), "piko_vault/a")));
        assertEquals("v", prefs("spookd_dm").getString("k", null));
        assertFalse(marker().exists());
    }

    @Test
    public void notificationChannelsKeepTheirImportance() {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("piko_profile_analyzer", "Profile analyzer",
                NotificationManager.IMPORTANCE_MIN));
        manager.createNotificationChannel(new NotificationChannel("piko_deleted_messages", "Deleted messages",
                NotificationManager.IMPORTANCE_HIGH));
        manager.createNotificationChannel(new NotificationChannel("spookd_deleted_messages", "Deleted messages",
                NotificationManager.IMPORTANCE_DEFAULT));

        SpookdMigration.run(app);

        NotificationChannel analyzer = manager.getNotificationChannel("spookd_profile_analyzer");
        assertEquals(NotificationManager.IMPORTANCE_MIN, analyzer.getImportance());
        assertEquals("Profile analyzer", analyzer.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,
                manager.getNotificationChannel("spookd_deleted_messages").getImportance());
        assertNull(manager.getNotificationChannel("piko_profile_analyzer"));
        assertNull(manager.getNotificationChannel("piko_deleted_messages"));
        assertTrue(marker().exists());
    }

    private static List<String> plan(String... existing) {
        List<String> result = new ArrayList<>();
        for (List<SpookdMigration.Move> group : SpookdMigration.plan(new HashSet<>(Arrays.asList(existing)))) {
            result.add(group.toString());
        }
        return result;
    }

    private SharedPreferences prefs(String name) {
        return app.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private File prefsFile(String fileName) {
        return new File(app.getDataDir(), "shared_prefs/" + fileName);
    }

    private File marker() {
        return new File(app.getNoBackupFilesDir(), "spookd_migration_v1");
    }

    private void writePrefs(String fileName, String entries) {
        writeFile(prefsFile(fileName),
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + entries + "\n</map>\n");
    }

    /** Every file under the data directory with its contents. */
    private Map<String, String> snapshot() throws IOException {
        Map<String, String> result = new TreeMap<>();
        collect(app.getDataDir(), "", result);
        return result;
    }

    private static void collect(File dir, String prefix, Map<String, String> result) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, prefix + child.getName() + "/", result);
            else result.put(prefix + child.getName(), readFile(child));
        }
    }

    private static void writeFile(File file, String content) {
        try {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
