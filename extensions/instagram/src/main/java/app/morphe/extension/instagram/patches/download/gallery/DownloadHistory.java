package app.morphe.extension.instagram.patches.download.gallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.constants.ExtensionStrings;
import app.morphe.extension.crimera.legacydownloader.DownloadRequest;
import app.morphe.extension.crimera.legacydownloader.MediaDownloader;
import app.morphe.extension.crimera.legacydownloader.StorageUtils;
import app.morphe.extension.instagram.utils.MediaUrls;
import app.morphe.extension.shared.Logger;

/**
 * The newest {@link #CAP} downloads with their status, one row each in {@code piko_gallery.db}, for
 * every account as the gallery shows them. Store calls do disk I/O on the caller's background thread.
 */
public final class DownloadHistory {
    static final int CAP = 500;
    static final String DOWNLOADING = "downloading";
    static final String SAVED = "saved";
    static final String FAILED = "failed";
    private static final String TABLE = "download_history";

    // Failed requests of this process by row, so a retry runs the same request through the download writer again.
    private static final Map<Long, DownloadRequest> retryable = Collections.synchronizedMap(
            new LinkedHashMap<Long, DownloadRequest>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, DownloadRequest> eldest) {
                    return size() > CAP;
                }
            });

    // Package-private so tests can stand in a store.
    static volatile DownloadHistory instance;
    // The open history screen, told on the writing thread after every change; null while none is in the foreground.
    private static volatile Runnable listener;

    private final SQLiteDatabase db;
    private final File galleryDir;
    private final LongSupplier clock;

    /** One download, as the history screen shows it. */
    static final class Entry {
        final long id;
        /** {@link GalleryItem#PHOTO}, {@link GalleryItem#VIDEO} or {@link GalleryItem#AUDIO}. */
        final String kind;
        final String ownerUsername;
        final String fileName;
        final long startedAt;
        final String status;
        /** Whether it was saved into the private gallery, even when the item was deleted since. */
        final boolean inGallery;
        /** The gallery item's file; null when it was not saved there or is gone. */
        final File galleryFile;
        /** The saved download folder file's URI; null when it was not saved there. */
        final String document;
        /** Why it failed; null while it downloads, once saved, or when the app closed mid-download. */
        final String error;

        private Entry(Cursor c, File galleryDir) {
            id = c.getLong(0);
            kind = c.getString(1);
            ownerUsername = c.getString(2);
            fileName = c.getString(3);
            startedAt = c.getLong(4);
            status = c.getString(5);
            inGallery = !c.isNull(6);
            galleryFile = c.isNull(7) ? null : new File(galleryDir, c.getString(7));
            document = c.getString(8);
            error = c.getString(9);
        }
    }

    DownloadHistory(SQLiteDatabase db, File galleryDir, LongSupplier clock) {
        this.db = db;
        this.galleryDir = galleryDir;
        this.clock = clock;
        // The first open in a process: rows still downloading belong to a process that ended mid-download.
        ContentValues values = new ContentValues();
        values.put("status", FAILED);
        db.update(TABLE, values, "status = ?", new String[]{DOWNLOADING});
    }

    static void createTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "kind TEXT,"
                + "owner_username TEXT,"
                + "file_name TEXT,"
                + "started_at INTEGER NOT NULL,"
                + "status TEXT NOT NULL,"
                + "gallery_id INTEGER,"
                + "document TEXT,"
                + "error TEXT"
                + ")");
    }

    /** The process-wide store; opens the database. Throws when the app context is not set yet. */
    static DownloadHistory get() {
        DownloadHistory store = instance;
        if (store == null) {
            synchronized (DownloadHistory.class) {
                if (instance == null) {
                    Context context = PikoUtils.getContext();
                    // The directory GalleryStore keeps its item files in.
                    instance = new DownloadHistory(GalleryDb.getInstance(context).getWritableDatabase(),
                            new File(context.getNoBackupFilesDir(), "piko_gallery"), System::currentTimeMillis);
                }
                store = instance;
            }
        }
        return store;
    }

    /** Adds a downloading row and drops rows beyond the newest {@link #CAP}; returns its id. */
    synchronized long start(String kind, String ownerUsername, String fileName) {
        ContentValues values = new ContentValues();
        values.put("kind", kind);
        values.put("owner_username", ownerUsername);
        values.put("file_name", fileName);
        values.put("started_at", clock.getAsLong());
        values.put("status", DOWNLOADING);
        long id = db.insertOrThrow(TABLE, null, values);
        db.delete(TABLE, "id <= ?", new String[]{String.valueOf(id - CAP)});
        changed();
        return id;
    }

    /** Marks a retried row downloading again. */
    synchronized void restart(long id) {
        ContentValues values = new ContentValues();
        values.put("status", DOWNLOADING);
        values.putNull("error");
        update(id, values);
    }

    /** {@code galleryId} or {@code document} is where it was saved; {@code fileName} is the name it got. */
    synchronized void saved(long id, String fileName, Long galleryId, String document) {
        ContentValues values = new ContentValues();
        values.put("status", SAVED);
        values.put("file_name", fileName);
        values.put("gallery_id", galleryId);
        values.put("document", document);
        values.putNull("error");
        update(id, values);
    }

    synchronized void failed(long id, String error) {
        ContentValues values = new ContentValues();
        values.put("status", FAILED);
        values.put("error", error);
        update(id, values);
    }

    /** Newest first. */
    synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT h.id, h.kind, h.owner_username, h.file_name, h.started_at,"
                + " h.status, h.gallery_id, g.file, h.document, h.error FROM " + TABLE + " h"
                + " LEFT JOIN gallery_items g ON g.id = h.gallery_id ORDER BY h.id DESC", null)) {
            while (c.moveToNext()) out.add(new Entry(c, galleryDir));
        }
        return out;
    }

    /** Deletes every row; saved files stay where they are. */
    synchronized void clear() {
        db.delete(TABLE, null, null);
        retryable.clear();
        changed();
    }

    private void update(long id, ContentValues values) {
        db.update(TABLE, values, "id = ?", new String[]{String.valueOf(id)});
        changed();
    }

    /** Sets the one listener told after every change, or removes it with null. */
    static void setListener(Runnable changed) {
        listener = changed;
    }

    /** Removes {@code changed} only if it is still the listener, so a newer screen keeps its own. */
    static void removeListener(Runnable changed) {
        if (listener == changed) listener = null;
    }

    private static void changed() {
        Runnable current = listener;
        if (current != null) current.run();
    }

    /** A short reason to show; a message holding a URL, as a failed HTTP fetch has, is left out. */
    static String reason(Throwable error) {
        String reason = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message != null && !message.trim().isEmpty() && !message.contains("://")) {
            reason += ": " + message.trim();
        }
        return reason.length() > 160 ? reason.substring(0, 160) : reason;
    }

    /** Records {@code request} from its start through its end; {@code item} supplies owner and kind. */
    public static Row track(DownloadRequest request, GalleryItem item) {
        Row row = new Row(request, item);
        request.tracker = row;
        return row;
    }

    /** Whether this process still holds the failed request of row {@code id} and its link is still valid at {@code now}. */
    static boolean canRetry(long id, long now) {
        DownloadRequest request = retryable.get(id);
        return request != null && alive(request, now);
    }

    /** When row {@code id}'s retry link expires, in epoch ms; 0 when it has none or cannot be retried. */
    static long retryExpiresAt(long id) {
        DownloadRequest request = retryable.get(id);
        return request == null ? 0 : MediaUrls.expiresAt(request.url);
    }

    /**
     * Main thread: runs the failed request of row {@code id} through the download writer again;
     * false, dropping it, when its link expired by {@code now}. Nothing runs when it is already running or gone.
     */
    static boolean retry(Context context, long id, long now) {
        DownloadRequest request = retryable.remove(id);
        if (request == null) return true;
        if (!alive(request, now)) return false;
        // Without the folder grant the writer opens the folder picker instead, so the row stays retryable.
        if (request.privateTarget == null && !StorageUtils.checkStoragePermissions()) retryable.put(id, request);
        new MediaDownloader(context).enqueue(request);
        return true;
    }

    private static boolean alive(DownloadRequest request, long now) {
        long expiresAt = MediaUrls.expiresAt(request.url);
        return expiresAt == 0 || expiresAt > now;
    }

    /** One request's row: one insert when it starts, one update when it ends. Runs on the download thread, never throws. */
    public static final class Row implements DownloadRequest.Tracker {
        private final DownloadRequest request;
        private final GalleryItem item;
        private volatile long id = -1;
        private volatile long galleryId = -1;

        private Row(DownloadRequest request, GalleryItem item) {
            this.request = request;
            this.item = item;
        }

        @Override
        public void started() {
            try {
                galleryId = -1;
                if (id < 0) {
                    id = get().start(item.kind, item.ownerUsername, request.fileName);
                } else {
                    retryable.remove(id);
                    get().restart(id);
                }
            } catch (Exception e) {
                Logger.printException(() -> "Download history: cannot record a start", e);
            }
        }

        /** The private gallery saved the download as item {@code galleryId}. */
        public void savedToGallery(long galleryId) {
            this.galleryId = galleryId;
        }

        @Override
        public void ended(boolean saved, Uri document, Throwable error) {
            try {
                // Ended before it started: it failed or was skipped while claiming its file.
                if (id < 0) id = get().start(item.kind, item.ownerUsername, request.fileName);
                if (saved) {
                    get().saved(id, request.fileName, galleryId < 0 ? null : galleryId,
                            document == null ? null : document.toString());
                } else {
                    // Retry is published before the change reaches an open screen.
                    if (error != null) retryable.put(id, request);
                    // No error means the conflict policy skipped it: retrying would skip it again.
                    get().failed(id, error == null ? ExtensionStrings.DOWNLOAD_MEDIA_EXISTS : reason(error));
                }
            } catch (Exception e) {
                Logger.printException(() -> "Download history: cannot record an end", e);
            }
        }

        /** The request that owns the file keeps the row; a rejected retry stays retryable. */
        @Override
        public void alreadyDownloading() {
            if (id < 0) return;
            retryable.put(id, request);
            changed();
        }
    }
}
