/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.BooleanSetting;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

public final class ReelsPlayerOptions {
    private static final WeakReference<Object> NONE = new WeakReference<>(null);

    // The Reel item whose video reached its end and has not played since.
    private static volatile WeakReference<Object> endedItem = NONE;

    // Reels viewers opened from the home feed whose sound was already turned off once.
    private static final Set<Object> mutedFeedViewers = Collections.newSetFromMap(new WeakHashMap<>());

    // A holder, so the state helpers below load without the settings classes.
    private static final class Switches {
        static final Pref.Cached<Boolean> START_MUTED = cached(Settings.REELS_START_MUTED);
        static final Pref.Cached<Boolean> STOP_LOOPING = cached(Settings.REELS_STOP_LOOPING);
        static final Pref.Cached<Boolean> TAP_TO_PAUSE = cached(Settings.REELS_TAP_TO_PAUSE);
        static final Pref.Cached<Boolean> EXPANDED_FEED_MUTED = cached(Settings.EXPANDED_FEED_VIDEOS_MUTED);

        private static Pref.Cached<Boolean> cached(BooleanSetting setting) {
            return new Pref.Cached<>(() -> Boolean.TRUE.equals(SharedPref.getBooleanPref(setting)), setting.key);
        }

        static boolean isOn(Pref.Cached<Boolean> option) {
            try {
                return option.get();
            } catch (Throwable t) {
                Logger.printException(() -> "Failed to read a Reels player option", t);
                return false;
            }
        }
    }

    private ReelsPlayerOptions() {}

    /** Called on the main thread when the Reels controller would play sound. */
    public static boolean startMuted() {
        return Switches.isOn(Switches.START_MUTED);
    }

    /**
     * Called before a Reels viewer checks whether to play sound, with the viewer's source.
     * Returns whether to turn Instagram's sound off now: once per viewer opened from the home feed.
     */
    public static boolean muteExpandedFeedVideo(Object viewer, Object source) {
        try {
            return isHomeFeedSource(source) && Switches.isOn(Switches.EXPANDED_FEED_MUTED) && firstSoundCheck(viewer);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to mute an expanded feed video", t);
            return false;
        }
    }

    // Instagram's Reels viewer sources for videos opened from the home feed are named FEED_TIMELINE*.
    static boolean isHomeFeedSource(Object source) {
        return source instanceof Enum && ((Enum<?>) source).name().startsWith("FEED_TIMELINE");
    }

    static boolean firstSoundCheck(Object viewer) {
        if (viewer == null) return false;
        synchronized (mutedFeedViewers) {
            return mutedFeedViewers.add(viewer);
        }
    }

    /** Called when a Reels player is told to play, with Instagram's loop flag set. */
    public static boolean stopLooping() {
        return Switches.isOn(Switches.STOP_LOOPING);
    }

    /** Called on the main thread on a single tap on a Reel. Returns whether the tap pauses or resumes it. */
    public static boolean tapPausesReel(boolean nativeTapPauses) {
        return nativeTapPauses || Switches.isOn(Switches.TAP_TO_PAUSE);
    }

    /** Called when a Reels player reaches the end of its video. */
    public static void onReelEnded(Object player) {
        try {
            if (stopLooping()) markEnded(PlaybackSpeed.playingContent(player));
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to note the end of a Reel", t);
        }
    }

    /** Called when a Reels player starts playing. */
    static void onReelStarted(Object player) {
        try {
            if (endedItem.get() != null) clearIfStarted(PlaybackSpeed.playingContent(player));
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to note the start of a Reel", t);
        }
    }

    /**
     * Called on the main thread on a single tap on a Reel.
     * Returns whether the Reel had ended, in which case the caller restarts it.
     */
    public static boolean takeEndedReel(Object item) {
        return endedItem.get() != null && takeEndedReel(item, stopLooping());
    }

    /** Called on the main thread before Instagram's one-tap sound shortcut on a Reel. Returns whether to skip it. */
    public static boolean skipsTapSoundShortcut() {
        return tapPausesReel(false) || (endedItem.get() != null && hasEndedReel(stopLooping()));
    }

    // A Reel recorded while Stop looping was on is dropped once the switch is off.
    static boolean takeEndedReel(Object item, boolean stopLooping) {
        Object ended = hasEndedReel(stopLooping) ? endedItem.get() : null;
        if (ended == null || !ended.equals(item)) return false;
        endedItem = NONE;
        return true;
    }

    static boolean hasEndedReel(boolean stopLooping) {
        if (!stopLooping) endedItem = NONE;
        return endedItem.get() != null;
    }

    static void markEnded(Object item) {
        if (item != null) endedItem = new WeakReference<>(item);
    }

    static void clearIfStarted(Object item) {
        Object ended = endedItem.get();
        if (ended != null && ended.equals(item)) endedItem = NONE;
    }
}
