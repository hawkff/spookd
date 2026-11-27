/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.audio;

import com.instagram.common.session.UserSession;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/** Leaves other apps' audio playing until the user turns Instagram's sound on. */
@SuppressWarnings("unused")
public final class KeepOtherAudio {
    private KeepOtherAudio() {
    }

    /**
     * Called on the main thread at the start of Instagram's foreground check that turns its sound on
     * while another app plays media. True skips the check, so videos stay muted.
     */
    public static boolean skipSoundOnForOtherAudio() {
        try {
            return Pref.keepOtherAudio();
        } catch (Throwable t) {
            Logger.printException(() -> "KeepOtherAudio: reading the switch failed", t);
            return false;
        }
    }

    /**
     * Called at the start of Instagram's audio focus request, on the thread of the player that asks.
     * False skips the request; true lets Instagram take audio focus as usual.
     */
    public static boolean allowFocusRequest(UserSession session) {
        try {
            return !Pref.keepOtherAudio() || (session != null && soundTurnedOn(session));
        } catch (Throwable t) {
            Logger.printException(() -> "KeepOtherAudio: checking the sound state failed", t);
            return true;
        }
    }

    /** Whether the sound of the account was turned on in this session; replaced at patch time. */
    private static boolean soundTurnedOn(UserSession session) {
        return true;
    }
}
