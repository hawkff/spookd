package app.morphe.extension.instagram.patches.pip;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoPipTest {
    @Test
    public void pipIsForcedOnlyWithTheSwitchOnAndroid15OrNewer() {
        assertTrue(VideoPip.forced(false, true, VideoPip.MIN_SDK));
        assertTrue(VideoPip.forced(false, true, VideoPip.MIN_SDK + 1));
        assertFalse(VideoPip.forced(false, false, VideoPip.MIN_SDK));
        assertFalse(VideoPip.forced(false, true, VideoPip.MIN_SDK - 1));
    }

    @Test
    public void theReelsMenuKeepsInstagramsOwnAnswer() {
        assertFalse(VideoPip.forced(true, true, VideoPip.MIN_SDK));
    }
}
