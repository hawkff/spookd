/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.format.Formatter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.db.SpookdInstantsDb;
import app.morphe.extension.instagram.patches.analyzer.ProfileAnalyzer;
import app.morphe.extension.instagram.patches.dm.vault.MediaVault;
import app.morphe.extension.instagram.patches.download.gallery.GalleryStorage;
import app.morphe.extension.instagram.patches.instants.InstantsDownloadHook;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The storage list opened from settings, and the opt-in cache cleanup at launch. Sizes are read and
 * items cleared on a background thread; dialogs run on the main thread.
 */
public final class StorageScreen {
    // The names SpookdFollowDb and SpookdInstantsDb open.
    private static final String ANALYZER_DB = "piko_follow_snapshots.db";
    private static final String INSTANTS_DB = "piko_instants_vault.db";
    private static final Set<String> KEEP_IN_CACHE = Collections.singleton(GalleryStorage.SHARE_DIR);
    private static final long BYTES_PER_MB = 1_000_000L;
    // Cache files written this recently may still be in use, such as a download's staged cover.
    private static final long IN_USE_MS = 10 * 60_000L;
    // Clears run one at a time, so two never race over the same files.
    private static final ExecutorService CLEARS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "spookd-storage-clear");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private enum Item {
        GALLERY("spookd_storage_gallery", true),
        VAULT("spookd_storage_vault", true),
        INSTANTS("spookd_storage_instants", true),
        HISTORY("spookd_storage_history", true),
        ANALYZER("spookd_storage_analyzer", true),
        CACHE("spookd_storage_cache", false);

        final String title;
        final boolean userData;

        Item(String title, boolean userData) {
            this.title = title;
            this.userData = userData;
        }
    }

    private StorageScreen() {
    }

    /** Main thread. */
    public static void show(Context context) {
        Context app = context.getApplicationContext();
        AlertDialog loading = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(str("spookd_storage"))
                .setMessage(str("spookd_storage_calculating"))
                .show();
        background(() -> {
            Item[] items = Item.values();
            String[] amounts = new String[items.length];
            for (Item item : items) amounts[item.ordinal()] = amount(app, item);
            Utils.runOnMainThread(() -> {
                if (!loading.isShowing() || isGone(context)) return;
                loading.dismiss();
                String[] rows = new String[items.length];
                for (Item item : items) {
                    rows[item.ordinal()] = String.format(str("spookd_storage_item"), str(item.title), amounts[item.ordinal()]);
                }
                new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                        .setTitle(str("spookd_storage"))
                        .setItems(rows, (dialog, which) -> onPicked(context, items[which]))
                        .setNegativeButton(str("spookd_close"), null)
                        .show();
            });
        });
    }

    private static void onPicked(Context context, Item item) {
        if (!item.userData) {
            clear(context, item);
            return;
        }
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(String.format(str("spookd_storage_clear_confirm"), str(item.title)))
                .setNegativeButton(str("spookd_cancel"), null)
                .setPositiveButton(str("spookd_delete"), (dialog, which) -> clear(context, item))
                .show();
    }

    private static void clear(Context context, Item item) {
        Context app = context.getApplicationContext();
        clearInOrder(() -> {
            boolean cleared;
            try {
                cleared = clearItem(app, item);
            } catch (Throwable t) {
                Logger.printException(() -> "Failed to clear " + item, t);
                cleared = false;
            }
            boolean done = cleared;
            Utils.runOnMainThread(() -> {
                Utils.showToastShort(String.format(
                        str(done ? "spookd_storage_cleared" : "spookd_storage_clear_failed"), str(item.title)));
                if (!isGone(context)) show(context);
            });
        });
    }

    /**
     * Called on the main thread once startup succeeded; clears Instagram's cache on a background
     * thread when it is over the chosen size. Never touches saved data.
     */
    static void autoClearCache(Context context) {
        Context app = context.getApplicationContext();
        clearInOrder(() -> GalleryStorage.sweepPicks(app));
        long limitMb = Math.min(Pref.autoClearCacheMb(), 1_000_000L);
        if (limitMb <= 0) return;
        clearInOrder(() -> {
            if (cacheBytes(app) > limitMb * BYTES_PER_MB && !clearCache(app)) {
                Logger.printInfo(() -> "Some cache files could not be deleted");
            }
        });
    }

    private static String amount(Context context, Item item) {
        try {
            switch (item) {
                case GALLERY:
                    return Formatter.formatShortFileSize(context, GalleryStorage.galleryBytes());
                case VAULT:
                    long vault = 0;
                    for (MediaVault.Item stored : MediaVault.get().storedItems()) vault += stored.sizeBytes;
                    return Formatter.formatShortFileSize(context, vault);
                case INSTANTS:
                    return String.format(str("spookd_storage_entries_size"),
                            SpookdInstantsDb.getInstance(context).getAll().size(),
                            Formatter.formatShortFileSize(context, databaseBytes(context, INSTANTS_DB)));
                case HISTORY:
                    int history = GalleryStorage.historyEntries();
                    int rows = history + GalleryStorage.galleryEntries();
                    // Both share one file, so the history gets the part its rows are of all rows.
                    long share = rows == 0 ? 0 : databaseBytes(context, GalleryStorage.DATABASE) * history / rows;
                    return String.format(str("spookd_storage_history_size"), history,
                            Formatter.formatShortFileSize(context, share));
                case ANALYZER:
                    return Formatter.formatShortFileSize(context, databaseBytes(context, ANALYZER_DB));
                default:
                    return Formatter.formatShortFileSize(context, cacheBytes(context));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to measure " + item, t);
            return "-";
        }
    }

    private static boolean clearItem(Context context, Item item) throws Exception {
        switch (item) {
            case GALLERY:
                return GalleryStorage.clearGallery();
            case VAULT:
                return MediaVault.get().deleteAll();
            case INSTANTS:
                return InstantsDownloadHook.clearVault(SpookdInstantsDb.getInstance(context));
            case HISTORY:
                GalleryStorage.clearHistory();
                return true;
            case ANALYZER:
                return ProfileAnalyzer.deleteAllData(context);
            default:
                return clearCache(context);
        }
    }

    private static long cacheBytes(Context context) {
        return sizeOfContents(context.getCacheDir(), KEEP_IN_CACHE) + sizeOf(context.getExternalCacheDir());
    }

    /** False when a file that still exists could not be deleted. */
    private static boolean clearCache(Context context) {
        long olderThan = System.currentTimeMillis() - IN_USE_MS;
        boolean internal = clearContents(context.getCacheDir(), KEEP_IN_CACHE, olderThan);
        return clearContents(context.getExternalCacheDir(), Collections.emptySet(), olderThan) && internal;
    }

    private static long databaseBytes(Context context, String name) {
        String path = context.getDatabasePath(name).getPath();
        long total = 0;
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) total += new File(path + suffix).length();
        return total;
    }

    /** Bytes under {@code file}; symbolic links count as nothing and are not followed. */
    static long sizeOf(File file) {
        if (file == null || Files.isSymbolicLink(file.toPath())) return 0;
        if (!file.isDirectory()) return file.length();
        return sizeOfContents(file, Collections.emptySet());
    }

    static long sizeOfContents(File dir, Set<String> keep) {
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File child : children) {
            if (!keep.contains(child.getName())) total += sizeOf(child);
        }
        return total;
    }

    static boolean clearContents(File dir, Set<String> keep) {
        return clearContents(dir, keep, Long.MAX_VALUE);
    }

    /**
     * Deletes everything inside {@code dir} but the entries named in {@code keep} and files modified
     * after {@code olderThan}, with the folders holding them; links are removed, not followed. False
     * when something else that still exists could not be deleted; missing files count as gone.
     */
    static boolean clearContents(File dir, Set<String> keep, long olderThan) {
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) return dir == null || !dir.exists();
        boolean cleared = true;
        for (File child : children) {
            if (keep.contains(child.getName())) continue;
            Path path = child.toPath();
            boolean link = Files.isSymbolicLink(path);
            if (!link && child.isDirectory()) {
                cleared &= clearContents(child, Collections.emptySet(), olderThan);
                String[] left = child.list();
                if (left != null && left.length > 0) continue;
            } else if (!link && child.lastModified() > olderThan) {
                continue;
            }
            if (!child.delete() && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) cleared = false;
        }
        return cleared;
    }

    private static boolean isGone(Context context) {
        return context instanceof Activity && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed());
    }

    private static void clearInOrder(Runnable task) {
        CLEARS.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Logger.printException(() -> "Storage clear failed", t);
            }
        });
    }

    private static void background(Runnable task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Logger.printException(() -> "Storage task failed", t);
            }
        }, "spookd-storage");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }
}
