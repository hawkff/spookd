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
}
