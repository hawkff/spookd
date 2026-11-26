package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ReactionViewerTest {

    @Test
    public void aResponseForAArrivingAfterTheSwitchToBRecordsNothing() {
        // A's history request was outstanding when B became current; the REST item has no session.
        String viewer = ReactionViewer.atEnqueue(null, "B", true);
        assertNull(ReactionViewer.forComparison(true, viewer));
        assertNull(ReactionViewer.forComparison(true, ReactionViewer.atEnqueue(null, null, true)));
    }

    @Test
    public void realtimeItemsCompareForTheirOwnSessionOnly() {
        String viewer = ReactionViewer.atEnqueue("A", "B", false);
        assertEquals("A", viewer);
        assertEquals("A", ReactionViewer.forComparison(false, viewer));
        assertNull(ReactionViewer.forComparison(false, ReactionViewer.atEnqueue(null, "B", false)));
        assertNull(ReactionViewer.forComparison(false, " "));
    }
}
