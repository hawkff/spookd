/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import android.content.Context;
import android.content.DialogInterface;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.patches.StoryPlayback;
import app.morphe.extension.crimera.ObjectBrowser;
import app.morphe.extension.instagram.entity.MediaData;

import com.instagram.common.session.UserSession;

public class StoryButton {
    private static boolean VIEW_STORY_MENTIONS;
    private static boolean ENABLE_DOWNLOAD;
    private static boolean DEBUG;
    private static WeakReference<Object> menu = new WeakReference<>(null);
    private static String mentionsLabel;

    static{
        VIEW_STORY_MENTIONS = Pref.viewStoryMentions() && SettingsStatus.viewStoryMentions;
        ENABLE_DOWNLOAD = Pref.enableDownload() && SettingsStatus.downloadMedia;
        DEBUG = Pref.spookdDebug();
    }

    /** Start of building a story menu, on the main thread. */
    public static void captureMenu(Object storyMenu) {
        menu = new WeakReference<>(storyMenu);
    }

    public static ArrayList addButtons(ArrayList buttonList){
        if(DEBUG){
            buttonList.add(str("spookd_debug"));
        }
        if(VIEW_STORY_MENTIONS){
            mentionsLabel = mentionsLabel(menu.get());
            buttonList.add(mentionsLabel);
        }
        menu = new WeakReference<>(null);
        if(ENABLE_DOWNLOAD){
            buttonList.add(str("spookd_download_options"));
        }
        if(Pref.downloadWithExternalDownloader()){
            buttonList.add(str("spookd_download_with_external_downloader"));
        }
        if(StoryGhostList.showMenuItem()){
            buttonList.add(str("spookd_story_ghost_add"));
        }
        if(StoryAutoSave.showMenuItem()){
            buttonList.add(str("spookd_story_auto_save_toggle"));
        }
        if(StoryPlayback.showMenuItem()){
            buttonList.add(str("spookd_speed_and_seek"));
        }

        return buttonList;
    }

    public static boolean storyDialogAction(CharSequence buttonText, Context ctx, UserSession session,
                                           Object mediaObject, Object reel,
                                           DialogInterface.OnDismissListener onDismiss) {
        if (buttonText.equals(str("spookd_view_story_mentions")) || buttonText.toString().equals(mentionsLabel)) {
            ViewStoryMentionsPatch.viewMentions(ctx, mediaObject, onDismiss);
            return true;
        } else if (buttonText.equals(str("spookd_download_options")) || buttonText.equals(str("spookd_category_download_media"))) {
            DownloadUtils.showStoryDownloadOptions(ctx, session, mediaObject, reel, onDismiss);
            return true;
        } else if (buttonText.equals(str("spookd_story_ghost_add"))) {
            StoryGhostList.addStoryOwner(session, mediaObject, onDismiss);
            return true;
        } else if (buttonText.equals(str("spookd_story_auto_save_toggle"))) {
            StoryAutoSave.toggleStoryOwner(session, mediaObject, onDismiss);
            return true;
        }
        return false;
    }

    // Counts the people mentioned in the story's loaded data; the badge is skipped when there are none.
    private static String mentionsLabel(Object storyMenu) {
        try {
            Object media = storyMenu == null ? null : menuMediaNative(storyMenu);
            HashSet<UserData> mentions = media == null ? null : new MediaData(media).getMentionSet();
            if (mentions != null && !mentions.isEmpty()) {
                List<String> userIds = new ArrayList<>();
                for (UserData mention : mentions) userIds.add(mention.getUserId());
                int people = StoryMentionCount.people(userIds);
                if (people > 0) return str("spookd_view_story_mentions_count", people);
            }
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to count story mentions", throwable);
        }
        return str("spookd_view_story_mentions");
    }

    // The patch replaces this body with a read of the menu's story media.
    public static Object menuMediaNative(Object storyMenu) {
        return null;
    }

    public static boolean storyButtonAction(CharSequence buttonText, Context ctx, Object mediaObject){
        try {
            if (buttonText.equals(str("spookd_debug"))) {
                ObjectBrowser.browseObject(ctx, new MediaData(mediaObject));
                return true;
            } else if (buttonText.equals(str("spookd_download_with_external_downloader"))) {
                DownloadUtils.externalDownloader(mediaObject,0);

                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed storyButtonAction", ex);
        }
        return false;
    }
}
