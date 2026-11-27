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
import app.morphe.extension.instagram.settings.ActivityHook;
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
    // Set when the picker has a transparent screen of its own, which closes once the picker is done.
    private static final String FINISH_HOST = "finish_host";
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

    private GalleryBackup() {
    }

    /** Main thread, from the settings row: the choice, then the screen lock for the chosen action. */
    public static void chooseLocked(Activity activity) {
        try {
            if (!Gallery.canOpen(activity)) {
                Utils.showToastLong(str("spookd_vault_needs_screen_lock"));
                return;
            }
            choose(activity, true);
        } catch (Exception e) {
            Logger.printException(() -> "Gallery backup: cannot show the choice", e);
            Utils.showToastShort(str("spookd_gallery_action_failed"));
        }
    }

    /**
     * Main thread. With {@code askLock} the screen lock is asked for each chosen action right before
     * its file picker, so a choice left open never outlasts a relock. Without it {@code activity} is
     * the unlocked gallery, which closes for the chosen action.
     */
    static void choose(Activity activity, boolean askLock) {
        // In the order of the modes, so the tapped row is the mode.
        String[] labels = {str("spookd_gallery_backup_export"), str("spookd_gallery_backup_import"),
                str("spookd_gallery_import_files")};
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(str("spookd_gallery_backup"))
                .setItems(labels, (d, which) -> {
                    if (!askLock) {
                        pickInHost(activity, which);
                        return;
                    }
                    try {
                        InboxLock.confirm(activity, () -> {
                            if (!activity.isFinishing() && !activity.isDestroyed()) pick(activity, which);
                        });
                    } catch (Exception e) {
                        Logger.printException(() -> "Gallery backup: cannot ask for the screen lock", e);
                        Utils.showToastShort(str("spookd_gallery_action_failed"));
                    }
                })
                .setNegativeButton(str("spookd_cancel"), null)
                .show();
    }

    /**
     * Opens the picker in its own transparent screen, then closes the gallery: the gallery is closed
     * on any stop, also by Inbox lock, so it cannot wait for the picker's result itself.
     */
    private static void pickInHost(Activity gallery, int mode) {
        Bundle args = new Bundle();
        args.putInt(MODE, mode);
        args.putBoolean(FINISH_HOST, true);
        if (ActivityHook.startGalleryPicker(gallery, args)) {
            gallery.finish();
        } else {
            Utils.showToastShort(str("spookd_gallery_action_failed"));
        }
    }

    private static void pick(Activity activity, int mode) {
        try {
            FragmentManager fragments = activity.getFragmentManager();
            if (activity.isFinishing() || fragments.findFragmentByTag(TAG) != null) return;
            Picker picker = new Picker();
            Bundle args = new Bundle();
            args.putInt(MODE, mode);
            picker.setArguments(args);
            fragments.beginTransaction().add(picker, TAG).commitAllowingStateLoss();
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

    /**
     * Main thread: hands the picked documents to the work thread. Without a notification that can
     * show, a dialog on {@code host} shows the progress instead; closing it leaves the work running.
     * Returns that dialog, or null; with {@code finishHost} the host then closes with the dialog.
     */
    private static AlertDialog start(Activity host, int mode, List<Uri> uris, boolean finishHost) {
        Context app = host.getApplicationContext();
        String title = str(mode == EXPORT ? "spookd_gallery_backup_running" : "spookd_gallery_importing");
        AlertDialog dialog = null;
        if (canNotify(app)) {
            Utils.showToastShort(title);
        } else {
            try {
                dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(host))
                        .setTitle(title).setMessage(title)
                        .setOnDismissListener(d -> {
                            if (finishHost) host.finish();
                        })
                        .show();
            } catch (Exception e) {
                Logger.printInfo(() -> "Gallery backup: no progress dialog", e);
                Utils.showToastShort(title);
            }
        }
        AlertDialog shown = dialog;
        WORK.execute(() -> {
            try {
                BiConsumer<Integer, Integer> progress = progress(app, title, shown);
                if (mode == EXPORT) export(app, uris.get(0), progress);
                else if (mode == IMPORT) importArchive(app, uris.get(0), progress);
                else if (mode == IMPORT_FILES) importFiles(app, uris, progress);
            } catch (Exception e) {
                Logger.printException(() -> "Gallery backup failed", e);
                Utils.showToastLong(str("spookd_gallery_action_failed"));
            } finally {
                cancelProgress(app, shown);
            }
        });
        return shown;
    }

    /** Main thread: whether the progress notification can show, its channel included; creates the channel. */
    private static boolean canNotify(Context app) {
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return false;
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, str("spookd_private_gallery"),
                    NotificationManager.IMPORTANCE_LOW));
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (Exception e) {
            Logger.printInfo(() -> "Gallery backup: cannot check notifications", e);
            return false;
        }
    }

    /** Work thread. A backup that fails part way is deleted, or the user is told to delete it. */
    private static void export(Context app, Uri uri, BiConsumer<Integer, Integer> progress) {
        ContentResolver resolver = app.getContentResolver();
        int[] written;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot write the backup");
            written = GalleryArchive.write(GalleryStore.get(), out, progress);
        } catch (Exception e) {
            Logger.printException(() -> "Gallery backup: cannot write", e);
            boolean deleted;
            try {
                deleted = DocumentsContract.deleteDocument(resolver, uri);
            } catch (Exception cleanup) {
                Logger.printInfo(() -> "Gallery backup: cannot delete the partial file", cleanup);
                deleted = false;
            }
            Utils.showToastLong(str(deleted ? "spookd_gallery_backup_failed" : "spookd_gallery_backup_failed_kept"));
            return;
        }
        Utils.showToastLong(written[1] == 0 ? str("spookd_gallery_backup_done", written[0])
                : str("spookd_gallery_backup_done_left_out", written[0], written[1]));
    }

    /** Work thread: merges a backup, skipping what the gallery already has. */
    private static void importArchive(Context app, Uri uri, BiConsumer<Integer, Integer> progress) {
        GalleryArchive.Result result = new GalleryArchive.Result();
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot read the backup");
            GalleryArchive.read(GalleryStore.get(), in, result, progress);
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
    private static void importFiles(Context app, List<Uri> uris, BiConsumer<Integer, Integer> progress) {
        ContentResolver resolver = app.getContentResolver();
        GalleryStore store = GalleryStore.get();
        GalleryArchive.Duplicates duplicates = new GalleryArchive.Duplicates(
                store.list(null, null, false, null, GalleryStore.SORT_NEWEST));
        long epoch = store.clearEpoch();
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
                                GalleryArchive.IMPORTED_FOLDER, name, null, GalleryArchive.MAX_FILE_BYTES);
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

    /** Work thread: shows {@code title} now, then (done, total) at most twice a second, in {@code dialog} if any. */
    private static BiConsumer<Integer, Integer> progress(Context app, String title, AlertDialog dialog) {
        showProgress(app, title, dialog, 0, 0);
        long[] last = {0};
        return (done, total) -> {
            long now = SystemClock.uptimeMillis();
            if (now - last[0] < 500 && done < total) return;
            last[0] = now;
            showProgress(app, title, dialog, done, total);
        };
    }

    private static void showProgress(Context app, String title, AlertDialog dialog, int done, int total) {
        String text = total > 0 ? str("spookd_gallery_backup_progress", done, total) : null;
        if (dialog != null) {
            Utils.runOnMainThread(() -> {
                if (dialog.isShowing()) dialog.setMessage(text == null ? title : title + "\n" + text);
            });
            return;
        }
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager == null) return;
            int icon = app.getApplicationInfo().icon;
            manager.notify(NOTIFICATION_ID, new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setProgress(total, done, total <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build());
        } catch (Exception e) {
            Logger.printInfo(() -> "Gallery backup: no progress notification", e);
        }
    }

    private static void cancelProgress(Context app, AlertDialog dialog) {
        if (dialog != null) {
            Utils.runOnMainThread(() -> {
                if (dialog.isShowing()) dialog.dismiss();
            });
            return;
        }
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
    }

    /** Headless: opens the file picker and hands what was picked to the work thread. */
    public static final class Picker extends Fragment {
        // Saved, so a host recreated while its progress dialog shows closes instead of picking again.
        private static final String CONSUMED = "consumed";
        private boolean consumed;
        private AlertDialog progressDialog;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState != null) {
                // The work, if this process still runs it, ends with its own toast.
                if (savedInstanceState.getBoolean(CONSUMED)) done(null);
                return;
            }
            try {
                startActivityForResult(intent(getArguments().getInt(MODE)), REQUEST);
            } catch (Exception e) {
                Logger.printException(() -> "Gallery backup: no file picker", e);
                Utils.showToastShort(str(e instanceof ActivityNotFoundException
                        ? "spookd_file_picker_unavailable" : "spookd_gallery_action_failed"));
                done(null);
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode != REQUEST) return;
            List<Uri> picked = resultCode == Activity.RESULT_OK ? uris(data) : Collections.emptyList();
            Activity activity = getActivity();
            boolean finishHost = getArguments().getBoolean(FINISH_HOST);
            consumed = true;
            done(picked.isEmpty() || activity == null ? null
                    : start(activity, getArguments().getInt(MODE), picked, finishHost));
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean(CONSUMED, consumed);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            // Without its listener, so a recreation does not finish the new host.
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.setOnDismissListener(null);
                progressDialog.dismiss();
            }
        }

        /**
         * Removes this picker, and closes its own transparent screen; with a progress {@code dialog}
         * there, it stays attached until the dialog closes the screen.
         */
        private void done(AlertDialog dialog) {
            Activity activity = getActivity();
            boolean finishHost = getArguments().getBoolean(FINISH_HOST);
            if (dialog != null && finishHost) {
                progressDialog = dialog;
                return;
            }
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
            if (activity != null && finishHost) activity.finish();
        }
    }
}
