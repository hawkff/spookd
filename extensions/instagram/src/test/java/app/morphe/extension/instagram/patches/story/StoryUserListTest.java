package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Loading the class loads the settings, as in StoryGhostListTest.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class StoryUserListTest {

    @Test
    public void storyListsKeepTheirStoredKey() {
        assertEquals("story_ghost_users", StoryUserList.storageKey("story_ghost_users", false, null));
        assertEquals("story_ghost_users", StoryUserList.storageKey("story_ghost_users", false, "1"));
    }

    @Test
    public void aNewAccountDoesNotReadTheChatsOfTheAccountItsSettingsWereCopiedFrom() {
        // Account B starts with a copy of account A's settings file, so A's key is there but B reads its own.
        String a = StoryUserList.storageKey("dm_ghost_chats", true, "A");
        String b = StoryUserList.storageKey("dm_ghost_chats", true, "B");
        assertNotEquals(a, b);
        assertEquals("dm_ghost_chats_A", a);
        assertNull(StoryUserList.storageKey("dm_ghost_chats", true, null));
        assertNull(StoryUserList.storageKey("dm_ghost_chats", true, ""));
    }
}
