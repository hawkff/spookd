/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.dm;

import android.content.Context;
import android.content.SharedPreferences;
import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.entity.DirectItem;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.patches.dm.vault.MediaEligibility;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import com.instagram.common.session.UserSession;

@SuppressWarnings("unused")
public class EphemeralMediaPatch {
    private static final boolean MAKE_EPHEMERAL_MEDIA_PERMANENT;
    private static final String PERMA_KEY = "permanent";

    static {
        MAKE_EPHEMERAL_MEDIA_PERMANENT = Pref.makeEphemeralMediaPermanent();
    }

    public static boolean isMakeEphemeralMediaPermanent() { return MAKE_EPHEMERAL_MEDIA_PERMANENT; }

    public static boolean shouldSuppressEphemeralMediaReceipt(UserSession session, Object item) {
        if (!MAKE_EPHEMERAL_MEDIA_PERMANENT) return false;
        try {
            String key = localReadKey(session.getUserId(), item);
            SharedPreferences preferences = localReadPreferences();
            if (key != null && preferences != null) preferences.edit().putBoolean(key, true).apply();
        } catch (Exception ignored) { }
        try {
            // With the eye button the receipt is built and kept back right before it is queued (VisualMessageSeen).
            return !Pref.markVisualMessagesSeenManually();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean wasEphemeralMediaViewed(Object item, Object viewer) {
        if (!MAKE_EPHEMERAL_MEDIA_PERMANENT) return false;
        try {
            String key = localReadKey(new UserData(viewer).getUserId(), item);
            SharedPreferences preferences = localReadPreferences();
            return key != null && preferences != null && preferences.getBoolean(key, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String localReadKey(String viewerId, Object item) {
        if (viewerId == null || viewerId.isEmpty() || item == null) return null;
        String itemId = new DirectItem(item).getItemId();
        return itemId == null || itemId.isEmpty() ? null : viewerId + ":" + itemId;
    }

    private static SharedPreferences localReadPreferences() {
        Context context = PikoUtils.getContext();
        // Suppressing the receipt preserves the image, but refresh resets the server's seen count.
        return context == null ? null : context.getSharedPreferences("piko_ephemeral_media_viewed", Context.MODE_PRIVATE);
    }

    /** Runs on the thread of Instagram's visual media parser, at the end of each parse. */
    public static String makeEphemeralMediaPermanent(Long expireAt, String viewMode, Object parser, Object wrapper) {
        EphemeralMediaCaption.record(expireAt, viewMode, parser);
        String original = viewMode;
        try {
            if (expireAt == null || !MAKE_EPHEMERAL_MEDIA_PERMANENT) return viewMode;

            long currentTime = System.currentTimeMillis();
            long expireAtConv = Long.valueOf(expireAt) * 1000;

            // If current time is less than expire at time and the view mode isn't permanent
            // change the view mode to permanent.
            viewMode = currentTime <= expireAtConv && !viewMode.equals(PERMA_KEY) ? PERMA_KEY : viewMode;
            // Only rewritten items take the lock; the vault reads the mode for its marks and retention.
            if (wrapper != null && original != null && !original.equals(viewMode)) {
                MediaEligibility.recordOriginalMode(wrapper, original);
            }

        } catch (Exception e) {
            Logger.printException(() -> "error makeEphemeralMediaPermanent: " + e);
        }
        return viewMode;
    }

}
