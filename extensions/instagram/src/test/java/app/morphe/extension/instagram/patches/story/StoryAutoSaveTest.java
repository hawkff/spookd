package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.instagram.patches.download.DownloadFileNameFormatter;

public class StoryAutoSaveTest {

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
    public void namesWithTheMediaIdOrShortcodeAreUnique() {
        assertTrue(StoryAutoSave.namesAreUnique(DownloadFileNameFormatter.DEFAULT_TEMPLATE));
        assertTrue(StoryAutoSave.namesAreUnique("{username}_{media_id}"));
        assertTrue(StoryAutoSave.namesAreUnique("{shortcode}"));
    }

    @Test
    public void namesWithoutAnItemIdMayRepeat() {
        assertFalse(StoryAutoSave.namesAreUnique("{username}"));
        assertFalse(StoryAutoSave.namesAreUnique("{username}_{upload_date}"));
    }

    @Test
    public void anInvalidTemplateFallsBackToTheUniqueDefault() {
        assertTrue(StoryAutoSave.namesAreUnique(null));
        assertTrue(StoryAutoSave.namesAreUnique(""));
        assertTrue(StoryAutoSave.namesAreUnique("{unknown_token}"));
    }
}
