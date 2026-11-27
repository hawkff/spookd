package app.morphe.extension.instagram.patches.userprofile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.shared.Utils;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class ProfileExtrasSwitchOnTest {
    /** Stands in for Instagram's post; the patch renames MediaData's "methodName" lookup to the post's video check. */
    public static final class Post {
        private final boolean video;

        Post(boolean video) {
            this.video = video;
        }

        public boolean methodName() {
            return video;
        }
    }

    private boolean patchApplied;

    @Before
    public void turnTheSwitchOn() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        patchApplied = SettingsStatus.hideProfileGridReels;
        SettingsStatus.hideProfileGridReels = true;
        SharedPref.setBooleanPref(Settings.HIDE_PROFILE_GRID_REELS.key, true);
        ProfileExtras.takeWaitingGrids();
    }

    @After
    public void restoreTheSwitch() {
        SharedPref.setBooleanPref(Settings.HIDE_PROFILE_GRID_REELS.key, false);
        SettingsStatus.hideProfileGridReels = patchApplied;
        ProfileExtras.takeWaitingGrids();
    }

    @Test
    public void videosLeaveTheGridWhilePhotosAndCarouselsStay() {
        assertFalse(ProfileExtras.keepInProfileGrid(true, new Post(true)));
        assertTrue(ProfileExtras.keepInProfileGrid(true, new Post(false)));
        // Instagram's own rejection still wins.
        assertFalse(ProfileExtras.keepInProfileGrid(false, new Post(false)));
        // Instagram's count repair and own-grid pin sync are off while hiding.
        assertFalse(ProfileExtras.offWhileGridReelsHidden(true));
    }

    @Test
    public void anUnscrollableGridAsksOncePerCursorAndNeverAfterAFailure() {
        Object grid = new Object();
        assertFalse(ProfileExtras.profileGridNeedsNextPage(grid, null, false, true, true, "a", "b", "c"));
        assertFalse(ProfileExtras.profileGridNeedsNextPage(grid, null, false, false, false, "a", "b", "c"));
        assertTrue(ProfileExtras.profileGridNeedsNextPage(grid, null, false, false, true, "a", "b", "c"));
        assertFalse(ProfileExtras.profileGridNeedsNextPage(grid, null, false, false, true, "a", "b", "c"));
        assertTrue(ProfileExtras.profileGridNeedsNextPage(grid, null, false, false, true, "a2", "b", "c"));
    }

    @Test
    public void aGridLoadingItsPageWaitsForTheRequestToSucceed() {
        Object grid = new Object();
        assertFalse(ProfileExtras.profileGridNeedsNextPage(grid, null, true, false, true, "a", "b", "c"));
        assertTrue(ProfileExtras.takeWaitingGrids().contains(grid));
        assertTrue(ProfileExtras.profileGridNeedsNextPage(grid, null, false, false, true, "a", "b", "c"));
    }

    @Test
    public void turningTheSwitchOffStopsBothFilteringAndContinuation() {
        SharedPref.setBooleanPref(Settings.HIDE_PROFILE_GRID_REELS.key, false);
        assertTrue(ProfileExtras.keepInProfileGrid(true, new Post(true)));
        assertFalse(ProfileExtras.profileGridNeedsNextPage(new Object(), null, false, false, true, "a", "b", "c"));
    }
}
