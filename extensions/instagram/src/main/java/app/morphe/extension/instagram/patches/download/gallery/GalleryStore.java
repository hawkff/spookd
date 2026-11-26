package app.morphe.extension.instagram.patches.download.gallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.PikoUtils;

/**
 * Downloads saved as plain files in {@code no_backup/piko_gallery/}, one row each in
 * {@code piko_gallery.db}. Every call does disk I/O and runs on the caller's background thread.
 * Calls that list or hand out files first reconcile files with rows once per process, so they
 * throw {@link IllegalStateException} when the directory cannot be created or listed.
 */
public final class GalleryStore {
    static final String PART = ".part";
    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,8}$");
    private static final String COLUMNS = "id, file, tab, kind, file_name, sub_folder,"
            + " owner_username, owner_id, media_id, link, account_id, saved_at, taken_at,"
            + " size_bytes, duration_ms";

    /** What {@link #publish} does when a saved download already has the same subfolder and name. */
    public enum Conflict { RENAME, SKIP, OVERWRITE }

    /** One saved download. */
    public static final class Entry {
        public final long id;
        public final File file;
        public final GalleryItem item;
        /** The formatted download name, used for export and conflicts. */
        public final String fileName;
        public final String subFolder;
        public final long savedAt;
        public final long sizeBytes;
        public final Long durationMs;

        private Entry(Cursor c, File dir) {
            id = c.getLong(0);
            file = new File(dir, c.getString(1));
            item = new GalleryItem(GalleryTab.valueOf(c.getString(2).toUpperCase(Locale.ROOT)),
                    c.getString(3), c.getString(6), c.getString(7), c.getString(8),
                    c.getString(9), c.getString(10), c.isNull(12) ? null : c.getLong(12));
            fileName = c.getString(4);
            subFolder = c.getString(5);
            savedAt = c.getLong(11);
            sizeBytes = c.getLong(13);
            durationMs = c.isNull(14) ? null : c.getLong(14);
        }
    }

    private static volatile GalleryStore instance;

    private final SQLiteDatabase db;
    private final File dir;
    private final File thumbDir;
    private final LongSupplier clock;
    private boolean recovered;

    GalleryStore(SQLiteDatabase db, File dir, File thumbDir, LongSupplier clock) {
        this.db = db;
        this.dir = dir;
        this.thumbDir = thumbDir;
        this.clock = clock;
    }

    /** The process-wide store; opens the database. Throws when the app context is not set yet. */
    public static GalleryStore get() {
        GalleryStore store = instance;
        if (store == null) {
            synchronized (GalleryStore.class) {
                if (instance == null) {
                    Context context = PikoUtils.getContext();
                    instance = new GalleryStore(GalleryDb.getInstance(context).getWritableDatabase(),
                            new File(context.getNoBackupFilesDir(), "piko_gallery"),
                            new File(context.getCacheDir(), "piko_gallery_thumbs"),
                            System::currentTimeMillis);
                }
                store = instance;
            }
        }
        return store;
    }

    /** A new, not yet created file for one download to write into. */
    public synchronized File partFile() {
        recover();
        return new File(dir, UUID.randomUUID() + PART);
    }

    /** Whether a saved download already has this export subfolder and name. */
    public synchronized boolean exists(String subFolder, String fileName) {
        recover();
        return newest(subFolder, fileName) != null;
    }

    /** Whether a saved download is of media {@code mediaId}, whatever its name. */
    public synchronized boolean hasMedia(String mediaId) {
        if (mediaId == null) return false;
        recover();
        try (Cursor c = db.rawQuery("SELECT 1 FROM gallery_items WHERE media_id = ? LIMIT 1",
                new String[]{mediaId})) {
            return c.moveToFirst();
        }
    }

    /**
     * Makes a finished part a gallery item and returns its id. With {@code overwrite} the part
     * and its metadata replace the newest item of the same subfolder and name, if any, keeping its
     * id; otherwise it is a new item. On throw the part is deleted and existing items are intact.
     */
    public long publish(File part, GalleryItem item, String subFolder, String fileName,
                        boolean overwrite) throws IOException {
        return publish(part, item, subFolder, fileName, overwrite ? Conflict.OVERWRITE : Conflict.RENAME);
    }

    /** {@link #publish} that with SKIP deletes the part and returns -1 when the name is taken, checked under the store's lock. */
    public long publish(File part, GalleryItem item, String subFolder, String fileName,
                        Conflict conflict) throws IOException {
        try {
            if (!part.isFile()) throw new IOException("Missing " + part);
            Long durationMs = GalleryItem.PHOTO.equals(item.kind) ? null : durationMs(part);
            long size = part.length();
            synchronized (this) {
                String file = baseName(part) + extension(fileName);
                File target = new File(dir, file);
                ContentValues values = new ContentValues();
                values.put("file", file);
                values.put("tab", item.tab.name().toLowerCase(Locale.ROOT));
                values.put("kind", item.kind);
                values.put("file_name", fileName);
                values.put("sub_folder", subFolder);
                values.put("owner_username", item.ownerUsername);
                values.put("owner_id", item.ownerId);
                values.put("media_id", item.mediaId);
                values.put("link", item.link);
                values.put("account_id", item.accountId);
                values.put("saved_at", clock.getAsLong());
                values.put("taken_at", item.takenAt);
                values.put("size_bytes", size);
                values.put("duration_ms", durationMs);
                Object[] existing = conflict == Conflict.RENAME ? null : newest(subFolder, fileName);
                if (existing != null && conflict == Conflict.SKIP) {
                    part.delete();
                    return -1;
                }
                if (existing != null) {
                    long id = (Long) existing[0];
                    // The old file stays until the row points at the replacement; a crash leaves an orphan.
                    if (!part.renameTo(target)) throw new IOException("Cannot publish " + file);
                    try {
                        if (db.update("gallery_items", values, "id = ?",
                                new String[]{String.valueOf(id)}) != 1) {
                            throw new IOException("Item " + id + " is gone");
                        }
                    } catch (IOException | RuntimeException e) {
                        target.delete();
                        throw e;
                    }
                    new File(dir, (String) existing[1]).delete();
                    thumb((String) existing[1]).delete();
                    return id;
                }
                // The row goes first, so a file never exists without one.
                long id = db.insertOrThrow("gallery_items", null, values);
                if (!part.renameTo(target)) {
                    db.delete("gallery_items", "id = ?", new String[]{String.valueOf(id)});
                    throw new IOException("Cannot publish " + file);
                }
                return id;
            }
        } catch (IOException | RuntimeException e) {
            part.delete();
            throw e;
        }
    }

    /** Saved downloads of one tab, newest first. */
    public synchronized List<Entry> list(GalleryTab tab) {
        recover();
        List<Entry> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT " + COLUMNS + " FROM gallery_items WHERE tab = ?"
                        + " ORDER BY saved_at DESC, id DESC",
                new String[]{tab.name().toLowerCase(Locale.ROOT)})) {
            while (c.moveToNext()) out.add(new Entry(c, dir));
        }
        return out;
    }

    /** Deletes the row, then its file and thumbnail; a file left behind goes at the next recovery. */
    public synchronized boolean delete(long id) {
        String[] args = {String.valueOf(id)};
        String file;
        try (Cursor c = db.rawQuery("SELECT file FROM gallery_items WHERE id = ?", args)) {
            if (!c.moveToFirst()) return false;
            file = c.getString(0);
        }
        db.delete("gallery_items", "id = ?", args);
        new File(dir, file).delete();
        thumb(file).delete();
        return true;
    }

    /**
     * Deletes every item: the rows, then their files and thumbnails. False when an item file that
     * still exists could not be deleted; recovery removes it at the next start. Throws on database errors.
     */
    public synchronized boolean deleteAll() {
        recover();
        List<String> files = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT file FROM gallery_items", null)) {
            while (c.moveToNext()) files.add(c.getString(0));
        }
        db.delete("gallery_items", null, null);
        boolean deleted = true;
        for (String file : files) {
            File item = new File(dir, file);
            if (!item.delete() && item.exists()) deleted = false;
            thumb(file).delete();
        }
        return deleted;
    }

    /** {@link #delete(long)} only while {@code seen} is still the item's file, so an item overwritten meanwhile stays. */
    public synchronized boolean delete(long id, File seen) {
        try (Cursor c = db.rawQuery("SELECT file FROM gallery_items WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst() || !seen.getName().equals(c.getString(0))) return false;
        }
        return delete(id);
    }

    /**
     * Writes item {@code id}'s thumbnail through {@code write} only while {@code file} is still its
     * file, under the lock a delete or overwrite takes, so neither leaves a stale thumbnail. Returns
     * false, writing nothing, when the item is gone or was replaced.
     */
    public synchronized boolean writeThumb(long id, File file, Predicate<OutputStream> write) throws IOException {
        try (Cursor c = db.rawQuery("SELECT file FROM gallery_items WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst() || !file.getName().equals(c.getString(0)) || !file.isFile()) return false;
        }
        if (!thumbDir.isDirectory() && !thumbDir.mkdirs()) return false;
        File thumb = thumb(file.getName());
        boolean written = false;
        try (OutputStream out = new FileOutputStream(thumb)) {
            written = write.test(out);
        } finally {
            if (!written) thumb.delete();
        }
        return written;
    }

    /**
     * Opt-in cleanup of downloads only: deletes the oldest until none is older than
     * {@code maxAgeDays} and all fit in {@code maxBytes}; 0 turns either limit off. Returns how
     * many were deleted.
     */
    public synchronized int prune(int maxAgeDays, long maxBytes, long now) {
        if (maxAgeDays <= 0 && maxBytes <= 0) return 0;
        recover();
        List<long[]> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, saved_at, size_bytes FROM gallery_items"
                + " ORDER BY saved_at, id", null)) {
            while (c.moveToNext()) rows.add(new long[]{c.getLong(0), c.getLong(1), c.getLong(2)});
        }
        List<Long> over = overLimits(rows, maxAgeDays, maxBytes, now);
        for (long id : over) delete(id);
        return over.size();
    }

    /** Ids to delete from {id, saved_at, size_bytes} rows ordered oldest first. */
    static List<Long> overLimits(List<long[]> oldestFirst, int maxAgeDays, long maxBytes, long now) {
        long total = 0;
        for (long[] row : oldestFirst) total += row[2];
        long before = maxAgeDays > 0 ? now - maxAgeDays * DAY_MS : Long.MIN_VALUE;
        List<Long> out = new ArrayList<>();
        for (long[] row : oldestFirst) {
            if (row[1] >= before && (maxBytes <= 0 || total <= maxBytes)) break;
            out.add(row[0]);
            total -= row[2];
        }
        return out;
    }

    /** Once per process, before any part is handed out: drops parts, files without rows and rows without files. */
    private void recover() {
        if (recovered) return;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
        File[] files = dir.listFiles();
        if (files == null) throw new IllegalStateException("Cannot list " + dir);
        Map<String, Long> rows = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT file, id FROM gallery_items", null)) {
            while (c.moveToNext()) rows.put(c.getString(0), c.getLong(1));
        }
        Set<String> thumbs = new HashSet<>();
        for (String file : rows.keySet()) thumbs.add(thumb(file).getName());
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(PART) || rows.remove(name) == null) file.delete();
        }
        for (Map.Entry<String, Long> row : rows.entrySet()) {
            db.delete("gallery_items", "id = ?", new String[]{String.valueOf(row.getValue())});
            thumbs.remove(thumb(row.getKey()).getName());
        }
        File[] cached = thumbDir.listFiles();
        if (cached != null) {
            for (File thumb : cached) {
                if (!thumbs.contains(thumb.getName())) thumb.delete();
            }
        }
        recovered = true;
    }

    /** {id, file} of the newest row with this subfolder and name, or null. */
    private Object[] newest(String subFolder, String fileName) {
        if (fileName == null) return null;
        String where = subFolder == null ? "sub_folder IS NULL" : "sub_folder = ?";
        String[] args = subFolder == null ? new String[]{fileName} : new String[]{subFolder, fileName};
        try (Cursor c = db.rawQuery("SELECT id, file FROM gallery_items WHERE " + where
                + " AND file_name = ? ORDER BY id DESC LIMIT 1", args)) {
            return c.moveToFirst() ? new Object[]{c.getLong(0), c.getString(1)} : null;
        }
    }

    /** The cached thumbnail of an item file; keyed by the file, so an overwrite never shows the old one. */
    File thumb(String file) {
        return new File(thumbDir, file + ".jpg");
    }

    private static String baseName(File part) {
        String name = part.getName();
        return name.endsWith(PART) ? name.substring(0, name.length() - PART.length()) : name;
    }

    /** The name's extension, lowercase, or empty when it has none that is safe as a file suffix. */
    private static String extension(String fileName) {
        Matcher m = EXTENSION.matcher(fileName);
        String ext = m.find() ? m.group().toLowerCase(Locale.ROOT) : "";
        return PART.equals(ext) ? "" : ext;
    }

    private static Long durationMs(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? null : Long.parseLong(value);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }
}
