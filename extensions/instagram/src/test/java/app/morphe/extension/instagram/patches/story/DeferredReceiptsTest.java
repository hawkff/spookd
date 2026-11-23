package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class DeferredReceiptsTest {
    private final AtomicBoolean full = new AtomicBoolean();
    private final List<Integer> sent = new ArrayList<>();
    private final DeferredReceipts<Integer> receipts = new DeferredReceipts<>(3, full::get, receipt -> {
        if (full.get()) return false;
        sent.add(receipt);
        return true;
    });

    @Test
    public void receiptsWaitWhileTheRegistryIsFullAndGoOutInOrderOnceItFrees() {
        full.set(true);
        assertTrue(receipts.submit(1));
        assertTrue(receipts.submit(2));
        assertTrue(receipts.retry());
        assertTrue(sent.isEmpty());

        full.set(false);
        assertTrue(receipts.submit(3));
        assertFalse(receipts.retry());
        assertEquals(Arrays.asList(1, 2, 3), sent);
    }

    @Test
    public void theOldestWaitingReceiptIsDroppedPastTheBound() {
        full.set(true);
        for (int receipt = 1; receipt <= 5; receipt++) receipts.submit(receipt);

        full.set(false);
        receipts.retry();
        assertEquals(Arrays.asList(3, 4, 5), sent);
    }

    @Test
    public void aReceiptRefusedForAnotherReasonIsNotKept() {
        DeferredReceipts<Integer> refusing = new DeferredReceipts<>(3, () -> false, receipt -> false);

        assertFalse(refusing.submit(1));
        assertFalse(refusing.retry());
    }

    @Test
    public void theRegistryIsFullAtItsLimitAndFreesWhenARequestIsUnregistered() {
        RequestRegistry registry = new RequestRegistry(() -> 0L, 30_000L, 2, key -> { });
        Object first = new Object();

        assertTrue(registry.register(first, StorySeenKey.from("viewer", "1"), null));
        assertFalse(registry.isFull());
        assertTrue(registry.register(new Object(), StorySeenKey.from("viewer", "2"), null));
        assertTrue(registry.isFull());
        assertFalse(registry.register(new Object(), StorySeenKey.from("viewer", "3"), null));

        registry.unregister(first);
        assertFalse(registry.isFull());
    }
}
