package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneOffset;

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
    public void templatesWithTheMediaIdAreKept() {
        assertEquals(DownloadFileNameFormatter.DEFAULT_TEMPLATE,
                StoryAutoSave.uniqueTemplate(DownloadFileNameFormatter.DEFAULT_TEMPLATE));
        assertEquals("{media_id}", StoryAutoSave.uniqueTemplate("{media_id}"));
    }

    @Test
    public void templatesWithoutTheMediaIdGetIt() {
        assertEquals("{username}_{media_id}", StoryAutoSave.uniqueTemplate("{username}"));
        assertEquals("{shortcode}_{media_id}", StoryAutoSave.uniqueTemplate("{shortcode}"));
        assertEquals("{username}_{upload_date}_{media_id}", StoryAutoSave.uniqueTemplate("{username}_{upload_date}"));
    }

    @Test
    public void anInvalidTemplateFallsBackToTheDefault() {
        assertEquals(DownloadFileNameFormatter.DEFAULT_TEMPLATE, StoryAutoSave.uniqueTemplate(null));
        assertEquals(DownloadFileNameFormatter.DEFAULT_TEMPLATE, StoryAutoSave.uniqueTemplate(""));
        assertEquals(DownloadFileNameFormatter.DEFAULT_TEMPLATE, StoryAutoSave.uniqueTemplate("{unknown_token}"));
    }

    @Test
    public void distinctMediaOfOneOwnerGetDistinctNamesFromANonUniqueTemplate() {
        String template = StoryAutoSave.uniqueTemplate("{username}");
        String first = name(template, "101");
        String second = name(template, "102");

        // GalleryStoreTest publishes these names under every conflict policy.
        assertEquals("alice_101.jpg", first);
        assertEquals("alice_102.jpg", second);
        assertNotEquals(first, second);
        assertEquals(first, name(template, "101"));
    }

    @Test
    public void aMediaIdIsTakenOncePerProcess() {
        assertTrue(StoryAutoSave.firstView("first-view-201"));
        assertFalse(StoryAutoSave.firstView("first-view-201"));
        assertTrue(StoryAutoSave.firstView("first-view-202"));
        assertFalse(StoryAutoSave.firstView(null));
    }

    private static String name(String template, String mediaId) {
        return DownloadFileNameFormatter.format(template,
                new DownloadFileNameFormatter.Values("alice", mediaId, "", null, "image", 0, ""),
                ".jpg", ZoneOffset.UTC);
    }
}
