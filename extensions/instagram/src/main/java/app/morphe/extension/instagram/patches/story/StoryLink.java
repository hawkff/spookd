/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.DialogInterface;

import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.Links;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import com.instagram.common.session.UserSession;

/** The Copy story link entry of the story menu, shown even where Instagram hides Share. */
public final class StoryLink {
    private static final String HIGHLIGHT_REEL_PREFIX = "highlight:";

    private StoryLink() {
    }

    static boolean showMenuItem() {
        return Pref.copyStoryLink();
    }

    /** The story menu item, on the main thread. The menu left resuming the story to this action. */
    static void copy(UserSession session, Object media, Object reel, DialogInterface.OnDismissListener onDismiss) {
        try {
            String reelId = reel == null ? null : reelIdNative(reel);
            String url;
            if (isHighlight(reelId)) {
                url = link(reelId, null, null);
            } else {
                MediaData data = new MediaData(media, session);
                url = link(null, data.getUserData().getUsername(), data.getMediaPkId());
            }
            if (url == null) {
                Utils.showToastShort(str("spookd_story_link_unavailable"));
            } else {
                Utils.setClipboard(Links.processSharedUrl(url));
                Utils.showToastShort(str("spookd_story_link_copied"));
            }
        } catch (Exception e) {
            Logger.printException(() -> "Failed to copy the story link", e);
            Utils.showToastShort(str("spookd_story_link_unavailable"));
        } finally {
            if (onDismiss != null) onDismiss.onDismiss(null);
        }
    }

    private static boolean isHighlight(String reelId) {
        return reelId != null && reelId.length() > HIGHLIGHT_REEL_PREFIX.length()
                && reelId.startsWith(HIGHLIGHT_REEL_PREFIX);
    }

    /** The highlight's link for a highlight reel id, else the item's link; null when a part is missing. */
    static String link(String reelId, String username, String mediaId) {
        if (isHighlight(reelId)) {
            return String.format(Constants.INSTAGRAM_SHARE_LINK, "stories/highlights",
                    reelId.substring(HIGHLIGHT_REEL_PREFIX.length()));
        }
        if (username == null || username.isEmpty() || mediaId == null) return null;
        // A media id can be "<pk>_<owner id>"; the link takes the pk.
        int split = mediaId.indexOf('_');
        String pk = split < 0 ? mediaId : mediaId.substring(0, split);
        if (pk.isEmpty()) return null;
        return String.format(Constants.INSTAGRAM_SHARE_LINK, "stories", username) + pk + "/";
    }

    // The patch replaces this body with a read of the reel's id; null leaves highlights on the item link.
    public static String reelIdNative(Object reel) {
        return null;
    }
}
