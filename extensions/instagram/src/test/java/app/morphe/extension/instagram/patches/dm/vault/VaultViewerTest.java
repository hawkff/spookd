package app.morphe.extension.instagram.patches.dm.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.instagram.patches.dm.InboxLock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class VaultViewerTest {
    private Activity activity;

    @After
    public void tearDown() throws Exception {
        setPrompting(false);
        if (activity != null) {
            pending().remove(activity);
            confirmed().remove(activity);
        }
    }

    @Test
    public void sampleSizeKeepsTheScreenCoveredAndTheBitmapBounded() {
        assertEquals(1, VaultViewer.sampleSize(1080, 1920, 1080, 2400));
        assertEquals(1, VaultViewer.sampleSize(4000, 3000, 1080, 2400));
        assertEquals(2, VaultViewer.sampleSize(2160, 4800, 1080, 2400));
        assertEquals(8, VaultViewer.sampleSize(8640, 19200, 1080, 2400));
        // A long strip: the screen rule never samples it, the pixel cap does.
        int sample = VaultViewer.sampleSize(100_000, 1000, 1080, 2400);
        assertTrue((long) (100_000 / sample) * (1000 / sample) <= 16_000_000);
        assertEquals(4, sample);
        assertEquals(1, VaultViewer.sampleSize(1, 1, 0, 0));
    }

    @Test
    public void closeWithNoPromptUpEndsTheRequestAndTheConfirmation() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).create().get();
        pending().put(activity, new Object());
        confirmed().add(activity);

        for (int i = 0; i < 2; i++) {
            VaultViewer.close(activity);
            assertFalse(pending().containsKey(activity));
            assertFalse(confirmed().contains(activity));
        }
    }

    @Test
    public void closeWhileAPromptIsUpKeepsTheRequestAndEndsTheConfirmation() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).create().get();
        Object request = new Object();
        pending().put(activity, request);
        confirmed().add(activity);
        setPrompting(true);

        VaultViewer.close(activity);
        assertSame(request, pending().get(activity));
        assertFalse(confirmed().contains(activity));

        setPrompting(false);
        VaultViewer.close(activity);
        assertFalse(pending().containsKey(activity));
    }

    @SuppressWarnings("unchecked")
    private static Map<Activity, Object> pending() throws Exception {
        return (Map<Activity, Object>) field(VaultViewer.class, "pending").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<Activity> confirmed() throws Exception {
        return (Set<Activity>) field(VaultViewer.class, "confirmed").get(null);
    }

    private static void setPrompting(boolean prompting) throws Exception {
        field(InboxLock.class, "prompting").setBoolean(null, prompting);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
