package app.morphe.extension.instagram.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.KeyguardManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.morphe.extension.instagram.utils.Pref;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public final class SettingsLockTest {
    private final boolean[] switchOn = {false};

    @Before
    public void setUp() {
        SettingsLock.installed = false;
        SettingsLock.unlocked = false;
        SettingsLock.startedActivities = 0;
        SettingsLock.stored = () -> switchOn[0];
    }

    @After
    public void tearDown() {
        SettingsLock.stored = Pref::settingsLock;
    }

    @Test
    public void settingsOpenAtOnceWhileTheSwitchIsOff() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        secure(activity, true);
        boolean[] opened = {false};

        SettingsLock.open(activity, () -> opened[0] = true);

        assertTrue(opened[0]);
    }

    @Test
    public void withoutAScreenLockAnOnSwitchIsIgnoredAndCannotBeTurnedOn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        secure(activity, false);
        switchOn[0] = true;
        boolean[] opened = {false};

        SettingsLock.open(activity, () -> opened[0] = true);

        assertTrue(opened[0]);
        assertFalse(SettingsLock.allowTurningOn(activity));
    }

    @Test
    public void leavingTheAppClosesOpenSettingsAndLocksAgain() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        secure(activity, true);
        assertTrue(SettingsLock.allowTurningOn(activity));
        switchOn[0] = true;
        SettingsLock.install(activity);
        boolean[] opened = {false};

        // Turned on inside settings, so this session opens settings without asking.
        SettingsLock.open(activity, () -> opened[0] = true);
        assertTrue(opened[0]);
        assertFalse(activity.isFinishing());

        controller.pause().stop();

        assertTrue(activity.isFinishing());
        assertFalse(SettingsLock.unlocked);
    }

    @Test
    public void leavingTheAppWithTheSwitchOffKeepsSettingsOpen() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        secure(activity, true);
        SettingsLock.install(activity);
        SettingsLock.open(activity, () -> { });

        controller.pause().stop();

        assertFalse(activity.isFinishing());
    }

    private static void secure(Activity activity, boolean secure) {
        shadowOf(activity.getSystemService(KeyguardManager.class)).setIsDeviceSecure(secure);
    }
}
