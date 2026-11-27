package app.morphe.extension.instagram.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class NewIssueUriTest {

    @Test
    public void aBugReportFillsTheBugFormWithTheDeviceAndVersions() {
        Uri uri = ScreenBuilder.newIssueUri(true, "Google Pixel 8 & co", "15 (API 35)", "452.1.0.53.64", "1.0.0");
        assertEquals("https://github.com/hawkff/spookd/issues/new", uri.buildUpon().clearQuery().build().toString());
        assertEquals("bug_instagram.yml", uri.getQueryParameter("template"));
        assertEquals("452.1.0.53.64", uri.getQueryParameter("app-version"));
        assertEquals("1.0.0", uri.getQueryParameter("patches-version"));
        String problem = uri.getQueryParameter("problem");
        assertTrue(problem.contains("Device: Google Pixel 8 & co\nAndroid: 15 (API 35)"));
        // Encoded, so the device name cannot split the query.
        assertFalse(uri.getEncodedQuery().contains(" & "));
    }

    @Test
    public void aFeatureRequestFillsTheFeatureForm() {
        Uri uri = ScreenBuilder.newIssueUri(false, "Model", "14 (API 34)", "452", "1.0.0");
        assertEquals("feature_instagram.yml", uri.getQueryParameter("template"));
        assertTrue(uri.getQueryParameter("feature").endsWith("Instagram: 452\nPatches: 1.0.0"));
        assertNull(uri.getQueryParameter("problem"));
    }
}
