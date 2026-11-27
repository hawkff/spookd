package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class GalleryArchiveTest {
    private static final long NOW = 1_700_000_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final long[] clock = {NOW};
    private GalleryDb helper;
    private SQLiteDatabase db;
    private GalleryStore store;

    @Before
    public void setUp() throws Exception {
        helper = new GalleryDb(RuntimeEnvironment.getApplication());
        db = helper.getWritableDatabase();
        store = new GalleryStore(db, tmp.newFolder("piko_gallery"), tmp.newFolder("piko_gallery_thumbs"),
                () -> clock[0]);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void aBackupRestoresEveryDetailAfterAClear() throws Exception {
        long post = save(new GalleryItem(GalleryTab.POSTS, GalleryItem.PHOTO, "alice", "11", "22",
                "https://www.instagram.com/p/abc/", "33", 5000L), "alice", "alice_1.jpg", "photo bytes");
        store.setFavorite(false, post, true);
        clock[0] += 60_000;
        save(new GalleryItem(GalleryTab.REELS, GalleryItem.VIDEO, null, null, null, null, null, null),
                "bob/reels", "clip.mp4", "video bytes");
        byte[] backup = backup();

        assertTrue(store.deleteAll());
        clock[0] += 3_600_000;
        GalleryArchive.Result result = restore(backup);

        assertEquals(2, result.imported);
        assertEquals(0, result.duplicates);
        assertEquals(0, result.failed);
        List<GalleryStore.Entry> entries = store.list(null, null, false, null, GalleryStore.SORT_OLDEST);
        assertEquals(2, entries.size());
        GalleryStore.Entry photo = entries.get(0);
        assertEquals(GalleryTab.POSTS, photo.item.tab);
        assertEquals(GalleryItem.PHOTO, photo.item.kind);
        assertEquals("alice", photo.item.ownerUsername);
        assertEquals("11", photo.item.ownerId);
        assertEquals("22", photo.item.mediaId);
        assertEquals("https://www.instagram.com/p/abc/", photo.item.link);
        assertEquals("33", photo.item.accountId);
        assertEquals(Long.valueOf(5000L), photo.item.takenAt);
        assertEquals("alice", photo.subFolder);
        assertEquals("alice_1.jpg", photo.fileName);
        assertEquals(NOW, photo.savedAt);
        assertTrue(photo.favorite);
        assertEquals("photo bytes", read(photo.file));
        GalleryStore.Entry video = entries.get(1);
        assertEquals(GalleryTab.REELS, video.item.tab);
        assertEquals(GalleryItem.VIDEO, video.item.kind);
        assertNull(video.item.ownerUsername);
        assertNull(video.item.mediaId);
        assertNull(video.item.takenAt);
        assertEquals("bob/reels", video.subFolder);
        assertEquals(NOW + 60_000, video.savedAt);
        assertFalse(video.favorite);
        assertEquals("video bytes", read(video.file));
    }

    @Test
    public void importingAgainSkipsItemsByMediaIdOrIdenticalBytes() throws Exception {
        save(new GalleryItem(GalleryTab.POSTS, GalleryItem.PHOTO, "alice", null, "22", null, null, null),
                null, "a.jpg", "with media id");
        save(new GalleryItem(GalleryTab.POSTS, GalleryItem.PHOTO, null, null, null, null, null, null),
                null, "b.jpg", "without media id");
        byte[] backup = backup();

        GalleryArchive.Result result = restore(backup);

        assertEquals(0, result.imported);
        assertEquals(2, result.duplicates);
        assertEquals(0, result.failed);
        assertEquals(2, store.totals()[0]);
    }

    @Test
    public void duplicatesCompareBytesOnlyAgainstFilesOfTheSameSize() throws Exception {
        save(new GalleryItem(GalleryTab.POSTS, GalleryItem.PHOTO, null, null, null, null, null, null),
                null, "a.jpg", "abcd");
        save(new GalleryItem(GalleryTab.POSTS, GalleryItem.PHOTO, null, null, null, null, null, null),
                null, "gone.jpg", "wxyz12");
        List<GalleryStore.Entry> entries = store.list(null, null, false, null, GalleryStore.SORT_OLDEST);
        assertTrue(entries.get(1).file.delete());
        GalleryArchive.Duplicates duplicates = new GalleryArchive.Duplicates(entries);

        assertTrue(duplicates.contains(4, hash("abcd")));
        assertFalse(duplicates.contains(4, hash("abce")));
        // A gallery file that cannot be read is no duplicate.
        assertFalse(duplicates.contains(6, hash("wxyz12")));
        assertFalse(duplicates.contains(5, hash("new!!")));
        duplicates.add(5, hash("new!!"));
        assertTrue(duplicates.contains(5, hash("new!!")));
    }

    @Test
    public void aDamagedOrForeignFileImportsNothing() throws Exception {
        String valid = manifest(item("a.jpg"));
        assertInvalid("not a zip".getBytes(StandardCharsets.UTF_8));
        assertInvalid(zip(entries("media/a.jpg", "x", GalleryArchive.MANIFEST, valid)));
        assertInvalid(zip(entries(GalleryArchive.MANIFEST, "{not json", "media/a.jpg", "x")));
        assertInvalid(zip(entries(GalleryArchive.MANIFEST,
                new JSONObject(valid).put("schema", 2).toString(), "media/a.jpg", "x")));
        assertInvalid(zip(entries(GalleryArchive.MANIFEST, "{\"schema\":1}", "media/a.jpg", "x")));
        assertInvalid(zip(entries(GalleryArchive.MANIFEST, manifest(item("a.jpg"), item("a.jpg")),
                "media/a.jpg", "x")));
        assertEquals(0, store.totals()[0]);
    }

    @Test
    public void unsafeOrUnknownItemsAreLeftOut() throws Exception {
        assertEquals(0, GalleryArchive.parse(manifest(item("a.jpg"))).invalid);
        assertLeftOut(item("../a.jpg"));
        assertLeftOut(item("a/b.jpg"));
        assertLeftOut(item("a.jpg").put("fileName", "../a.jpg"));
        assertLeftOut(item("a.jpg").put("fileName", "a.exe"));
        assertLeftOut(item("a.jpg").put("subFolder", "alice/../.."));
        assertLeftOut(item("a.jpg").put("subFolder", "a\\b"));
        assertLeftOut(item("a.jpg").put("tab", "secret"));
        assertLeftOut(item("a.jpg").put("kind", "program"));
        assertLeftOut(item("a.jpg").put("link", "javascript:alert(1)"));
        assertLeftOut(item("a.jpg").put("savedAt", "1"));
        JSONObject noTime = item("a.jpg");
        noTime.remove("savedAt");
        assertLeftOut(noTime);
        assertLeftOut(item("a.jpg").put("savedAt", -1));
        assertLeftOut(item("a.jpg").put("owner", 5));
        assertLeftOut(item("a.jpg").put("favorite", "yes"));
        // A name without a suffix and a nested subfolder are still fine.
        assertEquals(0, GalleryArchive.parse(manifest(item("a").put("fileName", "plain")
                .put("subFolder", "alice/stories"))).invalid);
    }

    @Test
    public void entriesOutsideTheManifestAreNeverWritten() throws Exception {
        byte[] archive = zip(entries(
                GalleryArchive.MANIFEST, manifest(item("a.jpg"), item("b.jpg"), item("../c.jpg")),
                "media/a.jpg", "first",
                "media/../c.jpg", "escape",
                "other.txt", "stray"));

        GalleryArchive.Result result = restore(archive);

        assertEquals(1, result.imported);
        assertEquals(0, result.duplicates);
        // The unsafe item, the two entries no item claims, and b.jpg, which the archive lacks.
        assertEquals(4, result.failed);
        List<GalleryStore.Entry> entries = store.list(null, null, false, null, GalleryStore.SORT_OLDEST);
        assertEquals(1, entries.size());
        assertEquals("first", read(entries.get(0).file));
        assertFalse(new File(tmp.getRoot(), "c.jpg").exists());
    }

    @Test
    public void filesFromFilesKeepAKnownNameOrGetTheirTypesSuffix() throws Exception {
        assertEquals("IMG_1.JPG", GalleryArchive.importedName("IMG_1.JPG", "jpg", GalleryItem.PHOTO));
        assertEquals("clip.mov", GalleryArchive.importedName("clip.mov", "mp4", GalleryItem.VIDEO));
        assertEquals("clip.mp4", GalleryArchive.importedName("clip", "mp4", GalleryItem.VIDEO));
        assertEquals("photo.php.jpg", GalleryArchive.importedName("photo.php", "jpg", GalleryItem.PHOTO));
        assertEquals("_.._x.png", GalleryArchive.importedName("/../x.png", "png", GalleryItem.PHOTO));
        assertEquals("imported.webp", GalleryArchive.importedName(null, "webp", GalleryItem.PHOTO));
        assertNull(GalleryArchive.importedName("notes.txt", "txt", GalleryItem.PHOTO));
        assertNull(GalleryArchive.importedName("clip.jpg", null, GalleryItem.VIDEO));
    }

    @Test
    public void aPickedFileIsAddedToImportedUnlessItsBytesAreSaved() throws Exception {
        GalleryArchive.Duplicates duplicates = new GalleryArchive.Duplicates(Collections.emptyList());
        GalleryItem item = new GalleryItem(GalleryTab.IMPORTED, GalleryItem.PHOTO, null, null, null, null, null, null);

        long id = GalleryArchive.add(store, duplicates, store.clearEpoch(), stream("picked"), item,
                GalleryArchive.IMPORTED_FOLDER, "a.jpg", null);
        long again = GalleryArchive.add(store, duplicates, store.clearEpoch(), stream("picked"), item,
                GalleryArchive.IMPORTED_FOLDER, "b.jpg", null);

        assertTrue(id > 0);
        assertEquals(-1, again);
        List<GalleryStore.Entry> entries = store.list("imported", null, false, null, GalleryStore.SORT_NEWEST);
        assertEquals(1, entries.size());
        assertEquals("imported", entries.get(0).source);
        assertNull(entries.get(0).item.ownerUsername);
        assertEquals(NOW, entries.get(0).savedAt);
        assertEquals("picked", read(entries.get(0).file));
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private long save(GalleryItem item, String subFolder, String fileName, String content) throws IOException {
        File part = store.partFile();
        Files.write(part.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return store.publish(part, item, subFolder, fileName, false);
    }

    private byte[] backup() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GalleryArchive.write(store, out, (done, total) -> { });
        return out.toByteArray();
    }

    private GalleryArchive.Result restore(byte[] archive) throws IOException {
        GalleryArchive.Result result = new GalleryArchive.Result();
        GalleryArchive.read(store, new ByteArrayInputStream(archive), result, (done, total) -> { });
        return result;
    }

    private void assertInvalid(byte[] archive) throws IOException {
        try {
            restore(archive);
            fail("Imported a file that is no backup");
        } catch (GalleryArchive.InvalidBackup expected) {
        }
    }

    private static void assertLeftOut(JSONObject item) throws Exception {
        GalleryArchive.Manifest manifest = GalleryArchive.parse(manifest(item));
        assertEquals(1, manifest.invalid);
        assertEquals(Collections.emptyMap(), manifest.items);
    }

    private static JSONObject item(String file) throws Exception {
        return new JSONObject().put("file", file).put("tab", "posts").put("kind", GalleryItem.PHOTO)
                .put("fileName", file).put("savedAt", NOW);
    }

    private static String manifest(JSONObject... items) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject item : items) array.put(item);
        return new JSONObject().put("schema", GalleryArchive.SCHEMA).put("items", array).toString();
    }

    /** Entry names and contents, alternating, in order. */
    private static List<String[]> entries(String... namesAndContents) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            out.add(new String[]{namesAndContents[i], namesAndContents[i + 1]});
        }
        return out;
    }

    private static byte[] zip(List<String[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String[] entry : entries) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String hash(String content) throws IOException {
        return GalleryArchive.hash(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
