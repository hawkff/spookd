package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeepSeenLocallyTest {
    @Test
    public void aKeptLocalReceiptIsDroppedOnceAtItsQueueCall() {
        assertFalse(KeepSeenLocally.blocksRequest(true, true));
        assertTrue(KeepSeenLocally.takeLocal());
        assertFalse(KeepSeenLocally.takeLocal());
    }

    @Test
    public void withoutKeepSeenLocallyAGhostReceiptEndsTheRequest() {
        assertTrue(KeepSeenLocally.blocksRequest(true, false));
        assertFalse(KeepSeenLocally.takeLocal());
    }

    @Test
    public void aReceiptGhostLetsThroughIsQueued() {
        KeepSeenLocally.blocksRequest(true, true);
        // The next request on the thread is not blocked, so an earlier unfinished one leaves nothing behind.
        assertFalse(KeepSeenLocally.blocksRequest(false, false));
        assertFalse(KeepSeenLocally.takeLocal());
    }
}
