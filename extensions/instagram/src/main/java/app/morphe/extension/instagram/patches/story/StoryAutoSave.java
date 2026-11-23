/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.crimera.legacydownloader.DownloadRequest;
import app.morphe.extension.crimera.legacydownloader.MediaDownloader;
import app.morphe.extension.crimera.legacydownloader.StorageUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.patches.download.gallery.GalleryStore;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import com.instagram.common.session.UserSession;

/** Auto-save stories: saves viewed story items of everyone or of the listed owners. */
public final class StoryAutoSave {
    static final String MODE_OFF = "off";
    static final String MODE_EVERYONE = "everyone";
    static final String MODE_LISTED = "listed";

    // Media ids taken in this process; the gallery's media id check covers earlier ones.
    private static final Set<String> handled = ConcurrentHashMap.newKeySet();
    private static MediaDownloader downloader;

    private StoryAutoSave() {
    }

    /** Whether a story of {@code ownerId}, watched by {@code viewerId}, is saved in this mode; own stories never are. */
    static boolean savesOwner(String mode, String ownerId, String viewerId, boolean listed) {
        if (ownerId == null || ownerId.isEmpty() || ownerId.equals(viewerId)) return false;
        if (MODE_EVERYONE.equals(mode)) return true;
        return MODE_LISTED.equals(mode) && listed;
    }

    /**
     * What auto-save ends every file name with; it survives the length limit whole, so distinct media
     * never share a name and a taken name is the same media.
     */
    static String nameSuffix(String mediaId) {
        return "_" + mediaId;
    }

    /** False when media {@code mediaId} is unknown or was already taken in this process. */
    static boolean firstView(String mediaId) {
        return mediaId != null && handled.add(mediaId);
    }

    /**
     * Instagram marking a story item viewed, on the main thread. Only in-memory checks run here;
     * the request is built and queued on a background thread.
     */
    public static void onStoryViewed(Object logger, Object item, Object reel) {
        try {
            String mode = SharedPref.getStringPref(Settings.STORY_AUTO_SAVE);
            if (MODE_OFF.equals(mode) || !Pref.enableDownload()) return;
            // Only user story reels: ads (ADS_REEL), highlights and suggested reels are never saved.
            if (!StorySeenBridge.isSupportedSource(reelTypeNative(reel))) return;
            UserSession session = (UserSession) sessionNative(logger);
            Object media = mediaNative(item);
            if (session == null || media == null) return;
            String ownerId = StoryUserList.ownerId(media, session);
            if (!savesOwner(mode, ownerId, session.getUserId(), StoryUserList.AUTO_SAVE.all().containsKey(ownerId))) {
                return;
            }
            String mediaId = new MediaData(media, session).getMediaPkId();
            if (!firstView(mediaId)) return;
            Utils.runOnBackgroundThread(() -> save(media, reel, session, mediaId));
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to auto-save a story", throwable);
        }
    }

    // Background thread. Skips without a history row what was saved before or cannot be saved now.
    private static void save(Object media, Object reel, UserSession session, String mediaId) {
        try {
            if (GalleryStore.get().hasMedia(mediaId)) return;
            // Names are unique per media, so skipping a taken name never drops or replaces other media.
            DownloadRequest request = buildOrRelease(mediaId, () -> DownloadUtils.storyRequest(media, reel, session,
                    nameSuffix(mediaId), DownloadRequest.ConflictPolicy.SKIP));
            if (request == null) return;
            request.quiet = true;
            request.tracker = releaseOnFailure(request.tracker, mediaId);
            // Without the folder grant the downloader would open the folder picker; a later view tries again.
            if (!Utils.isNetworkConnected()
                    || (request.privateTarget == null && !StorageUtils.checkStoragePermissions())) {
                handled.remove(mediaId);
                return;
            }
            downloader().enqueue(request);
        } catch (Exception | LinkageError e) {
            handled.remove(mediaId);
            Logger.printException(() -> "Failed to auto-save a story", e);
        }
    }

    /** The built request, or null with the media id released, so a later view tries again, when building throws. */
    static DownloadRequest buildOrRelease(String mediaId, Callable<DownloadRequest> build) {
        try {
            return build.call();
        } catch (Exception | LinkageError e) {
            handled.remove(mediaId);
            Logger.printException(() -> "Failed to build a story auto-save", e);
            return null;
        }
    }

    /**
     * {@code history} that also releases the media id when the download fails, so a later view tries again;
     * a skip because the name is taken keeps it. Runs on the download thread.
     */
    static DownloadRequest.Tracker releaseOnFailure(DownloadRequest.Tracker history, String mediaId) {
        return new DownloadRequest.Tracker() {
            @Override
            public void started() {
                if (history != null) history.started();
            }

            @Override
            public void ended(boolean saved, Uri document, Throwable error) {
                if (error != null) handled.remove(mediaId);
                if (history != null) history.ended(saved, document, error);
            }

            @Override
            public void alreadyDownloading() {
                if (history != null) history.alreadyDownloading();
            }
        };
    }

    // One downloader, so auto-saves download one at a time on its executor.
    private static synchronized MediaDownloader downloader() {
        if (downloader == null) downloader = new MediaDownloader(Utils.getContext());
        return downloader;
    }

    // Only the listed mode uses the list, so the menu stays short otherwise.
    public static boolean showMenuItem() {
        return SettingsStatus.storyAutoSave && Pref.enableDownload()
                && MODE_LISTED.equals(SharedPref.getStringPref(Settings.STORY_AUTO_SAVE));
    }

    /** The story menu item, on the main thread: lists the story owner, or unlists them when listed. */
    public static void toggleStoryOwner(UserSession session, Object media, DialogInterface.OnDismissListener onDismiss) {
        try {
            UserData owner = new MediaData(media, session).getUserData();
            String userId = owner.getUserId();
            String username = owner.getUsername();
            if (userId != null && username != null && !userId.equals(session.getUserId())) {
                boolean added = StoryUserList.AUTO_SAVE.add(userId, username);
                if (!added) StoryUserList.AUTO_SAVE.remove(userId);
                Utils.showToastShort(str(added ? "spookd_story_auto_save_added" : "spookd_story_auto_save_removed",
                        username));
            }
        } catch (Exception e) {
            Logger.printException(() -> "Failed to change the story auto-save list", e);
        } finally {
            if (onDismiss != null) onDismiss.onDismiss(null);
        }
    }

    /** The list screen in the Story settings. */
    public static void showList(Context context) {
        StoryUserList.AUTO_SAVE.showList(context);
    }

    // The patch replaces these bodies with reads from Instagram's story objects.
    public static Object sessionNative(Object logger) {
        return null;
    }

    public static Object mediaNative(Object item) {
        return null;
    }

    public static Object reelTypeNative(Object reel) {
        return null;
    }
}
