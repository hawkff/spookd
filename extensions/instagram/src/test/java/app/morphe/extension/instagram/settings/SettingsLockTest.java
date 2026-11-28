package app.morphe.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.morphe.extension.instagram.patches.dm.InboxLock;
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
    public void tearDown() throws Exception {
        SettingsLock.stored = Pref::settingsLock;
        setPrompting(false);
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

    @Test
    @Config(sdk = 29)
    public void android10WithAScreenLockCanTurnTheLockOn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        secure(activity, true);
        switchOn[0] = true;

        assertTrue(SettingsLock.allowTurningOn(activity));
        assertTrue(SettingsLock.enabled(activity));
    }

    @Test
    public void aLaunchDuringAConfirmedPromptOpensOnceItEnds() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        secure(activity, true);
        switchOn[0] = true;
        setPrompting(true);
        boolean[] opened = {false};

        SettingsLock.open(activity, () -> opened[0] = true);
        assertFalse(opened[0]);
        assertFalse(activity.isFinishing());

        endPrompt(true);

        assertTrue(opened[0]);
        assertTrue(SettingsLock.unlocked);
    }

    @Test
    public void aLaunchDuringACancelledPromptRunsAgainInsteadOfBeingDropped() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        secure(activity, true);
        switchOn[0] = true;
        setPrompting(true);
        boolean[] opened = {false};

        SettingsLock.open(activity, () -> opened[0] = true);
        // The screen lock is gone by the rerun, so the gate opens settings instead of asking.
        secure(activity, false);
        endPrompt(false);

        assertTrue(opened[0]);
        assertFalse(SettingsLock.unlocked);
    }

    @Test
    public void resetWithTheLockOnRunsNothingUntilTheScreenLockConfirmsIt() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        secure(activity, true);
        switchOn[0] = true;
        // Another prompt is up, so this reset's request is not confirmed.
        setPrompting(true);
        boolean[] reset = {false};

        SettingsLock.confirmReset(activity, () -> reset[0] = true);
        endPrompt(false);

        assertFalse(reset[0]);
    }

    @Test
    public void resetRunsAtOnceWhileTheLockIsOffOrAlreadyUnlocked() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        secure(activity, true);
        int[] resets = {0};

        SettingsLock.confirmReset(activity, () -> resets[0]++);
        switchOn[0] = true;
        SettingsLock.unlocked = true;
        SettingsLock.confirmReset(activity, () -> resets[0]++);

        assertEquals(2, resets[0]);
    }

    private static void setPrompting(boolean prompting) throws Exception {
        Field field = InboxLock.class.getDeclaredField("prompting");
        field.setAccessible(true);
        field.setBoolean(null, prompting);
    }

    private static void endPrompt(boolean confirmed) throws Exception {
        Method method = InboxLock.class.getDeclaredMethod("promptEnded", boolean.class);
        method.setAccessible(true);
        method.invoke(null, confirmed);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void secure(Activity activity, boolean secure) {
        shadowOf(activity.getSystemService(KeyguardManager.class)).setIsDeviceSecure(secure);
    }
}
