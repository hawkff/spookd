package app.morphe.extension.instagram.patches.download.gallery;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.crimera.legacydownloader.DownloadRequest;
import app.morphe.extension.crimera.legacydownloader.MediaDownloader;
import app.morphe.extension.crimera.legacydownloader.StorageUtils;
import app.morphe.extension.instagram.patches.dm.vault.MediaVault;
import app.morphe.extension.instagram.patches.dm.vault.PlainMedia;
import app.morphe.extension.instagram.patches.dm.vault.ViewerMedia;
import app.morphe.extension.shared.Utils;

/** Export, share and open actions of the private gallery. */
final class GalleryActions {
    static final String SHARE_DIR = "piko_share";
    // Folders of items returned to Instagram's picker, which imports them later on its own thread.
    static final String PICK_PREFIX = "pick-";
    // shortcut: a fixed hour instead of knowing when Instagram's import ended; long enough for any video copy.
    static final long PICK_KEEP_MS = 60 * 60_000L;
    // Picks waiting for that hour may not take more than this; a pick past it is refused, nothing is evicted.
    static final long PICK_BUDGET_BYTES = 1_000_000_000L;
    // The manifest keeps this class name even when the code is minified; clones rename only the authority.
    private static final String FILE_PROVIDER = "androidx.core.content.FileProvider";

    private GalleryActions() {
    }

    /**
     * The item's plaintext, read on the caller's background thread; DM vault items decrypt as they
     * stream. Closing the stream closes the media.
     */
    static InputStream open(boolean vault, long id, File file) throws IOException {
        ViewerMedia media;
        if (vault) {
            MediaVault.Stored stored = MediaVault.get().open(id);
            if (stored == null) throw new IOException("DM media " + id + " is no longer stored");
            media = stored.file;
        } else {
            media = new PlainMedia(file);
        }
        ViewerMedia opened = media;
        return new FilterInputStream(opened.openStream()) {
            @Override
            public void close() throws IOException {
                opened.close();
            }
        };
    }

    /**
     * Main thread. Exports through the download writer without its per-item toasts, then shows how
     * many were saved once every request has ended. Without a folder grant the writer opens the
     * folder picker instead and nothing is exported.
     */
    static void exportAll(Context context, List<DownloadRequest> requests) {
        int total = requests.size();
        AtomicInteger ended = new AtomicInteger();
        AtomicInteger saved = new AtomicInteger();
        for (DownloadRequest request : requests) {
            request.onFinished = done -> {
                if (done) saved.incrementAndGet();
                if (ended.incrementAndGet() == total) {
                    Utils.showToastShort(str("spookd_gallery_exported", saved.get(), total));
                }
            };
        }
        if (StorageUtils.checkStoragePermissions()) Utils.showToastShort(str("spookd_gallery_exporting"));
        new MediaDownloader(context) {
            @Override
            protected void showToast(String msg) {
            }
        }.enqueueBatch(requests);
    }

    /**
     * Deletes earlier share copies, except picks younger than {@link #PICK_KEEP_MS}, and returns their
     * directory. Runs on the caller's background thread.
     */
    static File clearShared(Context context) {
        File dir = new File(context.getCacheDir(), SHARE_DIR);
        clearShared(dir, System.currentTimeMillis(), false);
        return dir;
    }

    /** Deletes picks older than {@link #PICK_KEEP_MS} only; runs on the caller's background thread. */
    static void sweepPicks(Context context) {
        clearShared(new File(context.getCacheDir(), SHARE_DIR), System.currentTimeMillis(), true);
    }

