package app.morphe.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class SafeStartupTest {
    private final SharedPreferences startup = SafeStartup.prefs(RuntimeEnvironment.getApplication());

    @Test
    public void safeModeIsDueAfterThreeCrashesInARow() {
        SafeStartup.recordCrash(startup);
        SafeStartup.recordCrash(startup);
        assertFalse(SafeStartup.safeModeDue(startup));

        SafeStartup.recordCrash(startup);
        assertTrue(SafeStartup.safeModeDue(startup));
    }

    @Test
    public void aSuccessfulStartupStartsTheCountAgain() {
        SafeStartup.recordCrash(startup);
        SafeStartup.recordCrash(startup);
        SafeStartup.resetCrashes(startup);
        SafeStartup.recordCrash(startup);

        assertEquals(1, startup.getInt(SafeStartup.CRASHES_KEY, -1));
        assertFalse(SafeStartup.safeModeDue(startup));
    }
}
