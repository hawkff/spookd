package app.morphe.extension.instagram.patches.userprofile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProfileExtrasTest {
    @Test
    public void gridKeepsInstagramVerdictWhileThePatchFlagIsOff() {
        assertTrue(ProfileExtras.keepInProfileGrid(true, new Object()));
        assertFalse(ProfileExtras.keepInProfileGrid(false, new Object()));
        assertTrue(ProfileExtras.keepInProfileGrid(true, null));
        assertTrue(ProfileExtras.offWhileGridReelsHidden(true));
        assertFalse(ProfileExtras.offWhileGridReelsHidden(false));
    }

    @Test
    public void reelsSortKeepsOrRemovesMostViewedAndOtherwiseFollowsInstagram() {
        assertTrue(ProfileExtras.mostViewedSort("keep", false));
        assertFalse(ProfileExtras.mostViewedSort("remove", true));
        assertTrue(ProfileExtras.mostViewedSort("default", true));
        assertFalse(ProfileExtras.mostViewedSort("default", false));
        assertTrue(ProfileExtras.mostViewedSort(null, true));
        // The patch flag is off in tests, so the setting reads as default.
        assertFalse(ProfileExtras.profileReelsMostViewed(false));
    }

    @Test
    public void highlightReorderFollowsInstagramWhileThePatchFlagIsOff() {
        assertTrue(ProfileExtras.reorderHighlights(true));
        assertFalse(ProfileExtras.reorderHighlights(false));
    }

    @Test
    public void hiddenPagesKeepLoadingUntilAPhotoFillsTheGridOrThePagesEnd() {
        // Photos left on each page after reels are hidden; one photo fills the screen here.
        assertEquals(3, pagesLoaded(new int[]{0, 0, 1, 1}));
        assertEquals(3, pagesLoaded(new int[]{0, 0, 0}));
        assertEquals(1, pagesLoaded(new int[]{2, 0}));
    }

    @Test
    public void anEmptiedPageDeliveredWhileLoadingAsksForExactlyOneMorePageOnceItSucceeds() {
        Object grid = new Object();
        ProfileExtras.takeWaitingGrids();

        // The page lands while its request still counts as loading.
        assertFalse(ProfileExtras.onGridChanged(grid, true, true, false, true, false, "c"));
        assertEquals(1, ProfileExtras.takeWaitingGrids().size());
        // The recheck after success sees loading cleared and asks once.
        assertTrue(ProfileExtras.onGridChanged(grid, true, false, false, true, false, "c"));
        // That request brings a photo that fills the grid: nothing else is asked or waits.
        assertFalse(ProfileExtras.onGridChanged(grid, true, true, false, true, true, "c"));
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
        // A finish with nothing waiting checks nothing.
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
    }

    @Test
    public void nextPageIsOnlyAskedForWhenHidingAndIdleWithMorePages() {
        Object grid = new Object();
        assertFalse(ProfileExtras.onGridChanged(grid, false, false, false, true, false, "c"));
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, false, false, false, "c"));
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, false, true, true, "c"));
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
    }

    @Test
    public void aFailedPageWithMorePagesIsNotRetriedOnItsOwn() {
        Object grid = new Object();
        ProfileExtras.takeWaitingGrids();

        // The failure refreshes the grid with the cursor kept: no request and nothing waits.
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, true, true, false, "c"));
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
        // A grid that waited is dropped when its request fails instead.
        assertFalse(ProfileExtras.onGridChanged(grid, true, true, false, true, false, "c"));
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, true, true, false, "c"));
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
    }

    @Test
    public void aPartlyArrivedPageThatKeepsFailingIsAskedForOnlyOncePerCursor() {
        Object grid = new Object();
        ProfileExtras.takeWaitingGrids();
        int requests = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            // Videos of the page arrive without moving the cursor, then the error ends the request as a success.
            if (ProfileExtras.onGridChanged(grid, true, true, false, true, false, "c1")) requests++;
            for (Object waiting : ProfileExtras.takeWaitingGrids()) {
                if (ProfileExtras.onGridChanged(waiting, true, false, false, true, false, "c1")) requests++;
            }
            if (ProfileExtras.onGridChanged(grid, true, false, false, true, false, "c1")) requests++;
        }
        assertEquals(1, requests);
        // A new cursor may be asked for again.
        assertTrue(ProfileExtras.onGridChanged(grid, true, false, false, true, false, "c2"));
    }

    private static int pagesLoaded(int[] photosPerPage) {
        Object grid = new Object();
        int loaded = 1;
        int shown = photosPerPage[0];
        while (ProfileExtras.onGridChanged(grid, true, false, false, loaded < photosPerPage.length, shown > 0, "c" + loaded)) {
            shown += photosPerPage[loaded++];
        }
        return loaded;
    }
}
