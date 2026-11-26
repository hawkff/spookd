package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class GalleryActionsTest {
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

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
    public void sharedCopiesGetTheNarrowestTypeThatCoversThemAll() {
        assertEquals("image/jpeg", GalleryActions.commonMimeType(Arrays.asList("image/jpeg", "image/jpeg")));
        assertEquals("image/*", GalleryActions.commonMimeType(Arrays.asList("image/jpeg", "image/png")));
        assertEquals("*/*", GalleryActions.commonMimeType(Arrays.asList("image/jpeg", "video/mp4")));
        assertEquals("video/*", GalleryActions.commonMimeType(Arrays.asList("video/*")));
    }

    @Test
    public void profileLinkOpensTheUsersPage() {
        assertEquals("https://www.instagram.com/alice.b_1/", GalleryActions.profileLink("alice.b_1"));
    }

    @Test
    public void clearingSharesKeepsAPickInstagramMayStillImportUntilItIsAnHourOld() throws IOException {
        File dir = temp.newFolder("piko_share");
        long now = 10 * GalleryActions.PICK_KEEP_MS;
        File share = folder(dir, "a1", now);
        File freshPick = folder(dir, GalleryActions.PICK_PREFIX + "b2", now - GalleryActions.PICK_KEEP_MS + 1000);
        File oldPick = folder(dir, GalleryActions.PICK_PREFIX + "c3", now - GalleryActions.PICK_KEEP_MS);

        GalleryActions.clearShared(dir, now);

        assertFalse(share.exists());
        assertTrue(new File(freshPick, "x.jpg").isFile());
        assertFalse(oldPick.exists());
    }

    private static File folder(File dir, String name, long modified) throws IOException {
        File folder = new File(dir, name);
        assertTrue(folder.mkdirs());
        assertTrue(new File(folder, "x.jpg").createNewFile());
        assertTrue(folder.setLastModified(modified));
        return folder;
    }
}
