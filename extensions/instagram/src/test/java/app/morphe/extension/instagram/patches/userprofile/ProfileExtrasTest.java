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
        assertFalse(ProfileExtras.onGridChanged(grid, true, true, false, true, false));
        assertEquals(1, ProfileExtras.takeWaitingGrids().size());
        // The recheck after success sees loading cleared and asks once.
        assertTrue(ProfileExtras.onGridChanged(grid, true, false, false, true, false));
        // That request brings a photo that fills the grid: nothing else is asked or waits.
        assertFalse(ProfileExtras.onGridChanged(grid, true, true, false, true, true));
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
        // A finish with nothing waiting checks nothing.
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
    }

    @Test
    public void nextPageIsOnlyAskedForWhenHidingAndIdleWithMorePages() {
        Object grid = new Object();
        assertFalse(ProfileExtras.onGridChanged(grid, false, false, false, true, false));
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, false, false, false));
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, false, true, true));
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
    }

    @Test
    public void aFailedPageWithMorePagesIsNotRetriedOnItsOwn() {
        Object grid = new Object();
        ProfileExtras.takeWaitingGrids();

        // The failure refreshes the grid with the cursor kept: no request and nothing waits.
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, true, true, false));
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
        // A grid that waited is dropped when its request fails instead.
        assertFalse(ProfileExtras.onGridChanged(grid, true, true, false, true, false));
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, true, true, false));
        assertTrue(ProfileExtras.takeWaitingGrids().isEmpty());
    }

    private static int pagesLoaded(int[] photosPerPage) {
        Object grid = new Object();
        int loaded = 1;
        int shown = photosPerPage[0];
        while (ProfileExtras.onGridChanged(grid, true, false, false, loaded < photosPerPage.length, shown > 0)) {
            shown += photosPerPage[loaded++];
        }
        return loaded;
    }
}
