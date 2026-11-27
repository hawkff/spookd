package app.morphe.extension.instagram.patches.download.gallery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The gallery backup file: a zip whose first entry is {@link #MANIFEST}, then one {@link #MEDIA}
 * entry per download named by its stored file. Streams on the caller's background thread; stored
 * DM media is not part of it, since its key never leaves this device.
 */
final class GalleryArchive {
    static final int SCHEMA = 1;
    static final String MANIFEST = "manifest.json";
    static final String MEDIA = "media/";
    /** The export subfolder of photos and videos added from Files. */
    static final String IMPORTED_FOLDER = "Imported";
    static final long MAX_MANIFEST_BYTES = 16L << 20;
    static final int MAX_ITEMS = 50_000;
    static final long MAX_FILE_BYTES = 2L << 30;
    // Left free on the device, so an import never fills the storage the app itself needs.
    static final long MIN_FREE_BYTES = 200L << 20;
    private static final int MAX_TEXT = 4096;
    private static final int MAX_NAME = 255;
    private static final int BUFFER = 64 * 1024;
    private static final Pattern STORED_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}(\\.[A-Za-z0-9]{1,8})?");
    // The same suffix GalleryStore gives a stored file.
    private static final Pattern EXTENSION = Pattern.compile("\\.([A-Za-z0-9]{1,8})$");
    static final Set<String> PHOTO_TYPES = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp", "gif",
            "heic", "heif"));
    static final Set<String> VIDEO_TYPES = new HashSet<>(Arrays.asList("mp4", "mov", "webm", "mkv", "3gp"));
    static final Set<String> AUDIO_TYPES = new HashSet<>(Arrays.asList("m4a", "mp3", "aac", "ogg", "opus",
            "wav", "amr"));
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList(GalleryItem.PHOTO, GalleryItem.VIDEO,
            GalleryItem.AUDIO));

    private GalleryArchive() {
    }

    /** The file is not a gallery backup, or its manifest is damaged; nothing was imported. */
    static final class InvalidBackup extends IOException {
        InvalidBackup(String message) {
            super(message);
        }
    }

    /** One file is larger than {@link #MAX_FILE_BYTES} or than the space left; the import goes on. */
    static final class TooLarge extends IOException {
    }

    /** One manifest item: what its download is restored with. */
    static final class Item {
        final GalleryItem item;
        final String fileName;
        final String subFolder;
        final long savedAt;
        final boolean favorite;

        Item(GalleryItem item, String fileName, String subFolder, long savedAt, boolean favorite) {
            this.item = item;
            this.fileName = fileName;
            this.subFolder = subFolder;
            this.savedAt = savedAt;
            this.favorite = favorite;
        }
    }

    static final class Manifest {
        /** Valid items by stored file name. */
        final Map<String, Item> items = new HashMap<>();
        /** Items left out because a field is missing, of the wrong type or unsafe. */
        int invalid;
    }

    /** Counts of one import, kept up to date while it runs, so a failure part way can still report them. */
    static final class Result {
        int imported;
        /** Already in the gallery, by media id or by identical bytes. */
        int duplicates;
        int failed;
    }

    /** Gallery files by size, hashed only when a new file of the same size has to be compared. */
    static final class Duplicates {
        private final Map<Long, List<File>> unhashed = new HashMap<>();
        private final Set<String> known = new HashSet<>();

        Duplicates(List<GalleryStore.Entry> entries) {
            for (GalleryStore.Entry entry : entries) {
                unhashed.computeIfAbsent(entry.sizeBytes, size -> new ArrayList<>()).add(entry.file);
            }
        }

        /** Whether a gallery file has these bytes; one that cannot be read matches nothing. */
        boolean contains(long size, String hash) {
            List<File> files = unhashed.remove(size);
            if (files != null) {
                for (File file : files) {
                    try (InputStream in = new FileInputStream(file)) {
                        known.add(size + ":" + hash(in));
                    } catch (IOException ignored) {
                    }
                }
            }
            return known.contains(size + ":" + hash);
        }

        void add(long size, String hash) {
            known.add(size + ":" + hash);
        }
    }

    /** The manifest of {@code entries}: their stored file, tab and every detail a download keeps. */
    static String manifest(List<GalleryStore.Entry> entries) throws JSONException {
        JSONArray items = new JSONArray();
        for (GalleryStore.Entry entry : entries) {
            GalleryItem item = entry.item;
            items.put(new JSONObject()
                    .put("file", entry.file.getName())
                    .put("tab", item.tab.name().toLowerCase(Locale.ROOT))
                    .put("kind", item.kind)
                    .put("fileName", entry.fileName)
                    .put("subFolder", entry.subFolder)
                    .put("owner", item.ownerUsername)
                    .put("ownerId", item.ownerId)
                    .put("mediaId", item.mediaId)
                    .put("link", item.link)
                    .put("accountId", item.accountId)
                    .put("savedAt", entry.savedAt)
                    .put("takenAt", item.takenAt)
                    .put("favorite", entry.favorite));
        }
        return new JSONObject().put("schema", SCHEMA).put("items", items).toString();
    }

    /**
     * Writes every download, oldest first, to {@code out}, which the caller closes, and returns how
     * many files went in; one deleted meanwhile is left out. {@code progress} gets (done, total).
     */
    static int write(GalleryStore store, OutputStream out, BiConsumer<Integer, Integer> progress) throws IOException {
        List<GalleryStore.Entry> entries = store.list(null, null, false, null, GalleryStore.SORT_OLDEST);
        // An archive the import would refuse is no backup.
        if (entries.size() > MAX_ITEMS) throw new IOException("Too many items: " + entries.size());
        byte[] manifest;
        try {
            manifest = manifest(entries).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (manifest.length > MAX_MANIFEST_BYTES) throw new IOException("Manifest too large");
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out, BUFFER));
        // Photos and videos are compressed already.
        zip.setLevel(Deflater.NO_COMPRESSION);
        zip.putNextEntry(new ZipEntry(MANIFEST));
        zip.write(manifest);
        zip.closeEntry();
        byte[] buffer = new byte[BUFFER];
        int written = 0;
        for (int i = 0; i < entries.size(); i++) {
            GalleryStore.Entry entry = entries.get(i);
            InputStream in;
            try {
                in = new FileInputStream(entry.file);
            } catch (FileNotFoundException e) {
                continue;
            }
            try (InputStream source = in) {
                ZipEntry zipEntry = new ZipEntry(MEDIA + entry.file.getName());
                zipEntry.setTime(entry.savedAt);
                zip.putNextEntry(zipEntry);
                for (int n; (n = source.read(buffer)) != -1; ) zip.write(buffer, 0, n);
                zip.closeEntry();
            }
            written++;
            progress.accept(i + 1, entries.size());
        }
        zip.finish();
        zip.flush();
        return written;
    }

    /**
     * Merges the backup in {@code in}, which the caller closes, into the store, counting into
     * {@code result}: an item whose media id or bytes are already saved is skipped. Throws
     * {@link InvalidBackup} before importing anything when the manifest is missing or damaged.
     */
    static void read(GalleryStore store, InputStream in, Result result, BiConsumer<Integer, Integer> progress)
            throws IOException {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in, BUFFER));
        ZipEntry first = zip.getNextEntry();
        if (first == null || !MANIFEST.equals(first.getName())) throw new InvalidBackup("No manifest first");
        Manifest manifest = parse(readManifest(zip));
        result.failed += manifest.invalid;
        int total = manifest.items.size();
        Duplicates duplicates = new Duplicates(store.list(null, null, false, null, GalleryStore.SORT_NEWEST));
        long epoch = store.clearEpoch();
        int done = 0;
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            String name = entry.getName();
            // Entry names only look up manifest items, never paths; a repeated or unknown entry finds none.
            Item item = entry.isDirectory() || !name.startsWith(MEDIA) ? null
                    : manifest.items.remove(name.substring(MEDIA.length()));
            if (item == null) {
                result.failed++;
                continue;
            }
            progress.accept(++done, total);
            String mediaId = item.item.mediaId;
            if (mediaId != null && !mediaId.isEmpty() && store.hasMedia(mediaId)) {
                result.duplicates++;
                continue;
            }
            long id;
            try {
                id = add(store, duplicates, epoch, zip, item.item, item.subFolder, item.fileName, item.savedAt);
            } catch (TooLarge e) {
                result.failed++;
                continue;
            }
            if (id == -1) {
                result.duplicates++;
                continue;
            }
            if (item.favorite) store.setFavorite(false, id, true);
            result.imported++;
        }
        // Listed, but not in the archive: deleted while the backup was written.
        result.failed += manifest.items.size();
    }

    /**
     * Copies {@code in} into a new gallery item and returns its id, or -1, keeping nothing, when the
     * gallery already has these bytes. Throws {@link TooLarge} for a file over the limit.
     */
    static long add(GalleryStore store, Duplicates duplicates, long epoch, InputStream in, GalleryItem item,
                    String subFolder, String fileName, Long savedAt) throws IOException {
        File part = store.partFile();
        String hash = copy(in, part, limit(part));
        long size = part.length();
        if (duplicates.contains(size, hash)) {
            part.delete();
            return -1;
        }
        long id = store.publish(part, item, subFolder, fileName, GalleryStore.Conflict.RENAME, epoch, savedAt);
        duplicates.add(size, hash);
        return id;
    }

    /**
     * The gallery name of a {@code kind} file picked in Files, keeping its name when its suffix is a
     * known type of that kind, else adding the suffix of its {@code mimeExtension}; null when neither is.
     */
    static String importedName(String displayName, String mimeExtension, String kind) {
        String name = displayName == null ? "" : displayName.replaceAll("[\\x00-\\x1f/\\\\]", "_");
        // The end keeps the suffix.
        if (name.length() > 200) name = name.substring(name.length() - 200);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "imported";
        Set<String> types = GalleryItem.PHOTO.equals(kind) ? PHOTO_TYPES : VIDEO_TYPES;
        String extension = extension(name);
        if (extension != null && types.contains(extension)) return name;
        if (mimeExtension == null || !types.contains(mimeExtension.toLowerCase(Locale.ROOT))) return null;
        return name + "." + mimeExtension.toLowerCase(Locale.ROOT);
    }

    /** The manifest's items; throws {@link InvalidBackup} unless it is this schema's JSON within the limits. */
    static Manifest parse(String json) throws InvalidBackup {
        Manifest manifest = new Manifest();
        try {
            JSONObject root = new JSONObject(json);
            if (!Integer.valueOf(SCHEMA).equals(root.opt("schema"))) throw new InvalidBackup("Unknown schema");
            JSONArray items = root.getJSONArray("items");
            if (items.length() > MAX_ITEMS) throw new InvalidBackup("Too many items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject object = items.optJSONObject(i);
                String file;
                Item item;
                try {
                    if (object == null) throw new IllegalArgumentException("Not an object");
                    file = text(object, "file");
                    if (file == null || !STORED_NAME.matcher(file).matches()) throw new IllegalArgumentException("file");
                    item = item(object);
                } catch (IllegalArgumentException e) {
                    manifest.invalid++;
                    continue;
                }
                if (manifest.items.put(file, item) != null) throw new InvalidBackup("Repeated file " + file);
            }
        } catch (JSONException e) {
            throw new InvalidBackup("Not a manifest: " + e.getMessage());
        }
        return manifest;
    }

    /** Throws {@link IllegalArgumentException} for any field that is missing, of the wrong type or unsafe. */
    private static Item item(JSONObject object) {
        String kind = text(object, "kind");
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("kind");
        String tab = text(object, "tab");
        if (tab == null) throw new IllegalArgumentException("tab");
        GalleryTab galleryTab = GalleryTab.valueOf(tab.toUpperCase(Locale.ROOT));
        String fileName = text(object, "fileName");
        if (!safeName(fileName, false) || !knownType(fileName)) throw new IllegalArgumentException("fileName");
        String subFolder = text(object, "subFolder");
        if (subFolder != null && !safeName(subFolder, true)) throw new IllegalArgumentException("subFolder");
        String link = text(object, "link");
        if (link != null && !link.startsWith("https://") && !link.startsWith("http://")) {
            throw new IllegalArgumentException("link");
        }
        Long savedAt = number(object, "savedAt");
        if (savedAt == null || savedAt < 0) throw new IllegalArgumentException("savedAt");
        Object favorite = object.opt("favorite");
        if (favorite != null && !(favorite instanceof Boolean)) throw new IllegalArgumentException("favorite");
        return new Item(new GalleryItem(galleryTab, kind, text(object, "owner"), text(object, "ownerId"),
                text(object, "mediaId"), link, text(object, "accountId"), number(object, "takenAt")),
                fileName, subFolder, savedAt, Boolean.TRUE.equals(favorite));
    }

    /** A string field, null when absent. */
    private static String text(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof String) || ((String) value).length() > MAX_TEXT) throw new IllegalArgumentException(key);
        return (String) value;
    }

    /** A whole-number field, null when absent. */
    private static Long number(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException(key);
        return ((Number) value).longValue();
    }

    /** A file name, or with {@code folder} a folder path, that cannot leave the folder it is written to. */
    static boolean safeName(String name, boolean folder) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == '\\' || (c == '/' && !folder)) return false;
        }
        for (String part : name.split("/")) {
            if (part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    /** Whether the name's suffix, when it has one, is a photo, video or audio type. */
    static boolean knownType(String fileName) {
        String extension = extension(fileName);
        return extension == null || PHOTO_TYPES.contains(extension) || VIDEO_TYPES.contains(extension)
                || AUDIO_TYPES.contains(extension);
    }

    /** The name's suffix, lowercase, without the dot; null without one. */
    static String extension(String fileName) {
        Matcher m = EXTENSION.matcher(fileName);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String readManifest(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER];
        for (int n; (n = in.read(buffer)) != -1; ) {
            if (bytes.size() + n > MAX_MANIFEST_BYTES) throw new InvalidBackup("Manifest too large");
            bytes.write(buffer, 0, n);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /** At most what the item may take: {@link #MAX_FILE_BYTES}, and never below {@link #MIN_FREE_BYTES} free. */
    private static long limit(File part) {
        File dir = part.getParentFile();
        return Math.min(MAX_FILE_BYTES, dir == null ? 0 : dir.getUsableSpace() - MIN_FREE_BYTES);
    }

    /** Copies {@code in} to {@code part} and returns the bytes' hash; on throw {@code part} is gone. */
    static String copy(InputStream in, File part, long limit) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[BUFFER];
        try (OutputStream out = new FileOutputStream(part)) {
            for (int n; (n = in.read(buffer)) != -1; ) {
                total += n;
                if (total > limit) throw new TooLarge();
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            part.delete();
            throw e;
        }
        return new BigInteger(1, digest.digest()).toString(16);
    }

    static String hash(InputStream in) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER];
        for (int n; (n = in.read(buffer)) != -1; ) digest.update(buffer, 0, n);
        return new BigInteger(1, digest.digest()).toString(16);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
