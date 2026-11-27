package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class AutoRefreshScheduleTest {

    @Test
    public void leavingTheAppRestartsTheThirtySecondWait() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        ShadowLooper main = shadowOf(Looper.getMainLooper());

        AutoRefresh.onResumed(activity);
        main.idleFor(Duration.ofSeconds(5));
        AutoRefresh.onPaused();
        main.idleFor(Duration.ofSeconds(24));
        AutoRefresh.onResumed(activity);
        // 30 s after the first resume, but only 1 s after coming back.
        main.idleFor(Duration.ofSeconds(1));
        assertFalse(AutoRefresh.attempted);

        main.idleFor(Duration.ofSeconds(29));
        assertTrue(AutoRefresh.attempted);
    }
}
