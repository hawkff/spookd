/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.instagram.patches.confirm.ActionConfirm;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Asks before an emoji reaction to an Instant is sent. Instagram's send is a suspending call: while the
 * dialog is open it reports itself suspended, and its caller is resumed exactly once, by Instagram's own
 * request after Send, or here with false (not sent) when the dialog closes otherwise.
 */
@SuppressWarnings("unused")
public final class InstantsReactions {
    // The caller of an approved reaction while it is sent again, which goes through unasked; main thread only.
    private static Object replaying;

    private InstantsReactions() {
    }

    /**
     * Start of Instagram's reaction send, on its coroutine thread: null lets it run now, anything else is
     * what it returns instead.
     */
    public static Object holdReaction(Object api, String mediaId, String emoji, Object continuation) {
        try {
            // Instagram resumes its own call with null arguments.
            if (mediaId == null || emoji == null || continuation == null || continuation == replaying) return null;
            if (!Pref.confirmInstantReaction()) return null;
            Object suspended = suspendedNative();
            if (suspended == null) return null;

            AtomicBoolean settled = new AtomicBoolean();
            Runnable send = () -> {
                if (settled.compareAndSet(false, true)) send(api, mediaId, emoji, continuation, suspended);
            };
            Runnable cancel = () -> {
                if (settled.compareAndSet(false, true)) resume(continuation, Boolean.FALSE);
            };
            if (Utils.isCurrentlyOnMainThread()) {
                return ActionConfirm.askInstantSend(emoji, continuation, send, cancel) ? suspended : null;
            }
            Utils.runOnMainThread(() -> {
                if (!ActionConfirm.askInstantSend(emoji, continuation, send, cancel)) send.run();
            });
            return suspended;
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to hold an Instant reaction", t);
            return null;
        }
    }

    private static void send(Object api, String mediaId, String emoji, Object continuation, Object suspended) {
        Object result;
        replaying = continuation;
        try {
            result = reactNative(api, mediaId, emoji, continuation);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to send a confirmed Instant reaction", t);
            result = Boolean.FALSE;
        } finally {
            replaying = null;
        }
        // A call that suspended resumes its caller itself once the request finishes.
        if (result != suspended) resume(continuation, result);
    }

    private static void resume(Object continuation, Object result) {
        try {
            resumeNative(continuation, result);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to resume an Instant reaction", t);
        }
    }

    // The patch replaces these bodies with Instagram's suspended marker, its reaction send and its coroutine resume.
    public static Object suspendedNative() {
        return null;
    }

    public static Object reactNative(Object api, String mediaId, String emoji, Object continuation) {
        return null;
    }

    public static void resumeNative(Object continuation, Object result) {
    }
}
