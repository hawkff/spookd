package app.morphe.extension.instagram.patches.instants;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class InstantsOriginTest {
    @Test
    public void onlyAScreenStillShownAndNeverPausedIsAsked() {
        assertFalse(InstantsOrigin.wasLeft(false, false, true));
        assertTrue(InstantsOrigin.wasLeft(true, false, true));
        assertTrue(InstantsOrigin.wasLeft(false, true, true));
        assertTrue(InstantsOrigin.wasLeft(false, false, false));
    }
}
