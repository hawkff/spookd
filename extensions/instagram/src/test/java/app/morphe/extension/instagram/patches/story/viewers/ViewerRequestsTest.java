package app.morphe.extension.instagram.patches.story.viewers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ViewerRequestsTest {

    @Test
    public void loadAllWaitsForARequestInstagramAlreadySentForTheSameCursor() {
        // Scrolling sent a request for page2; the paging class's own flag stays false then, so it is not read.
        ViewerRequests.Request scrolled = new ViewerRequests.Request(1_000, "page2");
        boolean busy = ViewerRequests.busy(scrolled, "page2", 1_500);
        assertTrue(busy);
        assertEquals(ViewerPaging.Step.WAIT, ViewerPaging.beforeRequest(true, busy));
    }

    @Test
    public void aRequestEndsWhenItsPageMovesTheCursorOrAfterTheLimit() {
        ViewerRequests.Request request = new ViewerRequests.Request(1_000, "page2");
        assertFalse(ViewerRequests.busy(request, "page3", 1_500));
        assertFalse(ViewerRequests.busy(request, null, 1_500));
        assertFalse(ViewerRequests.busy(request, "page2", 1_000 + ViewerRequests.LIMIT_MS));
        assertFalse(ViewerRequests.busy(null, "page2", 1_500));
        assertEquals(ViewerPaging.Step.REQUEST, ViewerPaging.beforeRequest(true,
                ViewerRequests.busy(request, "page3", 1_500)));
    }
}
