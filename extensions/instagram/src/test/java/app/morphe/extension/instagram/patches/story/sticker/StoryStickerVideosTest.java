package app.morphe.extension.instagram.patches.story.sticker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.SafeStartup;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class StoryStickerVideosTest {
    private final boolean savedStatus = SettingsStatus.storyStickerVideos;
    private boolean switched;
    private Object savedFeaturesOff;
    private Object savedContext;

    @After
    public void restore() throws Exception {
        SettingsStatus.storyStickerVideos = savedStatus;
        if (!switched) return;
        SharedPref.setBooleanPref(Settings.STORY_STICKER_VIDEOS.key, false);
        field(SafeStartup.class, "featuresOff").set(null, savedFeaturesOff);
        field(Class.forName("app.morphe.extension.shared.Utils"), "context").set(null, savedContext);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** The patch applied and the switch on, with features not turned off. */
    private void switchOn() throws Exception {
        Field featuresOff = field(SafeStartup.class, "featuresOff");
        Field context = field(Class.forName("app.morphe.extension.shared.Utils"), "context");
        savedFeaturesOff = featuresOff.get(null);
        savedContext = context.get(null);
        switched = true;
        featuresOff.set(null, Boolean.FALSE);
        Context app = RuntimeEnvironment.getApplication();
        context.set(null, app);
        SettingsStatus.storyStickerVideos = true;
        SharedPref.setBooleanPref(Settings.STORY_STICKER_VIDEOS.key, true);
        assertTrue(Pref.storyStickerVideos());
    }

    @Test
    public void onlyAGridThatIsNotTheLayoutPickerIsLetIn() {
        assertTrue(StoryStickerVideos.admits(false, true));
        assertFalse(StoryStickerVideos.admits(true, true));
        assertFalse(StoryStickerVideos.admits(false, false));
        assertFalse(StoryStickerVideos.admits(true, false));
    }

    @Test
    public void theLayoutPickerKeepsItsOwnFlag() {
        // Without the patch's settings the switch reads off, so nothing is let in.
        assertTrue(StoryStickerVideos.layoutCheck(true));
        assertFalse(StoryStickerVideos.layoutFlag(false));
        assertTrue(StoryStickerVideos.layoutFlag(true));
        assertFalse(StoryStickerVideos.layoutCheck(false));
        assertFalse(StoryStickerVideos.layoutFlag(false));
    }

    @Test
    public void withTheSwitchOnOnlyTheFlagReadAfterAnAdmittedTrayIsForced() throws Exception {
        switchOn();
        // The tray is not the layout picker, so it is let in and its next flag read is forced.
        assertTrue(StoryStickerVideos.layoutCheck(false));
        assertTrue(StoryStickerVideos.layoutFlag(false));
        assertFalse(StoryStickerVideos.layoutFlag(false));
        // The layout picker is not let in by the switch, so its flag stays Instagram's.
        assertTrue(StoryStickerVideos.layoutCheck(true));
        assertFalse(StoryStickerVideos.layoutFlag(false));
        assertTrue(StoryStickerVideos.layoutCheck(true));
        assertTrue(StoryStickerVideos.layoutFlag(true));
    }
}