    static void clearShared(File dir, long now, boolean expiredPicksOnly) {
        File[] entries = dir.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                boolean pick = entry.getName().startsWith(PICK_PREFIX);
                if (pick ? now - entry.lastModified() < PICK_KEEP_MS : expiredPicksOnly) continue;
                File[] inner = entry.listFiles();
                if (inner != null) for (File file : inner) file.delete();
                entry.delete();
            }
        }
    }

    /**
     * Copies the item to {@code dir}, the share folder {@link #clearShared} returned, and returns its
     * URI through the app's own file provider. Runs on the caller's background thread.
     */
    static Uri shareCopy(Context context, File dir, String name, boolean vault, long id, File file) throws Exception {
        return copy(context, dir, "", name, vault, id, file);
    }

    /** Bytes the picks in {@code dir} still hold; runs on the caller's background thread. */
    static long pickBytes(File dir) {
        long total = 0;
        File[] entries = dir.listFiles();
        if (entries == null) return 0;
        for (File entry : entries) {
            File[] inner = entry.getName().startsWith(PICK_PREFIX) ? entry.listFiles() : null;
            if (inner != null) for (File file : inner) total += file.length();
        }
        return total;
    }

    /** Whether a pick of {@code size} bytes fits next to {@code used} bytes of waiting picks. */
    static boolean fitsPickBudget(long used, long size) {
        return size >= 0 && used + size <= PICK_BUDGET_BYTES;
    }

    /** {@link #shareCopy} for Instagram's picker: the copy outlives the next clear for {@link #PICK_KEEP_MS}. */
    static Uri pickCopy(Context context, File dir, String name, boolean vault, long id, File file) throws Exception {
        return copy(context, dir, PICK_PREFIX, name, vault, id, file);
    }

    private static Uri copy(Context context, File dir, String prefix, String name, boolean vault, long id, File file)
            throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        // A fresh random folder per share: a URI granted earlier never points at a later item.
        File folder = new File(dir, prefix + UUID.randomUUID());
        if (!folder.mkdirs()) throw new IOException("Cannot create " + folder);
        File copy = new File(folder, name.replace('/', '_'));
        try (InputStream in = open(vault, id, file); OutputStream out = new FileOutputStream(copy)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) != -1; ) out.write(buffer, 0, count);
        } catch (Exception e) {
            copy.delete();
            throw e;
        }
        return shareUri(fileProviderAuthority(context), folder.getName(), copy.getName());
    }

    static String fileProviderAuthority(Context context) throws PackageManager.NameNotFoundException {
        String authorities = context.getPackageManager()
                .getProviderInfo(new ComponentName(context, FILE_PROVIDER), 0).authority;
        return authorities.split(";")[0];
    }

    /** The provider serves its {@code cache} path name from the cache directory. */
    static Uri shareUri(String authority, String folder, String name) {
        return new Uri.Builder().scheme("content").authority(authority)
                .appendPath("cache").appendPath(SHARE_DIR).appendPath(folder).appendPath(name).build();
    }

    /** Main thread: lets the user pick where to send the shared copies; {@code uris} is not empty. */
    static void share(Activity activity, ArrayList<Uri> uris, String mimeType) {
        Intent send = uris.size() == 1
                ? new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0))
                : new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.setType(mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = ClipData.newRawUri(null, uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        activity.startActivity(Intent.createChooser(send, null));
    }

    static String mimeType(String name, String kind) {
        String guessed = URLConnection.guessContentTypeFromName(name);
        if (guessed != null) return guessed;
        return GalleryItem.PHOTO.equals(kind) ? "image/*" : GalleryItem.VIDEO.equals(kind) ? "video/*" : "audio/*";
    }

    /** The one type of every copy, else their shared group such as {@code image/*}, else any type; not empty. */
    static String commonMimeType(List<String> types) {
        String first = types.get(0);
        String group = first.substring(0, first.indexOf('/') + 1);
        boolean same = true;
        boolean sameGroup = true;
        for (String type : types) {
            same &= type.equals(first);
            sameGroup &= type.startsWith(group);
        }
        return same ? first : sameGroup && !group.isEmpty() ? group + "*" : "*/*";
    }

    /** Main thread: opens an Instagram link in this app, so clones open it in themselves. */
    static void view(Activity activity, String url) {
        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(activity.getPackageName()));
    }

    static String profileLink(String username) {
        return "https://www.instagram.com/" + Uri.encode(username) + "/";
    }

    /** The name the deleted-messages screen gives a DM download; the row id stands in for a missing item id. */
    static String dmFileName(String itemId, long rowId, String mime, String kind) {
        String type = mime == null ? "" : mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
        String extension = type.isEmpty() ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
        if (extension == null) {
            extension = GalleryItem.PHOTO.equals(kind) ? "jpg" : GalleryItem.VIDEO.equals(kind) ? "mp4" : "m4a";
        }
        return "piko_" + (itemId == null || itemId.isEmpty() ? String.valueOf(rowId) : itemId) + "." + extension;
    }
}
