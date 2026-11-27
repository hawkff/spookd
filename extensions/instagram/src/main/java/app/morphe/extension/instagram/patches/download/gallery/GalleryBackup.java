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
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import java.io.IOException;
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
        String[] labels = {str("spookd_gallery_backup_export")};
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
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "spookd_gallery_" + day + ".zip");
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
        Utils.showToastShort(str("spookd_gallery_backup_running"));
        WORK.execute(() -> {
            try {
                export(app, uris.get(0));
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
