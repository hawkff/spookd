/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.audio;

import android.media.AudioManager;

import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Leaves other apps' audio playing until the user turns Instagram's sound on. */
@SuppressWarnings("unused")
public final class KeepOtherAudio {
    // Set by turning the sound on since Instagram last came to the foreground; a sound state left on from before does not count.
    private static volatile boolean soundOnSinceForeground;
    // The Reels picture-in-picture screen whose focus request was skipped, asked again once the sound is turned on.
    private static volatile WeakReference<Object> skippedPip;

    private KeepOtherAudio() {
    }

    /**
     * Called on the main thread at the start of Instagram's foreground check that turns its sound on
     * while another app plays media. While the switch is on, the check is skipped (true), and if
     * another app is playing, Instagram's sound is set off so its videos start muted.
     */
    public static boolean onForeground(Object soundState, AudioManager audioManager) {
        try {
            if (!Pref.keepOtherAudio()) return false;
            foregrounded();
            if (audioManager != null && audioManager.isMusicActive()) setSound(soundState, false);
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "KeepOtherAudio: foreground check failed", t);
            return false;
        }
    }

    /** Called at the start of Instagram's sound setter, on the thread that mutes or unmutes. */
    public static void onSoundSet(boolean on) {
        soundOnSinceForeground = on;
        Object pip = takeSkippedPip(on);
        if (pip != null) Utils.runOnMainThread(() -> retryPipFocus(pip));
    }

    /**
     * Called on the main thread by Reels picture-in-picture right before it records and asks for
     * audio focus, both for its own request and for the player-managed one. False skips that request.
     */
    public static boolean allowPipFocusRequest(Object pip) {
        try {
            return pipAllowed(Pref.keepOtherAudio(), pip);
        } catch (Throwable t) {
            Logger.printException(() -> "KeepOtherAudio: reading the switch failed", t);
            return true;
        }
    }

    static boolean pipAllowed(boolean switchOn, Object pip) {
        if (allows(switchOn)) return true;
        skippedPip = new WeakReference<>(pip);
        return false;
    }

    /** The skipped picture-in-picture screen to ask again once the sound is on, taken once. */
    static Object takeSkippedPip(boolean soundOn) {
        WeakReference<Object> skipped = skippedPip;
        if (!soundOn || skipped == null) return null;
        skippedPip = null;
        return skipped.get();
    }

    private static void retryPipFocus(Object pip) {
        try {
            requestPipFocus(pip, null, null, null);
        } catch (Throwable t) {
            Logger.printException(() -> "KeepOtherAudio: asking for picture-in-picture focus failed", t);
        }
    }

    /** Called at the start of Instagram's timed automatic sound on; true skips it. */
    public static boolean skipTimedSoundOn() {
        try {
            return Pref.keepOtherAudio();
        } catch (Throwable t) {
            Logger.printException(() -> "KeepOtherAudio: reading the switch failed", t);
            return false;
        }
    }

    /**
     * Called at the start of Instagram's audio focus requests (its focus helper and the router that
     * picks the helper or the player's own focus handling), on the thread of the player that asks.
     * False skips the request; true lets Instagram take audio focus as usual.
     */
    public static boolean allowFocusRequest() {
        try {
            return allows(Pref.keepOtherAudio());
        } catch (Throwable t) {
            Logger.printException(() -> "KeepOtherAudio: reading the switch failed", t);
            return true;
        }
    }

    static void foregrounded() {
        soundOnSinceForeground = false;
    }

    static boolean allows(boolean switchOn) {
        return !switchOn || soundOnSinceForeground;
    }

    /**
     * Runs Reels picture-in-picture's own focus request again, through the player when it manages focus;
     * replaced at patch time. The unused parameters are registers for the replacement.
     */
    private static void requestPipFocus(Object pip, Object spare1, Object spare2, Object spare3) {
    }

    /** Instagram's sound setter for the account; replaced at patch time. */
    private static void setSound(Object soundState, boolean on) {
    }
}
