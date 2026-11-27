package app.morphe.extension.instagram.patches.download.gallery;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Backup and import of the private gallery through the system file picker. Dialogs and the picker
 * run on the main thread; the copying runs on one background thread behind a progress notification.
 */
@SuppressWarnings("deprecation")
public final class GalleryBackup {
    private static final String TAG = "spookd_gallery_backup";
    private static final String MODE = "mode";
    private static final int EXPORT = 0;
    private static final int IMPORT = 1;
    private static final int IMPORT_FILES = 2;
    // Providers label zip files differently.
    private static final String[] ARCHIVE_TYPES = {"application/zip", "application/x-zip-compressed",
            "application/octet-stream"};
    private static final int REQUEST = 0x5bac;
    private static final int NOTIFICATION_ID = 0x5bac;
    private static final String CHANNEL = "spookd_gallery_backup";
    // Serial, so a second backup or import waits for the one running.
    private static final Executor WORK = Executors.newSingleThreadExecutor();
    private static boolean channelCreated; // work thread only

    private GalleryBackup() {
    }

    /** Main thread, from the settings row: the screen lock first, then the choice. */
    public static void chooseLocked(Activity activity) {
        try {
            if (!Gallery.canOpen(activity)) {
                Utils.showToastLong(str("spookd_vault_needs_screen_lock"));
                return;
            }
            InboxLock.confirm(activity, () -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) choose(activity, null, null);
            });
        } catch (Exception e) {
            Logger.printException(() -> "Gallery backup: cannot ask for the screen lock", e);
            Utils.showToastShort(str("spookd_gallery_action_failed"));
        }
    }

    /**
     * Main thread, behind the gallery lock. {@code onPicking} runs right before the file picker
     * opens and {@code onReturned} once it closed; either may be null.
     */
    static void choose(Activity activity, Runnable onPicking, Runnable onReturned) {
        String[] labels = {str("spookd_gallery_backup_export"), str("spookd_gallery_backup_import"),
                str("spookd_gallery_import_files")};
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(str("spookd_gallery_backup"))
                .setItems(labels, (d, which) -> pick(activity, which, onPicking, onReturned))
                .setNegativeButton(str("spookd_cancel"), null)
                .show();
    }

    private static void pick(Activity activity, int mode, Runnable onPicking, Runnable onReturned) {
        try {
            FragmentManager fragments = activity.getFragmentManager();
            if (activity.isFinishing() || fragments.findFragmentByTag(TAG) != null) return;
            Picker picker = new Picker();
            Bundle args = new Bundle();
            args.putInt(MODE, mode);
            picker.setArguments(args);
            picker.onReturned = onReturned;
            fragments.beginTransaction().add(picker, TAG).commitAllowingStateLoss();
            if (onPicking != null) onPicking.run();
        } catch (Exception e) {
            Logger.printException(() -> "Gallery backup: cannot open the file picker", e);
            Utils.showToastShort(str("spookd_gallery_action_failed"));
        }
    }

    private static Intent intent(int mode) {
        if (mode == EXPORT) {
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
            return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "spookd_gallery_" + day + ".zip");
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        if (mode == IMPORT) return intent.putExtra(Intent.EXTRA_MIME_TYPES, ARCHIVE_TYPES);
        return intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    private static List<Uri> uris(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) out.add(uri);
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        return out;
    }

    /** Main thread: hands the picked documents to the work thread. */
    private static void start(Context app, int mode, List<Uri> uris) {
        Utils.showToastShort(str(mode == EXPORT ? "spookd_gallery_backup_running" : "spookd_gallery_importing"));
        WORK.execute(() -> {
            try {
                if (mode == EXPORT) export(app, uris.get(0));
                else if (mode == IMPORT) importArchive(app, uris.get(0));
                else importFiles(app, uris);
            } catch (Exception e) {
                Logger.printException(() -> "Gallery backup failed", e);
                Utils.showToastLong(str("spookd_gallery_action_failed"));
            } finally {
                cancelProgress(app);
            }
        });
    }

    /** Work thread. A backup that fails part way is deleted, so no partial file looks like a backup. */
    private static void export(Context app, Uri uri) {
        ContentResolver resolver = app.getContentResolver();
        int written;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot write the backup");
            written = GalleryArchive.write(GalleryStore.get(), out, progress(app, str("spookd_gallery_backup_running")));
        } catch (Exception e) {
            Logger.printException(() -> "Gallery backup: cannot write", e);
            try {
                DocumentsContract.deleteDocument(resolver, uri);
            } catch (Exception ignored) {
            }
            Utils.showToastLong(str("spookd_gallery_backup_failed"));
            return;
        }
        Utils.showToastLong(str("spookd_gallery_backup_done", written));
    }

    /** Work thread: merges a backup, skipping what the gallery already has. */
    private static void importArchive(Context app, Uri uri) {
        GalleryArchive.Result result = new GalleryArchive.Result();
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot read the backup");
            GalleryArchive.read(GalleryStore.get(), in, result, progress(app, str("spookd_gallery_importing")));
        } catch (GalleryArchive.InvalidBackup e) {
            Logger.printInfo(() -> "Gallery backup: not a backup", e);
            Utils.showToastLong(str("spookd_gallery_import_invalid"));
            return;
        } catch (Exception e) {
            Logger.printException(() -> "Gallery backup: import stopped", e);
            Utils.showToastLong(str("spookd_gallery_import_stopped", result.imported, result.duplicates, result.failed));
            return;
        }
        Utils.showToastLong(str("spookd_gallery_import_done", result.imported, result.duplicates, result.failed));
    }

    /** Work thread: copies picked photos and videos into the Imported source, owner unknown. */
    private static void importFiles(Context app, List<Uri> uris) {
        ContentResolver resolver = app.getContentResolver();
        GalleryStore store = GalleryStore.get();
        GalleryArchive.Duplicates duplicates = new GalleryArchive.Duplicates(
                store.list(null, null, false, null, GalleryStore.SORT_NEWEST));
        long epoch = store.clearEpoch();
        BiConsumer<Integer, Integer> progress = progress(app, str("spookd_gallery_importing"));
        GalleryArchive.Result result = new GalleryArchive.Result();
        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            try {
                String mime = resolver.getType(uri);
                String kind = mime == null ? null : mime.startsWith("image/") ? GalleryItem.PHOTO
                        : mime.startsWith("video/") ? GalleryItem.VIDEO : null;
                String name = kind == null ? null : GalleryArchive.importedName(displayName(resolver, uri),
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime), kind);
                if (name == null) {
                    result.failed++;
                } else {
                    try (InputStream in = resolver.openInputStream(uri)) {
                        if (in == null) throw new IOException("Cannot read a picked file");
                        GalleryItem item = new GalleryItem(GalleryTab.IMPORTED, kind, null, null, null, null, null, null);
                        long id = GalleryArchive.add(store, duplicates, epoch, in, item,
                                GalleryArchive.IMPORTED_FOLDER, name, null);
                        if (id == -1) result.duplicates++;
                        else result.imported++;
                    }
                }
            } catch (Exception e) {
                result.failed++;
                Logger.printException(() -> "Gallery import: cannot copy a picked file", e);
            }
            progress.accept(i + 1, uris.size());
        }
        Utils.showToastLong(str("spookd_gallery_import_done", result.imported, result.duplicates, result.failed));
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            Logger.printInfo(() -> "Gallery import: no file name", e);
        }
        return null;
    }

    /** Work thread: shows {@code title} now, then (done, total) at most twice a second. */
    private static BiConsumer<Integer, Integer> progress(Context app, String title) {
        showProgress(app, title, 0, 0);
        long[] last = {0};
        return (done, total) -> {
            long now = SystemClock.uptimeMillis();
            if (now - last[0] < 500 && done < total) return;
            last[0] = now;
            showProgress(app, title, done, total);
        };
    }

    /** Nothing without the notification permission; the toasts still tell the outcome. */
    private static void showProgress(Context app, String title, int done, int total) {
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return;
            if (!channelCreated) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL, str("spookd_private_gallery"),
                        NotificationManager.IMPORTANCE_LOW));
                channelCreated = true;
            }
            int icon = app.getApplicationInfo().icon;
            manager.notify(NOTIFICATION_ID, new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(total > 0 ? str("spookd_gallery_backup_progress", done, total) : null)
                    .setProgress(total, done, total <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build());
        } catch (Exception e) {
            Logger.printInfo(() -> "Gallery backup: no progress notification", e);
        }
    }

    private static void cancelProgress(Context app) {
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
    }

    /** Headless: opens the file picker and hands what was picked to the work thread. */
    public static final class Picker extends Fragment {
        // Lost when the activity is recreated meanwhile; the work still starts.
        Runnable onReturned;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState != null) return;
            try {
                startActivityForResult(intent(getArguments().getInt(MODE)), REQUEST);
            } catch (Exception e) {
                Logger.printException(() -> "Gallery backup: no file picker", e);
                Utils.showToastShort(str(e instanceof ActivityNotFoundException
                        ? "spookd_file_picker_unavailable" : "spookd_gallery_action_failed"));
                done();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode != REQUEST) return;
            List<Uri> picked = resultCode == Activity.RESULT_OK ? uris(data) : Collections.emptyList();
            Activity activity = getActivity();
            if (!picked.isEmpty() && activity != null) {
                start(activity.getApplicationContext(), getArguments().getInt(MODE), picked);
            }
            done();
        }

        private void done() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
            if (onReturned != null) onReturned.run();
        }
    }
}
