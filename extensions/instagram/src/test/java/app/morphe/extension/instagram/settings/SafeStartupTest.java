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
        return startup.getBoolean(SafeStartup.SAFE_MODE_KEY, false);
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
    public void aFinishedStartResetsTheCount() {
        SafeStartup.openAttempt(startup);
        SafeStartup.openAttempt(startup);
        SafeStartup.closeAttempt(startup);
        SafeStartup.openAttempt(startup);

        assertEquals(0, startup.getInt(SafeStartup.FAILED_STARTS_KEY, -1));
        assertTrue(startup.getBoolean(SafeStartup.ATTEMPT_OPEN_KEY, false));
        assertFalse(safeMode());
    }
}
