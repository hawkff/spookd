/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import static app.morphe.extension.instagram.utils.IgStr.str;

import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.instagram.patches.confirm.ActionConfirm;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Holds back a captured Instant at Instagram's send: discards it while creation is off, and asks first
 * for a video. Instagram calls it inside its capture lock, on the thread that finished the capture.
 */
@SuppressWarnings("unused")
public final class InstantsSend {
    // The approved send while it runs again, which goes through unasked; main thread only.
    private static Object replaying;

    private InstantsSend() {
    }

    /** Start of Instagram's send of {@code send} from the Instants {@code camera}; false holds it back. */
    public static boolean allowSend(Object send, Object camera) {
        try {
            if (send == null || send == replaying) return true;
            if (Pref.disableInstantsCreation()) {
                discardNative(send, camera, true);
                Utils.showToastShort(str("spookd_disable_instants_creation_discarded"));
                return false;
            }
            if (!Pref.confirmInstantVideo() || !isVideoNative(send)) return true;

            AtomicBoolean settled = new AtomicBoolean();
            Runnable sendNow = () -> {
                if (settled.compareAndSet(false, true)) resend(send, camera);
            };
            Runnable discard = () -> {
                if (settled.compareAndSet(false, true)) discard(send, camera);
            };
            InstantsOrigin origin = InstantsOrigin.current();
            if (Utils.isCurrentlyOnMainThread()) {
                if (!origin.left()) return !ActionConfirm.askInstantSend(null, send, sendNow, discard);
                discard.run();
                return false;
            }
            Utils.runOnMainThread(() -> askLater(origin, null, send, sendNow, discard));
            return false;
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to check an Instant before sending", t);
            return true;
        }
    }

    /** Main thread: a camera left before the question shows discards; a question that cannot show sends. */
    static void askLater(InstantsOrigin origin, String emoji, Object token, Runnable send, Runnable drop) {
        boolean left;
        try {
            left = origin.left();
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to check the Instants screen", t);
            left = false;
        }
        if (left) drop.run();
        else if (!ActionConfirm.askInstantSend(emoji, token, send, drop)) send.run();
    }

    private static void resend(Object send, Object camera) {
        replaying = send;
        try {
            sendNative(send, camera);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to send a confirmed Instant", t);
        } finally {
            replaying = null;
        }
    }

    private static void discard(Object send, Object camera) {
        try {
            discardNative(send, camera, true);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to discard an Instant", t);
        }
    }

    // The patch replaces these bodies with reads of the send and calls into Instagram's Instants camera.
    public static boolean isVideoNative(Object send) {
        return false;
    }

    public static void sendNative(Object send, Object camera) {
    }

    /** Instagram's own discard; {@code dropPending} also removes the placeholder it may have shown. */
    public static void discardNative(Object send, Object camera, boolean dropPending) {
    }
}
