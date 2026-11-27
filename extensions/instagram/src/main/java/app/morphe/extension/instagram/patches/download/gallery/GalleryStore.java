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
    static final String SORT_NEWEST = "newest";
    static final String SORT_OLDEST = "oldest";
    static final String SORT_SIZE = "size";
    static final String SORT_USERNAME = "username";
    /** Owners first, case-insensitively, then the unknown ones; also the columns of the index that serves it. */
    static final String USERNAME_ORDER = "COALESCE(owner_username, '') = '', owner_username COLLATE NOCASE,"
            + " saved_at DESC";
    /** The source of a download whose origin was not recorded; other sources are the stored tab names. */
    static final String SOURCE_UNKNOWN = "unknown";
    // Generic URL downloads, such as profile pictures, land in Posts with neither a link nor a media id.
    private static final String UNPROVEN_POST = "tab = 'posts' AND COALESCE(link, '') = '' AND COALESCE(media_id, '') = ''";

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
        public final boolean favorite;
        /** The stored tab's name, or {@link #SOURCE_UNKNOWN}. */
        public final String source;

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
            favorite = c.getInt(15) != 0;
            source = source(c.getString(2), item.link, item.mediaId);
        }
    }

    private static volatile GalleryStore instance;

    private final SQLiteDatabase db;
    private final File dir;
    private final File thumbDir;
    private final LongSupplier clock;
    private boolean recovered;
    // Bumped by deleteAll, so a download that started before a clear never publishes after it.
    private long clearEpoch;

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

    /** Whether a saved download is of media {@code mediaId} and of {@code kind}: a video's audio and cover share its id. */
    synchronized boolean hasMedia(String mediaId, String kind) {
        if (mediaId == null || kind == null) return false;
        recover();
        try (Cursor c = db.rawQuery("SELECT 1 FROM gallery_items WHERE media_id = ? AND kind = ? LIMIT 1",
                new String[]{mediaId, kind})) {
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

    /** The clear epoch a download reads when it starts and hands back to {@link #publish}. */
    public synchronized long clearEpoch() {
        return clearEpoch;
    }

    /** {@link #publish} that with SKIP deletes the part and returns -1 when the name is taken, checked under the store's lock. */
    public long publish(File part, GalleryItem item, String subFolder, String fileName,
                        Conflict conflict) throws IOException {
        return publish(part, item, subFolder, fileName, conflict, null);
    }

    /** {@link #publish}, also deleting the part and failing when the gallery was cleared after {@code startedEpoch}. */
    public long publish(File part, GalleryItem item, String subFolder, String fileName,
                        Conflict conflict, Long startedEpoch) throws IOException {
        return publish(part, item, subFolder, fileName, conflict, startedEpoch, null);
    }

    /** {@link #publish} that keeps an imported item's {@code savedAt}; null saves it as now. */
    long publish(File part, GalleryItem item, String subFolder, String fileName,
                 Conflict conflict, Long startedEpoch, Long savedAt) throws IOException {
        try {
            if (!part.isFile()) throw new IOException("Missing " + part);
            Long durationMs = GalleryItem.PHOTO.equals(item.kind) ? null : durationMs(part);
            long size = part.length();
            synchronized (this) {
                // A failure, not a name conflict, so the download is reported as not saved and can be retried.
                if (startedEpoch != null && startedEpoch != clearEpoch) {
                    throw new IOException("The gallery was cleared during the download");
                }
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
                values.put("saved_at", savedAt != null ? savedAt : clock.getAsLong());
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
        return list(tab.name().toLowerCase(Locale.ROOT), null, false, null, SORT_NEWEST);
    }

    /** {@link #UNPROVEN_POST} in Java. */
    static String source(String tab, String link, String mediaId) {
        boolean unproven = "posts".equals(tab) && (link == null || link.isEmpty())
                && (mediaId == null || mediaId.isEmpty());
        return unproven ? SOURCE_UNKNOWN : tab;
    }

    /** The condition, after WHERE 1, of downloads from {@code source}; null matches every source. */
    private static String sourceWhere(String source, List<String> args) {
        if (source == null) return "";
        if (SOURCE_UNKNOWN.equals(source)) return " AND " + UNPROVEN_POST;
        args.add(source);
        return " AND tab = ?" + ("posts".equals(source) ? " AND NOT (" + UNPROVEN_POST + ")" : "");
    }

    /**
     * Saved downloads matching every filter given, in {@code sort} order: a null {@code source} or
     * {@code kind} matches all, and {@code username} is a case-insensitive part of the owner's name.
     * By username, downloads without an owner come last.
     */
    public synchronized List<Entry> list(String source, String kind, boolean favoritesOnly, String username,
                                         String sort) {
        return list(source, kind, favoritesOnly, username, sort, false, null);
    }

    /**
     * {@link #list(String, String, boolean, String, String)} that with {@code accountOnly} keeps only
     * downloads saved by {@code accountId}: none when it is null, and never one without an account.
     */
    public synchronized List<Entry> list(String source, String kind, boolean favoritesOnly, String username,
                                         String sort, boolean accountOnly, String accountId) {
        recover();
        List<String> args = new ArrayList<>();
        List<Entry> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(listQuery(source, kind, favoritesOnly, username, sort, accountOnly, accountId,
                args), args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(new Entry(c, dir));
        }
        return out;
    }

    /** The query of {@link #list}, adding its arguments to {@code args}. */
    static String listQuery(String source, String kind, boolean favoritesOnly, String username, String sort,
                            boolean accountOnly, String accountId, List<String> args) {
        StringBuilder where = new StringBuilder(" WHERE 1").append(sourceWhere(source, args));
        if (accountOnly && accountId == null) {
            where.append(" AND 0");
        } else if (accountOnly) {
            where.append(" AND account_id = ?");
            args.add(accountId);
        }
        if (kind != null) {
            where.append(" AND kind = ?");
            args.add(kind);
        }
        if (favoritesOnly) where.append(" AND f.item_id IS NOT NULL");
        if (username != null && !username.isEmpty()) {
            where.append(" AND owner_username LIKE ? ESCAPE '\\'");
            args.add("%" + username.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String order = SORT_OLDEST.equals(sort) ? "saved_at, id"
                : SORT_SIZE.equals(sort) ? "size_bytes DESC, id DESC"
                : SORT_USERNAME.equals(sort) ? USERNAME_ORDER
                : "saved_at DESC, id DESC";
        return "SELECT " + COLUMNS + ", f.item_id IS NOT NULL FROM gallery_items"
                + " LEFT JOIN gallery_favorites f ON f.vault = 0 AND f.item_id = id"
                + where + " ORDER BY " + order;
    }

    /** Whether any saved download is from {@code source}. */
    public synchronized boolean has(String source) {
        recover();
        List<String> args = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT 1 FROM gallery_items WHERE 1" + sourceWhere(source, args) + " LIMIT 1",
                args.toArray(new String[0]))) {
            return c.moveToFirst();
        }
    }

    /** Stars or unstars a download, or with {@code vault} a stored DM item; a deleted download loses its star. */
    public synchronized void setFavorite(boolean vault, long id, boolean favorite) {
        String[] args = {vault ? "1" : "0", String.valueOf(id)};
        if (favorite) {
            db.execSQL("INSERT OR IGNORE INTO gallery_favorites (vault, item_id) VALUES (?, ?)", args);
        } else {
            db.delete("gallery_favorites", "vault = ? AND item_id = ?", args);
        }
    }

    /** Row ids of the starred stored DM items. */
    public synchronized Set<Long> favoriteVaultIds() {
        Set<Long> out = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT item_id FROM gallery_favorites WHERE vault = 1", null)) {
            while (c.moveToNext()) out.add(c.getLong(0));
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
     * Deletes every item: its thumbnail and file, then its row. False when a file or thumbnail that
     * still exists could not be deleted; an item whose file stays keeps its row for the next clear.
     * Throws on database errors.
     */
    public synchronized boolean deleteAll() {
        clearEpoch++;
        recover();
        List<Object[]> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, file FROM gallery_items", null)) {
            while (c.moveToNext()) rows.add(new Object[]{c.getLong(0), c.getString(1)});
        }
        boolean deleted = true;
        for (Object[] row : rows) {
            String file = (String) row[1];
            File item = new File(dir, file);
            File thumb = thumb(file);
            if (!thumb.delete() && thumb.exists()) deleted = false;
            // A file that stays keeps its row, so the next clear tries it again.
            if (!item.delete() && item.exists()) {
                deleted = false;
                continue;
            }
            db.delete("gallery_items", "id = ?", new String[]{String.valueOf(row[0])});
        }
        return deleted;
    }

    /** {count, total size in bytes} of the saved downloads. */
    public synchronized long[] totals() {
        recover();
        try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM gallery_items", null)) {
            c.moveToFirst();
            return new long[]{c.getLong(0), c.getLong(1)};
        }
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
