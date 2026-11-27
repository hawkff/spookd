package app.morphe.extension.instagram.patches.userprofile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/** The grid decisions with the option on, passed in as the switch value. */
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

    @Before
    public void clearWaitingGrids() {
        ProfileExtras.takeWaitingGrids();
    }

    @Test
    public void videosLeaveTheGridWhilePhotosAndCarouselsStay() throws Exception {
        assertFalse(ProfileExtras.keepsPost(true, new Post(true)));
        assertTrue(ProfileExtras.keepsPost(true, new Post(false)));
        // With the option off every post stays.
        assertTrue(ProfileExtras.keepsPost(false, new Post(true)));
    }

    @Test
    public void anUnscrollableGridAsksOncePerCursorAndNeverAfterAFailure() {
        Object grid = new Object();
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, true, true, false, "a"));
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, false, false, false, "a"));
        assertTrue(ProfileExtras.onGridChanged(grid, true, false, false, true, false, "a"));
        assertFalse(ProfileExtras.onGridChanged(grid, true, false, false, true, false, "a"));
        assertTrue(ProfileExtras.onGridChanged(grid, true, false, false, true, false, "a2"));
    }

    @Test
    public void aGridLoadingItsPageWaitsForTheRequestToSucceed() {
        Object grid = new Object();
        assertFalse(ProfileExtras.onGridChanged(grid, true, true, false, true, false, "a"));
        assertTrue(ProfileExtras.takeWaitingGrids().contains(grid));
        assertTrue(ProfileExtras.onGridChanged(grid, true, false, false, true, false, "a"));
    }

    @Test
    public void withTheOptionOffNothingIsAskedFor() {
        assertFalse(ProfileExtras.onGridChanged(new Object(), false, false, false, true, false, "a"));
    }
}
