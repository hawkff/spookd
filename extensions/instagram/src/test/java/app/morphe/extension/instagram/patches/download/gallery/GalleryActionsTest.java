package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class GalleryActionsTest {
    @Test
    public void dmExportsAreNamedByTheirItemIdOrElseTheirRowId() {
        assertEquals("piko_abc.mp4", GalleryActions.dmFileName("abc", 7, null, "video"));
        assertEquals("piko_7.jpg", GalleryActions.dmFileName(null, 7, null, "photo"));
        assertEquals("piko_7.m4a", GalleryActions.dmFileName("", 7, "", "voice"));
    }

    @Test
    public void shareUriPointsAtTheProviderCachePath() {
        Uri uri = GalleryActions.shareUri("com.example.fileprovider", "f00", "a b#c.jpg");

        assertEquals("content", uri.getScheme());
        assertEquals("com.example.fileprovider", uri.getAuthority());
        assertEquals("/cache/piko_share/f00/a%20b%23c.jpg", uri.getEncodedPath());
        assertEquals(Arrays.asList("cache", "piko_share", "f00", "a b#c.jpg"), uri.getPathSegments());
    }

    @Test
    public void profileLinkOpensTheUsersPage() {
        assertEquals("https://www.instagram.com/alice.b_1/", GalleryActions.profileLink("alice.b_1"));
    }
}
