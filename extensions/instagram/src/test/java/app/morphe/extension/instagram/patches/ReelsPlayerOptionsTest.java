package app.morphe.extension.instagram.patches;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReelsPlayerOptionsTest {
    @Test
    public void tapRestartsOnlyTheEndedReelOnce() {
        Object ended = new Object();
        ReelsPlayerOptions.markEnded(ended);

        assertFalse(ReelsPlayerOptions.takeEndedReel(new Object()));
        assertFalse(ReelsPlayerOptions.takeEndedReel(null));
        assertTrue(ReelsPlayerOptions.takeEndedReel(ended));
        assertFalse(ReelsPlayerOptions.takeEndedReel(ended));
    }

    @Test
    public void playingTheEndedReelAgainClearsIt() {
        Object ended = new Object();
        ReelsPlayerOptions.markEnded(ended);

        ReelsPlayerOptions.clearIfStarted(new Object());
        ReelsPlayerOptions.clearIfStarted(null);
        assertTrue(ReelsPlayerOptions.takeEndedReel(ended));

        ReelsPlayerOptions.markEnded(ended);
        ReelsPlayerOptions.clearIfStarted(ended);
        assertFalse(ReelsPlayerOptions.takeEndedReel(ended));
    }

    @Test
    public void aLaterEndReplacesTheEarlierOne() {
        Object first = new Object();
        Object second = new Object();
        ReelsPlayerOptions.markEnded(first);
        ReelsPlayerOptions.markEnded(second);
        ReelsPlayerOptions.markEnded(null);

        assertFalse(ReelsPlayerOptions.takeEndedReel(first));
        assertTrue(ReelsPlayerOptions.takeEndedReel(second));
    }
}
