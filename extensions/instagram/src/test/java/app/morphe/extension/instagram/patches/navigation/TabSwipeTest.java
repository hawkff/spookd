package app.morphe.extension.instagram.patches.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class TabSwipeTest {
    @Test
    public void defaultKeepsInstagramsAnswer() {
        assertTrue(TabSwipe.decide("default", true));
        assertFalse(TabSwipe.decide("default", false));
        assertTrue(TabSwipe.decide(null, true));
    }

    @Test
    public void onWinsOverAStoredOptOut() {
        // Instagram answers false for a stored opt-out before it reads its flag.
        assertTrue(TabSwipe.decide("on", false));
    }

    @Test
    public void offWinsOverAStoredOptInAndTheNewerHomeLayout() {
        // Both make Instagram answer true whatever its flag says.
        assertFalse(TabSwipe.decide("off", true));
    }

    @After
    public void restore() {
        TabSwipe.frozen = null;
        TabSwipe.reader = () -> "default";
    }

    @Test
    public void aChangeWithoutTheRestartKeepsTheModeTheTabsWereBuiltWith() {
        TabSwipe.frozen = null;
        String[] saved = {"off"};
        TabSwipe.reader = () -> saved[0];
        assertFalse(TabSwipe.swipeTabs(true));
        // The user picks On and declines the restart.
        saved[0] = "on";
        assertFalse(TabSwipe.swipeTabs(true));
        assertFalse(TabSwipe.swipeTabs(false));
        assertEquals("off", TabSwipe.mode());
    }

    @Test
    public void aFailedFirstReadKeepsInstagramsAnswerForTheWholeProcess() {
        TabSwipe.frozen = null;
        boolean[] failed = {false};
        TabSwipe.reader = () -> {
            if (!failed[0]) {
                failed[0] = true;
                throw new IllegalStateException("settings not ready");
            }
            return "off";
        };
        assertTrue(TabSwipe.swipeTabs(true));
        assertEquals("default", TabSwipe.mode());
        assertTrue(TabSwipe.swipeTabs(true));
    }
}
