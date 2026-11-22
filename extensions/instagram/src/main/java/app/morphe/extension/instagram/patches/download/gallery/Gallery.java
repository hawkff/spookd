package app.morphe.extension.instagram.patches.download.gallery;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import app.morphe.extension.crimera.legacydownloader.DownloadRequest;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.AccountSettings;
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
        return SharedPref.getStringPref(AccountSettings.OWNER_KEY, null);
    }

    /** Saves a download as a gallery item; its calls run on the download thread. */
    public static DownloadRequest.PrivateTarget target(GalleryItem item, String subFolder, String fileName,
                                                       boolean overwrite) {
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
            public void publish(File part) throws IOException {
                GalleryStore.get().publish(part, item, subFolder, fileName, overwrite);
                prune();
            }
        };
    }

    /** Applies the opt-in cleanup settings to downloads only; runs on the caller's background thread, never throws. */
    public static void prune() {
        try {
            GalleryStore.get().prune(Pref.galleryMaxAgeDays(), Pref.galleryMaxBytes(), System.currentTimeMillis());
        } catch (Exception e) {
            Logger.printException(() -> "Gallery cleanup failed", e);
        }
    }

    /** The kind of a download that carries no media type, from its file name's extension. */
    public static String kindOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.matches(".*\\.(mp4|mov|webm|mkv|3gp)")) return GalleryItem.VIDEO;
        if (name.matches(".*\\.(m4a|mp3|aac|ogg|opus|wav|amr)")) return GalleryItem.AUDIO;
        return GalleryItem.PHOTO;
    }
}
