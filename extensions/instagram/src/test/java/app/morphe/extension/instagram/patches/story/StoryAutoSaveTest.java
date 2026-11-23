package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Arrays;

import app.morphe.extension.crimera.legacydownloader.DownloadRequest;
import app.morphe.extension.instagram.patches.download.DownloadFileNameFormatter;

public class StoryAutoSaveTest {
    // Names of Instagram's reel types.
    private enum ReelType { USER_REEL, PRIVATE_STORY_REEL, ADS_REEL, HIGHLIGHT_REEL, SUGGESTED_USER_REEL }

    @Test
    public void onlyUserStoryReelsAreSaved() {
        assertTrue(StorySeenBridge.isSupportedSource(ReelType.USER_REEL));
        assertTrue(StorySeenBridge.isSupportedSource(ReelType.PRIVATE_STORY_REEL));
        assertFalse(StorySeenBridge.isSupportedSource(ReelType.ADS_REEL));
        assertFalse(StorySeenBridge.isSupportedSource(ReelType.HIGHLIGHT_REEL));
        assertFalse(StorySeenBridge.isSupportedSource(ReelType.SUGGESTED_USER_REEL));
        assertFalse(StorySeenBridge.isSupportedSource(null));
    }

    @Test
    public void offSavesNoOne() {
        assertFalse(StoryAutoSave.savesOwner(StoryAutoSave.MODE_OFF, "1", "9", true));
        assertFalse(StoryAutoSave.savesOwner(StoryAutoSave.MODE_OFF, "1", "9", false));
    }

    @Test
    public void everyoneSavesListedAndUnlistedOwners() {
        assertTrue(StoryAutoSave.savesOwner(StoryAutoSave.MODE_EVERYONE, "1", "9", true));
        assertTrue(StoryAutoSave.savesOwner(StoryAutoSave.MODE_EVERYONE, "1", "9", false));
    }

    @Test
    public void listedSavesListedOwnersOnly() {
        assertTrue(StoryAutoSave.savesOwner(StoryAutoSave.MODE_LISTED, "1", "9", true));
        assertFalse(StoryAutoSave.savesOwner(StoryAutoSave.MODE_LISTED, "1", "9", false));
    }

    @Test
    public void ownStoriesAreNeverSaved() {
        assertFalse(StoryAutoSave.savesOwner(StoryAutoSave.MODE_EVERYONE, "9", "9", false));
        assertFalse(StoryAutoSave.savesOwner(StoryAutoSave.MODE_LISTED, "9", "9", true));
    }

    @Test
    public void anUnknownOwnerOrModeSavesNothing() {
        assertFalse(StoryAutoSave.savesOwner(StoryAutoSave.MODE_EVERYONE, null, "9", true));
        assertFalse(StoryAutoSave.savesOwner(StoryAutoSave.MODE_EVERYONE, "", "9", true));
        assertFalse(StoryAutoSave.savesOwner("removed_mode", "1", "9", true));
        assertFalse(StoryAutoSave.savesOwner(null, "1", "9", true));
    }

    @Test
    public void distinctMediaOfOneOwnerGetDistinctNamesFromANonUniqueTemplate() {
        String first = name("{username}", "101");
        String second = name("{username}", "102");

        // GalleryStoreTest publishes these names under every conflict policy.
        assertEquals("alice_101.jpg", first);
        assertEquals("alice_102.jpg", second);
        assertEquals(first, name("{username}", "101"));
    }

    @Test
    public void theMediaIdSurvivesTheLengthLimitWhole() {
        String template = repeat('a', 121) + "{media_id}" + repeat('b', 121);
        String first = name(template, "1234567890123456789");
        String second = name(template, "1234567891123456789");

        assertNotEquals(first, second);
        assertTrue(first, first.endsWith("_1234567890123456789.jpg"));
        assertTrue(second, second.endsWith("_1234567891123456789.jpg"));
        assertTrue(first.getBytes(StandardCharsets.UTF_8).length <= 255);
        assertTrue(second.getBytes(StandardCharsets.UTF_8).length <= 255);
    }

    @Test
    public void aMediaIdIsTakenOncePerProcess() {
        assertTrue(StoryAutoSave.firstView("first-view-201"));
        assertFalse(StoryAutoSave.firstView("first-view-201"));
        assertTrue(StoryAutoSave.firstView("first-view-202"));
        assertFalse(StoryAutoSave.firstView(null));
    }

    @Test
    public void aFailedDownloadReleasesItsMediaIdAndASkipKeepsIt() {
        int[] historyEnded = {0};
        DownloadRequest.Tracker history = new DownloadRequest.Tracker() {
            @Override
            public void started() {
            }

            @Override
            public void ended(boolean saved, Uri document, Throwable error) {
                historyEnded[0]++;
            }

            @Override
            public void alreadyDownloading() {
            }
        };
        assertTrue(StoryAutoSave.firstView("release-301"));

        StoryAutoSave.releaseOnFailure(history, "release-301").ended(false, null, null);
        assertFalse(StoryAutoSave.firstView("release-301"));

        StoryAutoSave.releaseOnFailure(history, "release-301").ended(false, null, new IOException("offline"));
        assertTrue(StoryAutoSave.firstView("release-301"));
        assertEquals(2, historyEnded[0]);
    }

    @Test
    public void aRequestThatCannotBeBuiltReleasesItsMediaId() {
        assertTrue(StoryAutoSave.firstView("release-302"));

        assertNull(StoryAutoSave.buildOrRelease("release-302", () -> {
            throw new IllegalStateException("No media");
        }));
        assertTrue(StoryAutoSave.firstView("release-302"));
    }

    // The file name auto-save gives a story of alice.
    private static String name(String template, String mediaId) {
        return DownloadFileNameFormatter.format(template,
                new DownloadFileNameFormatter.Values("alice", mediaId, "", null, "image", 0, ""),
                StoryAutoSave.nameSuffix(mediaId), ".jpg", ZoneOffset.UTC);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
