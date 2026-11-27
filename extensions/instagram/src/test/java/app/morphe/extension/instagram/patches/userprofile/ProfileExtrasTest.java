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
    public void nextPageIsOnlyAskedForWhenHidingAndIdleWithMorePages() {
        assertTrue(ProfileExtras.needsNextPage(true, false, true, false));
        assertFalse(ProfileExtras.needsNextPage(false, false, true, false));
        assertFalse(ProfileExtras.needsNextPage(true, true, true, false));
        assertFalse(ProfileExtras.needsNextPage(true, false, false, false));
        assertFalse(ProfileExtras.needsNextPage(true, false, true, true));
    }

    private static int pagesLoaded(int[] photosPerPage) {
        int loaded = 1;
        int shown = photosPerPage[0];
        while (ProfileExtras.needsNextPage(true, false, loaded < photosPerPage.length, shown > 0)) {
            shown += photosPerPage[loaded++];
        }
        return loaded;
    }
}
