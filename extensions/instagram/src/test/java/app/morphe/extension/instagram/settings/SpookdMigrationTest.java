package app.morphe.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;
import javax.crypto.spec.SecretKeySpec;

import app.morphe.extension.instagram.patches.dm.vault.MediaVault;
import app.morphe.extension.instagram.patches.dm.vault.VaultFile;
import app.morphe.extension.instagram.patches.download.gallery.GalleryDb;
import app.morphe.extension.instagram.patches.download.gallery.GalleryStore;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class SpookdMigrationTest {
    private static final long NOW = 1_700_000_000_000L;

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
                "[RENAME no_backup/piko_vault -> no_backup/spookd_vault,"
                        + " RENAME databases/piko_dm_vault.db-wal -> databases/spookd_dm_vault.db-wal,"
                        + " RENAME databases/piko_dm_vault.db-shm -> databases/spookd_dm_vault.db-shm,"
                        + " RENAME databases/piko_dm_vault.db -> databases/spookd_dm_vault.db]",
                "[RENAME databases/piko_gallery.db -> databases/spookd_gallery.db]",
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
                "[KEEP_BOTH databases/piko_dm_vault.db -> databases/spookd_dm_vault.db,"
                        + " KEEP_BOTH no_backup/piko_vault -> no_backup/spookd_vault]",
                "[KEEP_BOTH databases/piko_gallery.db -> databases/spookd_gallery.db,"
                        + " KEEP_BOTH no_backup/piko_gallery -> no_backup/spookd_gallery]",
                "[MERGE_DIRECTORY cache/piko_gallery_thumbs -> cache/spookd_gallery_thumbs]",
                "[DELETE files/piko_recommended_flags_v3.json]"
        ), plan(
                "shared_prefs/piko_settings.xml",
                "shared_prefs/spookd_settings.xml",
                "shared_prefs/piko_hidden_chats.xml",
                "shared_prefs/spookd_hidden_chats.xml",
                // Both vault databases: the old media stays even though the new directory is missing.
                "databases/piko_dm_vault.db",
                "databases/spookd_dm_vault.db",
                "no_backup/piko_vault",
                "databases/piko_gallery.db",
                "databases/piko_gallery.db-wal",
                "databases/spookd_gallery.db-wal",
                "no_backup/piko_gallery",
                "no_backup/spookd_gallery",
                "cache/piko_gallery_thumbs",
                "cache/spookd_gallery_thumbs",
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
                "[KEEP_BOTH shared_prefs/piko_dm -> shared_prefs/spookd_dm]",
                "[RENAME shared_prefs/piko_hidden_chats.xml -> shared_prefs/spookd_hidden_chats.xml]",
                "[RENAME databases/piko_dm_vault.db -> databases/spookd_dm_vault.db]",
                "[MERGE_DIRECTORY no_backup/piko_gallery -> no_backup/spookd_gallery]"
        ), plan(
                "shared_prefs/spookd_settings.xml",
                "shared_prefs/piko_hidden_chats.xml",
                // Without a journal entry this is a conflict: the new .bak would override the old file.
                "shared_prefs/spookd_dm.xml.bak",
                "shared_prefs/piko_dm.xml",
                // The media and sidecars moved, the database did not.
                "databases/piko_dm_vault.db",
                "databases/spookd_dm_vault.db-wal",
                "databases/spookd_dm_vault.db-shm",
                "no_backup/spookd_vault",
                // The database moved, part of its media did not.
                "databases/spookd_gallery.db",
                "no_backup/piko_gallery",
                "no_backup/spookd_gallery"));
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
    public void aJournaledMoveStoppedAfterTheBackupResumesForward() throws Exception {
        writePrefs("piko_settings.xml", "<string name=\"k\">stale</string>"
                + "<boolean name=\"enable_developer_options\" value=\"true\" />");
        writePrefs("piko_settings.xml.bak", "<string name=\"k\">bak</string>");
        // The first start journaled the group and died after its first rename.
        SpookdMigration.writeJournal(app, Arrays.asList(
                rename("shared_prefs/piko_settings.xml.bak", "shared_prefs/spookd_settings.xml.bak"),
                rename("shared_prefs/piko_settings.xml", "shared_prefs/spookd_settings.xml")));
        assertTrue(prefsFile("piko_settings.xml.bak").renameTo(prefsFile("spookd_settings.xml.bak")));

        SpookdMigration.run(app);

        assertFalse(prefsFile("piko_settings.xml").exists());
        assertFalse(journalFile().exists());
        SharedPreferences live = prefs("spookd_settings");
        assertEquals("bak", live.getString("k", null));
        assertFalse(live.contains("enable_developer_options"));
        assertEquals(1, live.getAll().size());
        assertTrue(marker().exists());
    }

    @Test
    public void oldXmlAndNewBackupWithoutAJournalEntryKeepBoth() throws Exception {
        writePrefs("piko_settings.xml", "<string name=\"piko_account_owner\">1</string>"
                + "<string name=\"k\">one</string>");
        writePrefs("spookd_settings.xml.bak", "<string name=\"spookd_account_owner\">2</string>");

        SpookdMigration.run(app);

        assertTrue(prefsFile("piko_settings.xml").exists());
        assertTrue(readFile(prefsFile("piko_settings.xml")).contains(">one<"));
        SharedPreferences live = prefs("spookd_settings");
        assertEquals("2", live.getString("spookd_account_owner", null));
        assertFalse(live.contains("k"));
        assertTrue(marker().exists());
    }

    @Test
    public void aJournaledGroupThatCannotFinishMovesBackAtTheNextStart() throws Exception {
        vaultDb("piko_dm_vault.db", 1);
        writeFile(new File(app.getNoBackupFilesDir(), "piko_vault/1.pkv"), "one");
        SpookdMigration.writeJournal(app, Arrays.asList(
                rename("no_backup/piko_vault", "no_backup/spookd_vault"),
                rename("databases/piko_dm_vault.db", "databases/spookd_dm_vault.db")));
        assertTrue(new File(app.getNoBackupFilesDir(), "piko_vault")
                .renameTo(new File(app.getNoBackupFilesDir(), "spookd_vault")));
        // Something created the new database meanwhile, so the database cannot follow its media.
        vaultDb("spookd_dm_vault.db", 2);

        SpookdMigration.run(app);

        assertEquals("one", readFile(new File(app.getNoBackupFilesDir(), "piko_vault/1.pkv")));
        assertFalse(new File(app.getNoBackupFilesDir(), "spookd_vault").exists());
        assertEquals(Arrays.asList("stored"), column("piko_dm_vault.db", "SELECT state FROM saved_media"));
        assertFalse(journalFile().exists());
        // With the pair whole again, both databases exist: a kept conflict.
        assertTrue(marker().exists());
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
    public void aFailedDeleteIsRetriedAtTheNextStart() throws Exception {
        File share = new File(app.getCacheDir(), "piko_share");
        writeFile(new File(share, "d.jpg"), "D");
        // A read-only folder keeps its files, so the delete fails.
        assertTrue(share.setWritable(false));
        try {
            SpookdMigration.run(app);
        } finally {
            share.setWritable(true);
        }
        assertTrue(share.exists());
        assertFalse(marker().exists());

        SpookdMigration.run(app);

        assertFalse(share.exists());
        assertTrue(marker().exists());
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

    // Databases with their media, followed by each consumer's own recovery.

    @Test
    public void galleryPairMovesAndRecoveryKeepsItsMedia() throws Exception {
        galleryDb("piko_gallery.db", "a.jpg");
        writeFile(new File(app.getNoBackupFilesDir(), "piko_gallery/a.jpg"), "A");

        SpookdMigration.run(app);
        recoverGallery();

        assertEquals("A", readFile(new File(app.getNoBackupFilesDir(), "spookd_gallery/a.jpg")));
        assertEquals(Arrays.asList("a.jpg"), column("spookd_gallery.db", "SELECT file FROM gallery_items"));
    }

    @Test
    public void galleryConflictKeepsTheOldMediaWithTheOldDatabase() throws Exception {
        galleryDb("piko_gallery.db", "a.jpg");
        writeFile(new File(app.getNoBackupFilesDir(), "piko_gallery/a.jpg"), "A");
        galleryDb("spookd_gallery.db", "b.jpg");
        writeFile(new File(app.getNoBackupFilesDir(), "spookd_gallery/b.jpg"), "B");

        SpookdMigration.run(app);
        recoverGallery();

        assertEquals("A", readFile(new File(app.getNoBackupFilesDir(), "piko_gallery/a.jpg")));
        assertEquals("B", readFile(new File(app.getNoBackupFilesDir(), "spookd_gallery/b.jpg")));
        assertFalse(new File(app.getNoBackupFilesDir(), "spookd_gallery/a.jpg").exists());
        assertEquals(Arrays.asList("a.jpg"), column("piko_gallery.db", "SELECT file FROM gallery_items"));
        assertTrue(marker().exists());
    }

    @Test
    public void vaultPairMovesAndRecoveryKeepsItsMedia() throws Exception {
        vaultDb("piko_dm_vault.db", 1);
        writeFile(new File(app.getNoBackupFilesDir(), "piko_vault/1.pkv"), "one");

        SpookdMigration.run(app);
        recoverVault();

        assertEquals("one", readFile(new File(app.getNoBackupFilesDir(), "spookd_vault/1.pkv")));
        assertEquals(Arrays.asList("stored"), column("spookd_dm_vault.db", "SELECT state FROM saved_media"));
    }

    @Test
    public void vaultConflictKeepsTheOldMediaWithTheOldDatabase() throws Exception {
        vaultDb("piko_dm_vault.db", 1);
        writeFile(new File(app.getNoBackupFilesDir(), "piko_vault/1.pkv"), "one");
        vaultDb("spookd_dm_vault.db", 2);
        writeFile(new File(app.getNoBackupFilesDir(), "spookd_vault/2.pkv"), "two");

        SpookdMigration.run(app);
        recoverVault();

        assertEquals("one", readFile(new File(app.getNoBackupFilesDir(), "piko_vault/1.pkv")));
        assertEquals("two", readFile(new File(app.getNoBackupFilesDir(), "spookd_vault/2.pkv")));
        assertFalse(new File(app.getNoBackupFilesDir(), "spookd_vault/1.pkv").exists());
        assertEquals(Arrays.asList("stored"), column("piko_dm_vault.db", "SELECT state FROM saved_media"));
    }

    @Test
    public void aDatabaseThatCannotMoveTakesItsMediaBack() throws Exception {
        vaultDb("piko_dm_vault.db", 1);
        writeFile(new File(app.getNoBackupFilesDir(), "piko_vault/1.pkv"), "one");
        File databases = app.getDatabasePath("piko_dm_vault.db").getParentFile();
        // A read-only databases directory makes the database rename fail after the media moved.
        assertTrue(databases.setWritable(false));
        try {
            SpookdMigration.run(app);
        } finally {
            databases.setWritable(true);
        }

        assertEquals("one", readFile(new File(app.getNoBackupFilesDir(), "piko_vault/1.pkv")));
        assertFalse(new File(app.getNoBackupFilesDir(), "spookd_vault").exists());
        assertTrue(app.getDatabasePath("piko_dm_vault.db").exists());
        assertFalse(marker().exists());

        SpookdMigration.run(app);
        recoverVault();

        assertEquals("one", readFile(new File(app.getNoBackupFilesDir(), "spookd_vault/1.pkv")));
        assertEquals(Arrays.asList("stored"), column("spookd_dm_vault.db", "SELECT state FROM saved_media"));
    }

    /** A gallery database with the real schema and one row for {@code file}. */
    private void galleryDb(String name, String file) throws Exception {
        Constructor<GalleryDb> constructor = GalleryDb.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        try (SQLiteDatabase db = openDb(name)) {
            constructor.newInstance(app).onCreate(db);
            db.setVersion(1);
            ContentValues values = new ContentValues();
            values.put("file", file);
            values.put("tab", "posts");
            values.put("kind", "photo");
            values.put("file_name", file);
            values.put("saved_at", NOW);
            values.put("size_bytes", 1);
            db.insertOrThrow("gallery_items", null, values);
        }
    }

    /** A vault database with the saved_media columns and one stored row with {@code id}. */
    private void vaultDb(String name, long id) {
        try (SQLiteDatabase db = openDb(name)) {
            db.execSQL("CREATE TABLE saved_media (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " item_id TEXT NOT NULL UNIQUE, account_id TEXT, kind TEXT NOT NULL, url TEXT,"
                    + " url_expires_at INTEGER NOT NULL, msg_timestamp INTEGER NOT NULL, state TEXT NOT NULL,"
                    + " attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0,"
                    + " size_bytes INTEGER NOT NULL DEFAULT 0, mime TEXT)");
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("item_id", "item" + id);
            values.put("kind", "photo");
            values.put("url_expires_at", NOW);
            values.put("msg_timestamp", NOW);
            values.put("state", "stored");
            values.put("size_bytes", 3);
            db.insertOrThrow("saved_media", null, values);
        }
    }

    private SQLiteDatabase openDb(String name) {
        File file = app.getDatabasePath(name);
        file.getParentFile().mkdirs();
        return SQLiteDatabase.openOrCreateDatabase(file, null);
    }

    private List<String> column(String dbName, String sql) {
        List<String> values = new ArrayList<>();
        try (SQLiteDatabase db = openDb(dbName); Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) values.add(cursor.getString(0));
        }
        return values;
    }

    /** The gallery's startup recovery on the new database and directory, as {@code GalleryStore.get()} opens them. */
    private void recoverGallery() throws Exception {
        Constructor<GalleryStore> constructor = GalleryStore.class.getDeclaredConstructor(
                SQLiteDatabase.class, File.class, File.class, LongSupplier.class);
        constructor.setAccessible(true);
        try (SQLiteDatabase db = openDb("spookd_gallery.db")) {
            GalleryStore store = constructor.newInstance(db, new File(app.getNoBackupFilesDir(), "spookd_gallery"),
                    new File(app.getCacheDir(), "spookd_gallery_thumbs"), (LongSupplier) () -> NOW);
            store.exists("none", "none");
        }
    }

    /** The vault's startup recovery on the new database and directory, as {@code MediaVault.get()} opens them. */
    private void recoverVault() throws Exception {
        Class<?> storeClass = Class.forName("app.morphe.extension.instagram.patches.dm.vault.VaultStore");
        Constructor<?> storeConstructor = storeClass.getDeclaredConstructor(SQLiteDatabase.class);
        storeConstructor.setAccessible(true);
        Constructor<MediaVault> constructor = MediaVault.class.getDeclaredConstructor(storeClass,
                Class.forName("app.morphe.extension.instagram.patches.dm.vault.MediaVault$ConnectionOpener"),
                LongSupplier.class, VaultFile.Kek.class, File.class, ScheduledExecutorService.class);
        constructor.setAccessible(true);
        Method recover = MediaVault.class.getDeclaredMethod("recover");
        recover.setAccessible(true);
        try (SQLiteDatabase db = openDb("spookd_dm_vault.db")) {
            VaultFile.Kek kek = () -> new SecretKeySpec(new byte[32], "AES");
            MediaVault vault = constructor.newInstance(storeConstructor.newInstance(db), null,
                    (LongSupplier) () -> NOW, kek, new File(app.getNoBackupFilesDir(), "spookd_vault"), null);
            recover.invoke(vault);
        }
    }

    private static SpookdMigration.Move rename(String from, String to) {
        return new SpookdMigration.Move(SpookdMigration.Op.RENAME, from, to);
    }

    private File journalFile() {
        return new File(app.getFilesDir(), "spookd_migration_journal");
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
