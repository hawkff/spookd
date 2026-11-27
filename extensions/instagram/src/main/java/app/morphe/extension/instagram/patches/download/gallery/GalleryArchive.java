package app.morphe.extension.instagram.patches.download.gallery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
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
    static final long MAX_MANIFEST_BYTES = 16L << 20;
    static final int MAX_ITEMS = 50_000;
    private static final int BUFFER = 64 * 1024;

    private GalleryArchive() {
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
}
