package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import app.morphe.extension.crimera.constants.ExtensionStrings;
import app.morphe.extension.crimera.legacydownloader.DownloadRequest;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class DownloadHistoryTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final GalleryItem POST = new GalleryItem(GalleryTab.POSTS, GalleryItem.PHOTO,
            "alice", "11", "22", "https://www.instagram.com/p/abc/", "33", 5000L);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final long[] clock = {NOW};
    private GalleryDb helper;
    private SQLiteDatabase db;
    private File dir;
    private GalleryStore gallery;
    private DownloadHistory history;

    @Before
    public void setUp() throws Exception {
        helper = new GalleryDb(RuntimeEnvironment.getApplication());
        db = helper.getWritableDatabase();
        dir = tmp.newFolder("piko_gallery");
        gallery = new GalleryStore(db, dir, tmp.newFolder("piko_gallery_thumbs"), () -> clock[0]);
        history = new DownloadHistory(db, dir, () -> clock[0]);
        DownloadHistory.instance = history;
    }

    @After
    public void tearDown() {
        DownloadHistory.instance = null;
        history.clear();
        helper.close();
    }

    @Test
    public void trackedRequestOffersRetryOnlyAfterAnErrorAndReusesItsRow() {
        DownloadRequest request = new DownloadRequest("https://cdn.example/a.jpg", null, "a.jpg");
        DownloadHistory.Row row = DownloadHistory.track(request, POST);
        assertSame(row, request.tracker);

        row.started();
        row.ended(false, null, new IOException("broken"));
        DownloadHistory.Entry entry = only();
        assertEquals(DownloadHistory.FAILED, entry.status);
        assertEquals("IOException: broken", entry.error);
        assertEquals("alice", entry.ownerUsername);
        assertEquals(GalleryItem.PHOTO, entry.kind);
        assertTrue(DownloadHistory.canRetry(entry.id, NOW));

        row.started();
        assertEquals(DownloadHistory.DOWNLOADING, only().status);
        assertFalse(DownloadHistory.canRetry(entry.id, NOW));

        row.ended(false, null, null);
        assertEquals(ExtensionStrings.DOWNLOAD_MEDIA_EXISTS, only().error);
        assertFalse(DownloadHistory.canRetry(entry.id, NOW));
    }

    @Test
    public void trackedRequestRecordsWhereItWasSaved() {
        DownloadRequest request = new DownloadRequest("https://cdn.example/a.jpg", null, "a.jpg");
        DownloadHistory.Row row = DownloadHistory.track(request, POST);
        row.started();
        request.fileName = "a (1).jpg";
        row.ended(true, Uri.parse("content://tree/document/a"), null);

        DownloadHistory.Entry entry = only();
        assertEquals(DownloadHistory.SAVED, entry.status);
        assertEquals("a (1).jpg", entry.fileName);
        assertEquals("content://tree/document/a", entry.document);
        assertFalse(entry.inGallery);
    }

    @Test
    public void trackedRequestSavedToTheGalleryPointsAtItsItem() throws Exception {
        long galleryId = gallery.publish(part(), POST, "alice", "a.jpg", false);
        DownloadHistory.Row row = DownloadHistory.track(new DownloadRequest("https://cdn.example/a.jpg", null, "a.jpg"), POST);
        row.started();
        row.savedToGallery(galleryId);
        row.ended(true, null, null);

        DownloadHistory.Entry entry = only();
        assertTrue(entry.inGallery);
        assertEquals(gallery.list(GalleryTab.POSTS).get(0).file, entry.galleryFile);
    }

    @Test
    public void anExpiredLinkIsNotRetried() {
        String expired = "https://scontent.cdninstagram.com/a.jpg?oe=" + Long.toHexString(1_000_000_000L);
        DownloadHistory.Row row = DownloadHistory.track(new DownloadRequest(expired, null, "a.jpg"), POST);
        row.started();
        row.ended(false, null, new IOException("gone"));

        assertFalse(DownloadHistory.canRetry(only().id, NOW));
    }

    @Test
    public void aLinkExpiringAfterTheRowWasShownIsNotEnqueued() {
        long expirySeconds = NOW / 1000 + 60;
        String url = "https://scontent.cdninstagram.com/a.jpg?oe=" + Long.toHexString(expirySeconds);
        DownloadHistory.Row row = DownloadHistory.track(new DownloadRequest(url, null, "a.jpg"), POST);
        row.started();
        row.ended(false, null, new IOException("broken"));
        long id = only().id;
        assertTrue(DownloadHistory.canRetry(id, NOW));
        assertEquals(expirySeconds * 1000, DownloadHistory.retryExpiresAt(id));

        // A null context would throw if the request reached the download writer.
        assertFalse(DownloadHistory.retry(null, id, expirySeconds * 1000 + 1));
        assertFalse(DownloadHistory.canRetry(id, NOW));
        assertEquals(0, DownloadHistory.retryExpiresAt(id));
        assertEquals(DownloadHistory.FAILED, only().status);
    }

    @Test
    public void startAddsADownloadingRowWithOwnerKindAndTime() {
        long id = history.start(GalleryItem.VIDEO, "alice", "alice_1.mp4");

        DownloadHistory.Entry entry = only();
        assertEquals(id, entry.id);
        assertEquals(DownloadHistory.DOWNLOADING, entry.status);
        assertEquals(GalleryItem.VIDEO, entry.kind);
        assertEquals("alice", entry.ownerUsername);
        assertEquals("alice_1.mp4", entry.fileName);
        assertEquals(NOW, entry.startedAt);
        assertFalse(entry.inGallery);
        assertNull(entry.galleryFile);
        assertNull(entry.document);
        assertNull(entry.error);
    }

    @Test
    public void savedToTheGalleryOpensItsCurrentFileUntilTheItemIsDeleted() throws Exception {
        long galleryId = gallery.publish(part(), POST, "alice", "a.jpg", false);
        long id = history.start(GalleryItem.PHOTO, "alice", "a.jpg");

        history.saved(id, "a.jpg", galleryId, null);

        DownloadHistory.Entry entry = only();
        assertEquals(DownloadHistory.SAVED, entry.status);
        assertTrue(entry.inGallery);
        assertEquals(gallery.list(GalleryTab.POSTS).get(0).file, entry.galleryFile);
        assertNull(entry.document);

        gallery.delete(galleryId);
        entry = only();
        assertTrue(entry.inGallery);
        assertNull(entry.galleryFile);
    }

    @Test
    public void savedToTheFolderKeepsTheDocumentAndTheNameItGot() {
        long id = history.start(GalleryItem.PHOTO, "alice", "a.jpg");

        history.saved(id, "a (1).jpg", null, "content://tree/document/a");

        DownloadHistory.Entry entry = only();
        assertEquals(DownloadHistory.SAVED, entry.status);
        assertEquals("a (1).jpg", entry.fileName);
        assertEquals("content://tree/document/a", entry.document);
        assertFalse(entry.inGallery);
        assertNull(entry.galleryFile);
    }

    @Test
    public void failedKeepsItsReasonAndRestartClearsIt() {
        long id = history.start(GalleryItem.PHOTO, "alice", "a.jpg");

        history.failed(id, "IOException: broken");
        assertEquals(DownloadHistory.FAILED, only().status);
        assertEquals("IOException: broken", only().error);

        history.restart(id);
        assertEquals(DownloadHistory.DOWNLOADING, only().status);
        assertNull(only().error);
    }

    @Test
    public void startKeepsOnlyTheNewestRowsNewestFirst() {
        long first = 0;
        long last = 0;
        for (int i = 0; i < DownloadHistory.CAP + 5; i++) {
            last = history.start(GalleryItem.PHOTO, "alice", i + ".jpg");
            if (i == 0) first = last;
        }

        List<DownloadHistory.Entry> entries = history.list();
        assertEquals(DownloadHistory.CAP, entries.size());
        assertEquals(last, entries.get(0).id);
        assertEquals(first + 5, entries.get(entries.size() - 1).id);
    }

    @Test
    public void anotherProcessFindsDownloadingRowsFailedWithoutAReason() {
        long running = history.start(GalleryItem.PHOTO, "alice", "a.jpg");
        long saved = history.start(GalleryItem.PHOTO, "alice", "b.jpg");
        history.saved(saved, "b.jpg", null, "content://b");

        history = new DownloadHistory(db, dir, () -> clock[0]);

        for (DownloadHistory.Entry entry : history.list()) {
            if (entry.id == running) {
                assertEquals(DownloadHistory.FAILED, entry.status);
                assertNull(entry.error);
            } else {
                assertEquals(DownloadHistory.SAVED, entry.status);
            }
        }
    }

    @Test
    public void clearDeletesEveryRowButNoGalleryItem() throws Exception {
        long galleryId = gallery.publish(part(), POST, "alice", "a.jpg", false);
        history.saved(history.start(GalleryItem.PHOTO, "alice", "a.jpg"), "a.jpg", galleryId, null);
        history.start(GalleryItem.PHOTO, "bob", "b.jpg");

        history.clear();

        assertTrue(history.list().isEmpty());
        assertEquals(1, gallery.list(GalleryTab.POSTS).size());
    }

    @Test
    public void reasonLeavesOutMessagesWithAUrlAndStaysShort() {
        assertEquals("FileNotFoundException",
                DownloadHistory.reason(new FileNotFoundException("https://scontent.cdninstagram.com/v/a.jpg?oe=1")));
        assertEquals("IOException: Download folder access is missing",
                DownloadHistory.reason(new IOException("Download folder access is missing")));
        assertEquals("IllegalStateException", DownloadHistory.reason(new IllegalStateException()));
        assertEquals(160, DownloadHistory.reason(new IOException(new String(new char[400]).replace('\0', 'x'))).length());
    }

    @Test
    public void upgradingAVersionOneGalleryAddsTheHistoryAndKeepsItems() {
        helper.close();
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase(GalleryDb.DB_NAME);
        File path = app.getDatabasePath(GalleryDb.DB_NAME);
        path.getParentFile().mkdirs();
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(path, null);
        old.execSQL("CREATE TABLE gallery_items (id INTEGER PRIMARY KEY AUTOINCREMENT, file TEXT NOT NULL)");
        old.execSQL("INSERT INTO gallery_items (file) VALUES ('a.jpg')");
        old.setVersion(1);
        old.close();

        helper = new GalleryDb(app);
        db = helper.getWritableDatabase();

        assertEquals(1, DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM gallery_items", null));
        history = new DownloadHistory(db, dir, () -> clock[0]);
        history.start(GalleryItem.PHOTO, "alice", "a.jpg");
        assertEquals(1, DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM download_history", null));
    }

    private DownloadHistory.Entry only() {
        List<DownloadHistory.Entry> entries = history.list();
        assertEquals(1, entries.size());
        return entries.get(0);
    }

    private File part() throws IOException {
        File part = gallery.partFile();
        Files.write(part.toPath(), "content".getBytes(StandardCharsets.UTF_8));
        return part;
    }
}
