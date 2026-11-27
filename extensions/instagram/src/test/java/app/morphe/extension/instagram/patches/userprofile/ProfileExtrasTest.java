package app.morphe.extension.instagram.patches.userprofile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProfileExtrasTest {
    @Test
    public void gridKeepsInstagramVerdictWhileThePatchFlagIsOff() {
        assertTrue(ProfileExtras.keepInProfileGrid(true, new Object(), null));
        assertFalse(ProfileExtras.keepInProfileGrid(false, new Object(), null));
        assertTrue(ProfileExtras.keepInProfileGrid(true, null, null));
        assertTrue(ProfileExtras.profileGridCountRepair(true));
        assertFalse(ProfileExtras.profileGridCountRepair(false));
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
}
