package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class GalleryStoreTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final GalleryItem POST = new GalleryItem(GalleryTab.POSTS, GalleryItem.PHOTO,
            "alice", "11", "22", "https://www.instagram.com/p/abc/", "33", 5000L);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final long[] clock = {NOW};
    private GalleryDb helper;
    private SQLiteDatabase db;
    private File dir;
    private File thumbs;
    private GalleryStore store;

    @Before
    public void setUp() throws Exception {
        // A fresh helper per test; the singleton would keep an earlier test's context.
        helper = new GalleryDb(RuntimeEnvironment.getApplication());
        db = helper.getWritableDatabase();
        dir = tmp.newFolder("piko_gallery");
        thumbs = tmp.newFolder("piko_gallery_thumbs");
        restart();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    /** A new process: the next store recovers the same directory and database again. */
    private void restart() {
        store = new GalleryStore(db, dir, thumbs, () -> clock[0]);
    }

    @Test
    public void publishInsertsRowAndMovesPartToItsFile() throws Exception {
        File part = part("abc");
        assertEquals(dir, part.getParentFile());
        assertTrue(part.getName().endsWith(GalleryStore.PART));

        long id = store.publish(part, POST, "alice", "alice_1.JPG", false);

        assertFalse(part.exists());
        List<GalleryStore.Entry> entries = store.list(GalleryTab.POSTS);
        assertEquals(1, entries.size());
        GalleryStore.Entry entry = entries.get(0);
        assertEquals(id, entry.id);
        String base = part.getName().substring(0, part.getName().length() - 5);
        assertEquals(new File(dir, base + ".jpg"), entry.file);
        assertEquals("abc", read(entry.file));
        assertEquals("alice_1.JPG", entry.fileName);
        assertEquals("alice", entry.subFolder);
        assertEquals(NOW, entry.savedAt);
        assertEquals(3, entry.sizeBytes);
        assertNull(entry.durationMs);
        assertEquals(GalleryTab.POSTS, entry.item.tab);
        assertEquals(GalleryItem.PHOTO, entry.item.kind);
        assertEquals("alice", entry.item.ownerUsername);
        assertEquals("11", entry.item.ownerId);
        assertEquals("22", entry.item.mediaId);
        assertEquals("https://www.instagram.com/p/abc/", entry.item.link);
        assertEquals("33", entry.item.accountId);
        assertEquals(Long.valueOf(5000), entry.item.takenAt);
        assertTrue(store.list(GalleryTab.REELS).isEmpty());
    }

    @Test
    public void failedMoveDeletesRowAndPart() throws Exception {
        File part = part("abc");
        String base = part.getName().substring(0, part.getName().length() - 5);
        File blocker = new File(dir, base + ".jpg");
        assertTrue(blocker.mkdir());
        assertTrue(new File(blocker, "child").createNewFile());

        try {
            store.publish(part, POST, "alice", "a.jpg", false);
            fail();
        } catch (IOException expected) {
        }

        assertFalse(part.exists());
        assertEquals(0, rows());
    }

    @Test
    public void unsafeExtensionIsDropped() throws Exception {
        File part = part("abc");
        store.publish(part, POST, "alice", "a.b/../c", false);
        String base = part.getName().substring(0, part.getName().length() - 5);
        assertEquals(new File(dir, base), store.list(GalleryTab.POSTS).get(0).file);
    }

    @Test
    public void recoveryDeletesLeftoverParts() throws Exception {
        File kept = file(publish("alice", "a.jpg", false));
        File leftover = new File(dir, "crashed.part");
        write(leftover, "half");
        restart();

        assertEquals(1, store.list(GalleryTab.POSTS).size());
        assertFalse(leftover.exists());
        assertTrue(kept.exists());
    }

    @Test
    public void recoveryDeletesFilesWithoutRows() throws Exception {
        File kept = file(publish("alice", "a.jpg", false));
        File orphan = new File(dir, "orphan.jpg");
        write(orphan, "x");
        restart();

        store.partFile();
        assertFalse(orphan.exists());
        assertTrue(kept.exists());
    }

    @Test
    public void recoveryDeletesRowsWithoutFiles() throws Exception {
        long kept = publish("alice", "a.jpg", false);
        // A crash between the insert and the move leaves a row without its file.
        ContentValues values = new ContentValues();
        values.put("file", "lost.jpg");
        values.put("tab", "posts");
        values.put("kind", "photo");
        values.put("file_name", "b.jpg");
        values.put("sub_folder", "alice");
        values.put("saved_at", NOW);
        values.put("size_bytes", 1);
        long lost = db.insertOrThrow("gallery_items", null, values);
        File thumb = new File(thumbs, lost + ".jpg");
        write(thumb, "t");
        restart();

        assertFalse(store.exists("alice", "b.jpg"));
        assertEquals(Collections.singletonList(kept), ids(store.list(GalleryTab.POSTS)));
        assertFalse(thumb.exists());
    }

    @Test
    public void recoveryRunsOncePerProcess() throws Exception {
        File part = store.partFile();
        write(part, "in progress");
        store.list(GalleryTab.POSTS);
        assertTrue(part.exists());
    }

    @Test
    public void renamePolicyAddsANewItemEachTime() throws Exception {
        long first = publish("alice", "a.jpg", false);
        long second = publish("alice", "a.jpg", false);

        assertEquals(Arrays.asList(second, first), ids(store.list(GalleryTab.POSTS)));
        assertTrue(file(first).exists());
        assertTrue(file(second).exists());
    }

    @Test
    public void skipPolicySeesItemsBySubfolderAndName() throws Exception {
        assertFalse(store.exists("alice", "a.jpg"));
        publish("alice", "a.jpg", false);
        publish(null, "b.jpg", false);

        assertTrue(store.exists("alice", "a.jpg"));
        assertFalse(store.exists("bob", "a.jpg"));
        assertFalse(store.exists("alice", "b.jpg"));
        assertTrue(store.exists(null, "b.jpg"));
        assertFalse(store.exists(null, "a.jpg"));
    }

    @Test
    public void overwritePolicyReplacesFileRowAndThumbnail() throws Exception {
        long id = publish("alice", "a.jpg", false);
        File original = file(id);
        File thumb = new File(thumbs, id + ".jpg");
        write(thumb, "t");
        clock[0] = NOW + 1000;

        File part = part("newer");
        assertEquals(id, store.publish(part, POST, "alice", "a.jpg", true));

        List<GalleryStore.Entry> entries = store.list(GalleryTab.POSTS);
        assertEquals(1, entries.size());
        assertEquals(original, entries.get(0).file);
        assertEquals("newer", read(original));
        assertEquals(5, entries.get(0).sizeBytes);
        assertEquals(NOW + 1000, entries.get(0).savedAt);
        assertFalse(part.exists());
        assertFalse(thumb.exists());
    }

    @Test
    public void overwriteWithoutAMatchAddsANewItem() throws Exception {
        long first = publish("alice", "a.jpg", false);
        long second = publish("alice", "b.jpg", true);

        assertEquals(Arrays.asList(second, first), ids(store.list(GalleryTab.POSTS)));
    }

    @Test
    public void deleteRemovesRowFileAndThumbnail() throws Exception {
        long id = publish("alice", "a.jpg", false);
        File file = file(id);
        File thumb = new File(thumbs, id + ".jpg");
        write(thumb, "t");

        assertTrue(store.delete(id));
        assertFalse(store.delete(id));
        assertEquals(0, rows());
        assertFalse(file.exists());
        assertFalse(thumb.exists());
    }

    @Test
    public void overLimitsByAge() {
        List<long[]> rows = Arrays.asList(row(1, NOW - 10 * DAY, 1), row(2, NOW - 8 * DAY, 1),
                row(3, NOW - 7 * DAY, 1), row(4, NOW - DAY, 1));
        assertEquals(Arrays.asList(1L, 2L), GalleryStore.overLimits(rows, 7, 0, NOW));
    }

    @Test
    public void overLimitsBySize() {
        List<long[]> rows = Arrays.asList(row(1, NOW - 3, 40), row(2, NOW - 2, 30),
                row(3, NOW - 1, 20));
        assertEquals(Collections.singletonList(1L), GalleryStore.overLimits(rows, 0, 50, NOW));
        assertEquals(Collections.emptyList(), GalleryStore.overLimits(rows, 0, 90, NOW));
        assertEquals(Arrays.asList(1L, 2L, 3L), GalleryStore.overLimits(rows, 0, 10, NOW));
    }

    @Test
    public void overLimitsByAgeAndSize() {
        List<long[]> rows = Arrays.asList(row(1, NOW - 9 * DAY, 50), row(2, NOW - 8 * DAY, 1),
                row(3, NOW - DAY, 30), row(4, NOW, 20));
        // Size alone would drop only 1; age also drops 2.
        assertEquals(Arrays.asList(1L, 2L), GalleryStore.overLimits(rows, 7, 60, NOW));
        // Age alone would drop 1 and 2; size also drops 3.
        assertEquals(Arrays.asList(1L, 2L, 3L), GalleryStore.overLimits(rows, 7, 40, NOW));
    }

    @Test
    public void overLimitsOff() {
        List<long[]> rows = Arrays.asList(row(1, 0, Long.MAX_VALUE / 2), row(2, NOW, 1));
        assertEquals(Collections.emptyList(), GalleryStore.overLimits(rows, 0, 0, NOW));
    }

    @Test
    public void pruneDeletesOldestDownloadsAndTheirFiles() throws Exception {
        clock[0] = NOW - 10 * DAY;
        long old = publish("alice", "old.jpg", false);
        File oldFile = file(old);
        clock[0] = NOW - DAY;
        long recent = publish("alice", "recent.jpg", false);

        assertEquals(0, store.prune(0, 0, NOW));
        assertEquals(1, store.prune(7, 0, NOW));

        assertEquals(Collections.singletonList(recent), ids(store.list(GalleryTab.POSTS)));
        assertFalse(oldFile.exists());
        assertEquals(1, store.prune(0, 1, NOW));
        assertEquals(0, rows());
    }

    private File part(String content) throws IOException {
        File part = store.partFile();
        write(part, content);
        return part;
    }

    private long publish(String subFolder, String fileName, boolean overwrite) throws IOException {
        return store.publish(part("content"), POST, subFolder, fileName, overwrite);
    }

    private File file(long id) {
        for (GalleryStore.Entry entry : store.list(GalleryTab.POSTS)) {
            if (entry.id == id) return entry.file;
        }
        throw new AssertionError("No item " + id);
    }

    private long rows() {
        return DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM gallery_items", null);
    }

    private static long[] row(long id, long savedAt, long size) {
        return new long[]{id, savedAt, size};
    }

    private static List<Long> ids(List<GalleryStore.Entry> entries) {
        List<Long> out = new ArrayList<>();
        for (GalleryStore.Entry entry : entries) out.add(entry.id);
        return out;
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
