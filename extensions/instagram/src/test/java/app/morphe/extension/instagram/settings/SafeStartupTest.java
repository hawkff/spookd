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

    private boolean safeMode() {
        return SafeStartup.readBoolean(startup, SafeStartup.SAFE_MODE_KEY);
    }

    @Test
    public void theStartAfterThreeUnfinishedStartsRunsInSafeMode() {
        SafeStartup.openAttempt(startup);
        SafeStartup.openAttempt(startup);
        SafeStartup.openAttempt(startup);
        assertFalse(safeMode());
        assertEquals(2, startup.getInt(SafeStartup.FAILED_STARTS_KEY, -1));

        SafeStartup.openAttempt(startup);
        assertTrue(safeMode());
        assertEquals(0, startup.getInt(SafeStartup.FAILED_STARTS_KEY, -1));
    }

    @Test
    public void launchesThatReachAScreenAndQuitAtOnceNeverTriggerSafeMode() {
        for (int launch = 0; launch < 3; launch++) {
            SafeStartup.openAttempt(startup);
            SafeStartup.closeAttempt(startup);
        }
        SafeStartup.openAttempt(startup);

        assertFalse(safeMode());
        assertEquals(0, startup.getInt(SafeStartup.FAILED_STARTS_KEY, -1));
    }

    @Test
    public void wrongTypedKeysReadAsDefaultsAndAreRewritten() {
        startup.edit()
                .putString(SafeStartup.SAFE_MODE_KEY, "yes")
                .putString(SafeStartup.DISABLE_ALL_KEY, "on")
                .putString(SafeStartup.ATTEMPT_OPEN_KEY, "1")
                .putBoolean(SafeStartup.FAILED_STARTS_KEY, true)
                .commit();

        assertFalse(safeMode());
        assertFalse(SafeStartup.readBoolean(startup, SafeStartup.DISABLE_ALL_KEY));
        SafeStartup.closeAttempt(startup);
        SafeStartup.openAttempt(startup);

        assertFalse(startup.getBoolean(SafeStartup.SAFE_MODE_KEY, true));
        assertFalse(startup.getBoolean(SafeStartup.DISABLE_ALL_KEY, true));
        assertTrue(startup.getBoolean(SafeStartup.ATTEMPT_OPEN_KEY, false));
        assertEquals(0, startup.getInt(SafeStartup.FAILED_STARTS_KEY, -1));
    }
}
