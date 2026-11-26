package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class HeldReceiptsTest {
    private static final String A = HeldReceipts.key("me", "a");
    private static final String B = HeldReceipts.key("me", "b");

    @Test
    public void keysNeedAnAccountAndAnItem() {
        assertNull(HeldReceipts.key(null, "a"));
        assertNull(HeldReceipts.key("me", ""));
        assertFalse(HeldReceipts.key("me", "a").equals(HeldReceipts.key("other", "a")));
    }

    @Test
    public void aDeferredReceiptIsNotTheOneOnScreen() throws Exception {
        HeldReceipts held = new HeldReceipts(4);
        held.keep(A, "receipt a");
        // B is on screen: no eye for it, and the eye cannot send A's receipt.
        assertFalse(held.has(B));
        assertFalse(held.send(B, receipt -> fail("sent " + receipt)));
        assertTrue(held.has(A));
    }

    @Test
    public void reopeningAnItemFindsItsReceiptByIdAndKeepsTheNewest() throws Exception {
        HeldReceipts held = new HeldReceipts(4);
        held.keep(HeldReceipts.key("me", "a"), "first viewer");
        held.keep(HeldReceipts.key("me", "a"), "second viewer");
        List<Object> sent = new ArrayList<>();
        assertTrue(held.send(HeldReceipts.key("me", "a"), sent::add));
        assertEquals(1, sent.size());
        assertEquals("second viewer", sent.get(0));
        assertFalse(held.has(A));
    }

    @Test
    public void aFailedSendKeepsTheReceiptForAnotherTry() throws Exception {
        HeldReceipts held = new HeldReceipts(4);
        held.keep(A, "receipt a");
        try {
            held.send(A, receipt -> { throw new IllegalStateException("queue missing"); });
            fail("the failure should reach the caller");
        } catch (IllegalStateException expected) {
            // The eye stays.
        }
        assertTrue(held.has(A));
        assertTrue(held.send(A, receipt -> { }));
        assertFalse(held.has(A));
    }

    @Test
    public void theOldestReceiptGoesFirstWhenFull() {
        HeldReceipts held = new HeldReceipts(1);
        held.keep(A, "receipt a");
        held.keep(B, "receipt b");
        assertFalse(held.has(A));
        assertTrue(held.has(B));
    }
}
