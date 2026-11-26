package app.morphe.extension.instagram.patches.story.viewers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ViewerPagingTest {

    @Test
    public void aPageWithoutSearchableViewersStillMovesOn() {
        // Only spam or Facebook viewers arrived, so no row was added, but the cursor moved and more pages remain.
        assertEquals(ViewerPaging.Step.REQUEST, ViewerPaging.afterRebuild("page1", "page2", true, false));
        assertEquals(ViewerPaging.Step.DONE, ViewerPaging.afterRebuild("page1", null, false, false));
    }

    @Test
    public void anUnmovedCursorWaitsForInstagramOrStops() {
        assertEquals(ViewerPaging.Step.WAIT, ViewerPaging.afterRebuild("page1", "page1", true, true));
        assertEquals(ViewerPaging.Step.STOP, ViewerPaging.afterRebuild("page1", "page1", true, false));
        assertEquals(ViewerPaging.Step.DONE, ViewerPaging.afterRebuild(null, null, false, false));
    }

    @Test
    public void noRequestIsSentWhileInstagramsOwnIsOut() {
        assertEquals(ViewerPaging.Step.WAIT, ViewerPaging.beforeRequest(true, true));
        assertEquals(ViewerPaging.Step.REQUEST, ViewerPaging.beforeRequest(true, false));
        assertEquals(ViewerPaging.Step.DONE, ViewerPaging.beforeRequest(false, true));
    }

    @Test
    public void aTimeoutWaitsOnlyWhileInstagramIsFetchingAndWithinTheLimit() {
        assertEquals(ViewerPaging.Step.WAIT, ViewerPaging.afterTimeout(true, 20_000, 60_000));
        assertEquals(ViewerPaging.Step.STOP, ViewerPaging.afterTimeout(true, 60_000, 60_000));
        assertEquals(ViewerPaging.Step.STOP, ViewerPaging.afterTimeout(false, 20_000, 60_000));
    }
}
