package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ReactionViewerTest {

    @Test
    public void restItemsAfterAnAccountSwitchUseTheCurrentSession() {
        // Account A was used before; B is current now and the REST item carries no session.
        String viewer = ReactionViewer.atEnqueue(null, "B", true);
        assertEquals("B", viewer);
        assertEquals("B", ReactionViewer.forComparison(true, viewer));
    }

    @Test
    public void unknownOwnershipSkipsTheComparison() {
        // Without a current session the stored id of an earlier account must not be used.
        String viewer = ReactionViewer.atEnqueue(null, null, true);
        assertNull(viewer);
        assertNull(ReactionViewer.forComparison(true, viewer));
        assertNull(ReactionViewer.forComparison(true, " "));
    }

    @Test
    public void realtimeItemsKeepTheirOwnSessionAndAreNotCompared() {
        assertEquals("A", ReactionViewer.atEnqueue("A", "B", false));
        assertNull(ReactionViewer.atEnqueue(null, "B", false));
        assertNull(ReactionViewer.forComparison(false, "A"));
    }
}
