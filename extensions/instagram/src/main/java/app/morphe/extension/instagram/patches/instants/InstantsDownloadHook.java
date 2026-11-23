/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import android.content.Context;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.db.SpookdInstantsDb;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Records each view-once Instant as the app builds it, so it can be re-viewed and downloaded later
 * from {@link InstantsVaultActivity}.
 */
@SuppressWarnings("unused")
public class InstantsDownloadHook {

    /** The item constructor runs again whenever the app rebuilds an item. */
    // Ids stored or being stored this session; one deleted, cleared or purged from the vault is not captured again until restart.
    private static final Set<String> sCapturedIds = ConcurrentHashMap.newKeySet();
    private static final ExecutorService sDbExecutor = Executors.newSingleThreadExecutor();

    /** Called from the per-instant item's constructor with that instant's Media. */
    public static void noteInstantMedia(Object media) {
        try {
            if (media == null) return;
            if (!SharedPref.getBooleanPref(Settings.INSTANTS_DOWNLOAD)) return;

            captureMedia(media);
        } catch (Throwable t) {
            Logger.printException(() -> "instants capture: noteInstantMedia failed", t);
        }
    }

    private static void captureMedia(Object media) {
        try {
            MediaData md = new MediaData(media);

            String id;
            try {
                id = md.getMediaPkId();
            } catch (Throwable t) {
                return; // without a stable id it can't be deduped or referenced later
            }
            if (id == null || id.isEmpty() || sCapturedIds.contains(id)) return;

            boolean isVideo = safeBool(() -> md.isVideo());
            String imageUrl = safeStr(() -> md.getImageLink());
            String videoUrl = isVideo ? safeStr(() -> md.getVideoLink()) : null;
            String username = safeStr(() -> md.getUserData().getUsername());
            String userId = safeStr(() -> md.getUserData().getUserId());
            Long takenAtMillis = safeTakenAtMillis(md);
            String shortcode = safeStr(md::getShortcode);

            if (!sCapturedIds.add(id)) return;
            Runnable store = () -> {
                try {
                    Context ctx = Utils.getContext();
                    SpookdInstantsDb db = ctx == null ? null : SpookdInstantsDb.getInstance(ctx);
                    if (db != null && !db.has(id)) {
                        db.insertOrIgnore(id, username, userId, imageUrl, videoUrl, isVideo, takenAtMillis, shortcode);
                    }
                    // An id that did not reach the vault is dropped so the next rebuild tries again.
                    if (db == null || !db.has(id)) sCapturedIds.remove(id);
                } catch (Throwable t) {
                    sCapturedIds.remove(id);
                    Logger.printException(() -> "instants capture: store failed", t);
                }
            };
            try {
                sDbExecutor.execute(store);
            } catch (Throwable t) {
                sCapturedIds.remove(id);
                throw t;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "instants capture: captureMedia failed", t);
        }
    }

    // ---- best-effort helpers ----

    private static Long safeTakenAtMillis(MediaData media) {
        try {
            Long seconds = media.getTakenAtSeconds();
            return seconds == null || seconds <= 0 ? null : Math.multiplyExact(seconds, 1000L);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private interface StrCall { String get() throws Exception; }
    private interface BoolCall { boolean get() throws Exception; }

    private static String safeStr(StrCall c) {
        try { return c.get(); } catch (Throwable t) { return null; }
    }

    private static boolean safeBool(BoolCall c) {
        try { return c.get(); } catch (Throwable t) { return false; }
    }
}
