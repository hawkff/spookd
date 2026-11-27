package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import com.instagram.common.session.UserSession;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class AutomaticScanDeleteTest {
    private static final String A = "1001";

    @After
    public void tearDown() {
        ProfileAnalyzer.onCurrentSession(null);
    }

    @Test
    public void aDeleteAfterTheEligibilityReadKeepsTheAutomaticScanFromStarting() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        UserSession session = new UserSession(A);
        ProfileAnalyzer.onCurrentSession(session);
        ProfileAnalyzer.recordCounts(A, 1, 1);
        int generation = ProfileAnalyzer.dataGeneration();

        // The user deletes the data while the automatic start waits.
        assertTrue(ProfileAnalyzer.deleteAllData(activity));
        assertNotEquals(generation, ProfileAnalyzer.dataGeneration());

        ProfileAnalyzer.startAutomaticScan(activity, A, ProfileAnalyzer.countsFor(A), generation);
        assertFalse(ProfileAnalyzer.isScanning());
    }
}
