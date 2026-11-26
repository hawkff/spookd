package app.morphe.extension.instagram.patches.navigation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
