/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.view.View;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Long-pressing Send on a feed post copies the post link; a tap still opens the share sheet. */
@SuppressWarnings("unused")
public final class HoldSendLink {
    private HoldSendLink() {
    }

    /** Called on the main thread on every bind, after Instagram set the Send button's listeners. */
    public static void bindShareButton(View button, Object postState) {
        try {
            if (button != null && postState != null && Pref.holdSendCopyLink()) {
                button.setOnLongClickListener(v -> copyLink(postState));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Could not bind hold Send to copy link", t);
        }
    }

    private static boolean copyLink(Object postState) {
        try {
            Object media = mediaOf(postState);
            if (media == null) return false;
            Object viewState = viewStateOf(postState);
            int index = viewState == null ? 0 : currentIndexOf(viewState);
            Utils.setClipboard(Links.processSharedUrl(Links.generatePostLink(media, index)));
            Utils.showToastShort(str("spookd_post_link_copied"));
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "Could not copy the post link", t);
            return false;
        }
    }

    // The patch replaces this body with a read of the post's media.
    public static Object mediaOf(Object postState) {
        return null;
    }

    // The patch replaces this body with a read of the post's carousel view state.
    public static Object viewStateOf(Object postState) {
        return null;
    }

    // The patch replaces this body with a read of the carousel item shown.
    public static int currentIndexOf(Object viewState) {
        return 0;
    }
}
