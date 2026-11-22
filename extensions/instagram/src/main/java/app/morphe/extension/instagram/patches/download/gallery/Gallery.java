package app.morphe.extension.instagram.patches.download.gallery;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

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

    /** Applies the opt-in cleanup settings to downloads only; runs on the caller's background thread, never throws. */
    public static void prune() {
        try {
            GalleryStore.get().prune(Pref.galleryMaxAgeDays(), Pref.galleryMaxBytes(), System.currentTimeMillis());
        } catch (Exception e) {
            Logger.printException(() -> "Gallery cleanup failed", e);
        }
    }
}
