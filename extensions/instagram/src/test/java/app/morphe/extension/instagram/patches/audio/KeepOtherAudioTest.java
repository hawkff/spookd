package app.morphe.extension.instagram.patches.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeepOtherAudioTest {

    @Test
    public void soundLeftOnFromBeforeTheForegroundNeedsANewUnmute() {
        KeepOtherAudio.onSoundSet(true);
        assertTrue(KeepOtherAudio.allows(true));

        // Background, music starts, back to the foreground: the old sound-on no longer counts.
        KeepOtherAudio.foregrounded();
        assertFalse(KeepOtherAudio.allows(true));

        KeepOtherAudio.onSoundSet(true);
        assertTrue(KeepOtherAudio.allows(true));
        KeepOtherAudio.onSoundSet(false);
        assertFalse(KeepOtherAudio.allows(true));
    }

    @Test
    public void withTheSwitchOffInstagramAlwaysAsks() {
        KeepOtherAudio.foregrounded();
        assertTrue(KeepOtherAudio.allows(false));
    }
}
