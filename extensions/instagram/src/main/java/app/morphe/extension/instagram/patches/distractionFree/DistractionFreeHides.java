/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.distractionFree;

import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class DistractionFreeHides {
    // Reels tab headers that stay hidden; views are only touched on the main thread.
    private static final Set<View> REELS_TAB_HEADERS = Collections.newSetFromMap(new WeakHashMap<>());

    private DistractionFreeHides() {
    }

    public static boolean hideRecentSearches() {
        try {
            return Pref.hideRecentSearches();
        } catch (Throwable t) {
            Logger.printException(() -> "hideRecentSearches failure", t);
            return false;
        }
    }

    /** Called when a profile builds its Muse banner; true skips the banner. */
    public static boolean hideMuseBanner() {
        try {
            return Pref.hideMuse();
        } catch (Throwable t) {
            Logger.printException(() -> "hideMuseBanner failure", t);
            return false;
        }
    }

    /** Takes Instagram's own "hide the comment bar" switch for the Reels viewer. */
    public static boolean hideReelsCommentBar(boolean hidden) {
        try {
            return hidden || Pref.hideReelsCommentBar();
        } catch (Throwable t) {
            Logger.printException(() -> "hideReelsCommentBar failure", t);
            return hidden;
        }
    }

    /** Whether the Reels comment bar's direct show call takes its hide branch instead. */
    public static boolean hideReelsCommentBar() {
        return hideReelsCommentBar(false);
    }

    /** Takes the visibility Instagram picked for the CTA container next to the comment bar. */
    public static int reelsCommentBarCtaVisibility(int visibility) {
        return commentBarVisibility(hideReelsCommentBar(), visibility);
    }

    static int commentBarVisibility(boolean hidden, int visibility) {
        return hidden ? View.GONE : visibility;
    }

    /** Takes whether the inbox shows its Instants stack. */
    public static boolean showInboxInstants(boolean show) {
        try {
            return show && !Pref.hideInboxInstants();
        } catch (Throwable t) {
            Logger.printException(() -> "showInboxInstants failure", t);
            return show;
        }
    }

    /** Called when a Reels header is bound to the viewer it belongs to. */
    public static void bindReelsHeader(View header, Object source) {
        try {
            if (!Pref.hideReelsTabHeader()) return;
            if (isReelsTab(source)) {
                REELS_TAB_HEADERS.add(header);
                header.setVisibility(View.GONE);
            } else {
                REELS_TAB_HEADERS.remove(header);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "bindReelsHeader failure", t);
        }
    }

    /** Every visibility change of a Reels header passes through here. */
    public static int reelsHeaderVisibility(View header, int visibility) {
        try {
            if (REELS_TAB_HEADERS.isEmpty() || !Pref.hideReelsTabHeader()) return visibility;
            return REELS_TAB_HEADERS.contains(header) ? View.GONE : visibility;
        } catch (Throwable t) {
            Logger.printException(() -> "reelsHeaderVisibility failure", t);
            return visibility;
        }
    }

    static boolean isReelsTab(Object source) {
        if (!(source instanceof Enum)) return false;
        String name = ((Enum<?>) source).name();
        return "CLIPS_TAB".equals(name) || "CLIPS_TAB_HOMECOMING".equals(name);
    }
}
