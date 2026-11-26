/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.content.DialogInterface;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SafeStartup;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class StoryPlayback {
    // A holder, so the pure helper below loads without the settings classes.
    private static final class Switches {
        static final Pref.Cached<Boolean> MUTE_STORIES = new Pref.Cached<>(
                () -> Pref.on(Settings.MUTE_STORIES), Settings.MUTE_STORIES.key);
    }

    // Music helpers created by the story viewer's audio manager.
    private static final Set<Object> storyAudio = Collections.newSetFromMap(new WeakHashMap<>());

    private StoryPlayback() {}

    /** Whether the story menu lists Speed and seek. */
    public static boolean showMenuItem() {
        return SettingsStatus.storyPlayback && !SafeStartup.featuresOff();
    }

    /**
     * Called by a story player when it decides whether to play sound, with whether the viewer was asked for
     * sound (volume buttons or Instagram's sound button). Returns the decision to use.
     */
    public static boolean storySound(boolean nativeSound, boolean askedForSound) {
        if (!nativeSound || askedForSound) return nativeSound;
        try {
            return playsSound(true, false, Switches.MUTE_STORIES.get());
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to read Mute stories", t);
            return true;
        }
    }

    /** Called when the story viewer's audio manager creates its music helper (sponsored story music). */
    public static void markStoryAudio(Object helper) {
        if (helper == null) return;
        synchronized (storyAudio) {
            storyAudio.add(helper);
        }
    }

    /**
     * Called by a music helper when it decides whether to play sound. The helper class also plays feed and
     * other sponsored audio, so only helpers the story viewer created follow Mute stories.
     */
    public static boolean storyAudioSound(boolean nativeSound, boolean askedForSound, Object helper) {
        if (!nativeSound || askedForSound || !isStoryAudio(helper)) return nativeSound;
        return storySound(true, false);
    }

    static boolean isStoryAudio(Object helper) {
        synchronized (storyAudio) {
            return storyAudio.contains(helper);
        }
    }

    static boolean playsSound(boolean nativeSound, boolean askedForSound, boolean muteStories) {
        return nativeSound && (askedForSound || !muteStories);
    }

    /**
     * Called on the main thread for every story menu row that is tapped. Returns whether the row was
     * Speed and seek; the story stays paused until the panel closes, then the menu's dismiss listener runs.
     */
    public static boolean openPanel(CharSequence row, Context context, Object fragment, Object media,
                                    DialogInterface.OnDismissListener onDismiss) {
        if (row == null || !str("spookd_speed_and_seek").contentEquals(row)) return false;
        boolean shown = false;
        try {
            Object player = media != null && new MediaData(media).isVideo() ? player(fragment) : null;
            if (player != null) {
                PlaybackSpeedPanel.showForStory(context, player, onDismiss);
                shown = true;
            } else {
                Utils.showToastShort(str("spookd_story_speed_panel_photo"));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to open the story speed panel", t);
        }
        if (!shown && onDismiss != null) onDismiss.onDismiss(null);
        return true;
    }

    // The bodies below are replaced at patch time with calls into Instagram's story player.
    static Object player(Object fragment) {
        return null;
    }

    static int positionMs(Object player) {
        return 0;
    }

    static int durationMs(Object player) {
        return 0;
    }

    static void seekTo(Object player, int positionMs) {
    }

    static void setSpeed(Object player, float speed) {
    }
}
