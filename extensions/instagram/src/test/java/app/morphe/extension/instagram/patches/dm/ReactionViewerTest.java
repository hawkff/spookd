package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ReactionViewerTest {

    @Test
    public void aResponseForAArrivingAfterTheSwitchToBRecordsNothing() {
        // A's history request was outstanding when B became current; the REST item has no session,
        // so neither the current session B nor the stored id names its account.
        assertNull(ReactionViewer.forComparison(true, null));
        assertNull(ReactionViewer.forComparison(true, "B"));
    }

    @Test
    public void realtimeItemsCompareForTheirOwnSessionOnly() {
        assertEquals("A", ReactionViewer.forComparison(false, "A"));
        assertNull(ReactionViewer.forComparison(false, null));
        assertNull(ReactionViewer.forComparison(false, " "));
    }
}
