package app.morphe.extension.instagram.patches.navigation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TabBarScrollDecisionTest {
    private final TabBarScrollDecision decision = new TabBarScrollDecision(24, 8);

    @Test
    public void hidesOnlyAfterScrollingDownPastTheThreshold() {
        assertFalse(decision.onScrolled(10, false));
        assertFalse(decision.onScrolled(14, false));
        assertTrue(decision.onScrolled(1, false));
    }

    @Test
    public void showsAfterScrollingUpPastTheThreshold() {
        decision.onScrolled(30, false);
        assertTrue(decision.onScrolled(-8, false));
        assertFalse(decision.onScrolled(-1, false));
    }

    @Test
    public void directionChangeRestartsTheCount() {
        decision.onScrolled(20, false);
        decision.onScrolled(-5, false);
        assertFalse(decision.onScrolled(20, false));
        assertTrue(decision.onScrolled(5, false));

        decision.onScrolled(-6, false);
        decision.onScrolled(4, false);
        assertTrue(decision.onScrolled(-6, false));
        assertFalse(decision.onScrolled(-3, false));
    }

    @Test
    public void showsAtTheTopEvenWhileScrollingDown() {
        decision.onScrolled(100, false);
        assertFalse(decision.onScrolled(50, true));
        assertFalse(decision.onScrolled(20, false));
    }

    @Test
    public void zeroStepKeepsTheCurrentState() {
        assertFalse(decision.onScrolled(0, false));
        decision.onScrolled(30, false);
        assertTrue(decision.onScrolled(0, false));
    }

    @Test
    public void resetShowsAndClearsTheCount() {
        decision.onScrolled(30, false);
        decision.reset();
        assertFalse(decision.onScrolled(20, false));
        assertTrue(decision.onScrolled(5, false));
    }
}
