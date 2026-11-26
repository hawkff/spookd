package app.morphe.extension.instagram.patches.download.gallery;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.function.LongConsumer;

import app.morphe.extension.crimera.legacydownloader.DownloadRequest;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.AccountSettings;
import app.morphe.extension.instagram.settings.SafeStartup;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Whether downloads go to the private gallery, and the download target that saves them there. */
public final class Gallery {
    private Gallery() {
    }

    /** Whether the gallery screen can open: Android 11 or newer with a secure screen lock. */
    public static boolean canOpen(Context context) {
        if (Build.VERSION.SDK_INT < 30) return false;
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    /**
     * Whether a download made now goes to the gallery: only while the settings row that opens it
     * is shown and the gallery can open, so a private download is always reachable. Never throws.
     */
    public static boolean enabled() {
        try {
            return SettingsStatus.downloadSection() && !Pref.saveDownloadsToPhoneStorage()
                    && canOpen(Utils.getContext());
        } catch (Exception e) {
            Logger.printException(() -> "Gallery.enabled failed", e);
            return false;
        }
    }

    /** The account that is saving now, or null before the first account switch was seen. */
    public static String accountId() {
        // The shared preference reader rejects a null default.
        String id = SharedPref.getStringPref(AccountSettings.OWNER_KEY, "");
        return id.isEmpty() ? null : id;
    }

    /** Saves a download as a gallery item, telling {@code published} its id; its calls run on the download thread. */
    public static DownloadRequest.PrivateTarget target(GalleryItem item, String subFolder, String fileName,
                                                       DownloadRequest.ConflictPolicy policy,
                                                       LongConsumer published) {
        GalleryStore.Conflict conflict = policy == DownloadRequest.ConflictPolicy.SKIP ? GalleryStore.Conflict.SKIP
                : policy == DownloadRequest.ConflictPolicy.OVERWRITE ? GalleryStore.Conflict.OVERWRITE
                : GalleryStore.Conflict.RENAME;
        return new DownloadRequest.PrivateTarget() {
            @Override
            public File partFile() {
                return GalleryStore.get().partFile();
            }

            @Override
            public boolean exists() {
                return GalleryStore.get().exists(subFolder, fileName);
            }

            @Override
            public boolean publish(File part) throws IOException {
                long id = GalleryStore.get().publish(part, item, subFolder, fileName, conflict);
                if (id == -1) return false;
                published.accept(id);
                prune();
                return true;
            }
        };
    }

    /** Applies the opt-in cleanup settings to downloads only; runs on the caller's background thread, never throws. */
    public static void prune() {
        try {
            if (SafeStartup.featuresOff()) return;
            GalleryStore.get().prune(Pref.galleryMaxAgeDays(), Pref.galleryMaxBytes(), System.currentTimeMillis());
        } catch (Exception e) {
            Logger.printException(() -> "Gallery cleanup failed", e);
        }
    }
}
