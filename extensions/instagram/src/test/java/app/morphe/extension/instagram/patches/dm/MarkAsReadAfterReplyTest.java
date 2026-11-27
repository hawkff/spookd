package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import app.morphe.extension.shared.MarkChatAsReadScope;

public class MarkAsReadAfterReplyTest {
    private static Set<String> on(String... actions) {
        return new HashSet<>(Arrays.asList(actions));
    }

    @Test
    public void sendCoversEveryMessageAndReplyOnlyQuotedOnes() {
        Set<String> reply = on(MarkAsReadAfterReply.REPLY);
        assertTrue(MarkAsReadAfterReply.triggers(reply, true, true, "send_text_message"));
        assertFalse(MarkAsReadAfterReply.triggers(reply, true, false, "send_text_message"));
        assertTrue(MarkAsReadAfterReply.triggers(on(MarkAsReadAfterReply.SEND), true, false, "send_text_message"));
    }

    @Test
    public void reactionsOnlyCountWhenChosen() {
        assertTrue(MarkAsReadAfterReply.triggers(on(MarkAsReadAfterReply.REACTION), false, false, "send_reaction"));
        assertFalse(MarkAsReadAfterReply.triggers(on(MarkAsReadAfterReply.SEND, MarkAsReadAfterReply.REPLY),
                false, false, "send_reaction"));
    }

    @Test
    public void otherMutationsNeverSend() {
        Set<String> all = on(MarkAsReadAfterReply.REPLY, MarkAsReadAfterReply.REACTION,
                MarkAsReadAfterReply.SEND, MarkAsReadAfterReply.TYPING);
        assertFalse(MarkAsReadAfterReply.triggers(all, false, false, "send_thread_seen_marker"));
        assertFalse(MarkAsReadAfterReply.triggers(all, false, false, null));
        assertFalse(MarkAsReadAfterReply.triggers(Collections.emptySet(), true, true, "send_reaction"));
    }

    @Test
    public void heldReceiptsAreKeptPerAccountAndTakenOnce() {
        PendingReadReceipts pending = new PendingReadReceipts();
        MarkChatAsReadScope.Operation receipt = () -> { };
        pending.remember("me", "chat", receipt);
        pending.remember(null, "chat", receipt);
        assertNull(pending.take("other", "chat"));
        assertNotNull(pending.take("me", "chat"));
        assertNull(pending.take("me", "chat"));
    }
}
