/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.audio;

import android.media.AudioManager;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/** Leaves other apps' audio playing until the user turns Instagram's sound on. */
@SuppressWarnings("unused")
public final class KeepOtherAudio {
    // Set by turning the sound on since Instagram last came to the foreground; a sound state left on from before does not count.
    private static volatile boolean soundOnSinceForeground;

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

    /** Instagram's sound setter for the account; replaced at patch time. */
    private static void setSound(Object soundState, boolean on) {
    }
}
