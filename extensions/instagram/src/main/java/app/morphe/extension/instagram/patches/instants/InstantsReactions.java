/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

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

            InstantsHold hold = new InstantsHold(() -> send(api, mediaId, emoji, continuation, suspended),
                    () -> resume(continuation, Boolean.FALSE));
            InstantsOrigin origin = InstantsOrigin.current();
            if (Utils.isCurrentlyOnMainThread()) {
                // A viewer already left gets Instagram's not-sent result right away.
                if (origin.left()) return Boolean.FALSE;
                return ActionConfirm.askInstantSend(emoji, continuation, hold::send, hold::drop) ? suspended : null;
            }
            Utils.runOnMainThread(() -> hold.askLater(origin::left,
                    asked -> ActionConfirm.askInstantSend(emoji, continuation, asked::send, asked::drop)));
            return suspended;
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to hold an Instant reaction", t);
            return null;
        }
    }

    private static void send(Object api, String mediaId, String emoji, Object continuation, Object suspended) {
        replaying = continuation;
        try {
            sendThrough(() -> reactNative(api, mediaId, emoji, continuation), suspended,
                    result -> resume(continuation, result));
        } finally {
            replaying = null;
        }
    }

    /** Runs the reaction send; unless it suspended, which resumes its caller later, its result resumes the caller once. */
    static void sendThrough(Callable<Object> react, Object suspended, Consumer<Object> resume) {
        Object result;
        try {
            result = react.call();
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to send a confirmed Instant reaction", t);
            result = Boolean.FALSE;
        }
        if (result != suspended) resume.accept(result);
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
