/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import java.util.Set;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.AccountSettings;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.MarkChatAsReadScope;

import com.instagram.common.session.UserSession;
import com.instagram.model.direct.DirectThreadKey;

/** Mark chats seen on: sends the read receipt DM ghost kept back for a chat when a chosen action happens in it. */
@SuppressWarnings("unused")
public final class MarkAsReadAfterReply {
    static final String REPLY = "reply";
    static final String REACTION = "reaction";
    static final String SEND = "send";
    static final String TYPING = "typing";
    private static final String REACTION_MUTATION = "send_reaction";
    private static final PendingReadReceipts PENDING = new PendingReadReceipts();

    private MarkAsReadAfterReply() {
    }

    /** Start of Instagram's DM seen request, on its thread: keeps a receipt DM ghost holds back for a later action. */
    public static void onSeenRequest(UserSession session, Object context,
                                     String threadId, String messageId, String senderId) {
        try {
            if (Pref.markSeenOn().isEmpty()) {
                PENDING.clear();
                return;
            }
            if (session == null || threadId == null || messageId == null || senderId == null
                    || !Pref.viewDmAnonymously(session.getUserId(), threadId)) return;

            // Keep the receipt Instagram tried to send, including its original message cursor.
            PENDING.remember(session.getUserId(), threadId,
                    () -> sendReceipt(session, context, threadId, messageId, senderId));
        } catch (Throwable t) {
            Logger.printException(() -> "MarkAsReadAfterReply.onSeenRequest failed", t);
        }
    }

    /** Called on Instagram's thread when a DM mutation was sent: a message, a reply or a reaction. */
    public static void onMutationSent(UserSession session, Object mutation) {
        try {
            Set<String> actions = Pref.markSeenOn();
            if (actions.isEmpty()) {
                PENDING.clear();
                return;
            }
            if (session == null || mutation == null) return;
            boolean message = isMessage(mutation);
            if (!triggers(actions, message, message && repliedTo(mutation) != null, typeOf(mutation))) return;
            Object chat = subjectOf(mutation);
            if (chat instanceof DirectThreadKey) send(session.getUserId(), ((DirectThreadKey) chat).A00);
        } catch (Throwable t) {
            Logger.printException(() -> "MarkAsReadAfterReply.onMutationSent failed", t);
        }
    }

    /** Called on the main thread when Instagram tells a chat that you started typing. */
    public static void onTyping(String threadId) {
        try {
            if (!Pref.markSeenOn().contains(TYPING)) return;
            // shortcut: the typing account is taken as the one whose settings are live, the foreground one.
            send(SharedPref.getStringPref(AccountSettings.OWNER_KEY, null), threadId);
        } catch (Throwable t) {
            Logger.printException(() -> "MarkAsReadAfterReply.onTyping failed", t);
        }
    }

    static boolean triggers(Set<String> actions, boolean message, boolean reply, String mutationType) {
        return (message && actions.contains(SEND))
                || (reply && actions.contains(REPLY))
                || (REACTION_MUTATION.equals(mutationType) && actions.contains(REACTION));
    }

    private static void send(String account, String threadId) throws Exception {
        if (threadId == null || !Pref.viewDmAnonymously(account, threadId)) return;
        MarkChatAsReadScope.Operation receipt = PENDING.take(account, threadId);
        // Consume the pending receipt before replaying it; later messages stay anonymous.
        if (receipt != null) MarkChatAsReadScope.run(receipt);
    }

    // The patch replaces these bodies with reads of Instagram's DM mutation.
    private static boolean isMessage(Object mutation) {
        return false;
    }

    private static Object repliedTo(Object mutation) {
        return null;
    }

    private static String typeOf(Object mutation) {
        return null;
    }

    private static Object subjectOf(Object mutation) {
        return null;
    }

    private static void sendReceipt(UserSession session, Object context,
                                    String threadId, String messageId, String senderId) {
    }
}
