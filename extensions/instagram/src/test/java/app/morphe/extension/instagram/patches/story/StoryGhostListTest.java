package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Robolectric provides a working org.json.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class StoryGhostListTest {

    @Test
    public void everyoneModeAppliesToListedAndUnlistedOwners() {
        assertTrue(StoryGhostList.appliesTo(StoryGhostList.MODE_EVERYONE, true));
        assertTrue(StoryGhostList.appliesTo(StoryGhostList.MODE_EVERYONE, false));
    }

    @Test
    public void exceptListedModeLeavesListedOwnersOut() {
        assertFalse(StoryGhostList.appliesTo(StoryGhostList.MODE_EXCEPT_LISTED, true));
        assertTrue(StoryGhostList.appliesTo(StoryGhostList.MODE_EXCEPT_LISTED, false));
    }

    @Test
    public void onlyListedModeAppliesToListedOwnersOnly() {
        assertTrue(StoryGhostList.appliesTo(StoryGhostList.MODE_ONLY_LISTED, true));
        assertFalse(StoryGhostList.appliesTo(StoryGhostList.MODE_ONLY_LISTED, false));
    }

    @Test
    public void anUnknownModeKeepsEveryoneAnonymous() {
        assertTrue(StoryGhostList.appliesTo("removed_mode", false));
        assertTrue(StoryGhostList.appliesTo(null, false));
    }

    @Test
    public void addingKeepsTheOrderAndRefusesDuplicates() {
        String one = StoryGhostUsers.withUser("{}", "1", "first");
        String two = StoryGhostUsers.withUser(one, "2", "second");
        Map<String, String> users = StoryGhostUsers.parse(two);

        assertEquals(Arrays.asList("1", "2"), new ArrayList<>(users.keySet()));
        assertEquals("first", users.get("1"));
        assertEquals("second", users.get("2"));
        assertNull(StoryGhostUsers.withUser(two, "1", "renamed"));
    }

    @Test
    public void removingDropsOnlyThatUser() {
        String two = StoryGhostUsers.withUser(StoryGhostUsers.withUser("{}", "1", "first"), "2", "second");

        assertEquals(Collections.singletonMap("2", "second"),
                StoryGhostUsers.parse(StoryGhostUsers.withoutUser(two, "1")));
        assertEquals(StoryGhostUsers.parse(two), StoryGhostUsers.parse(StoryGhostUsers.withoutUser(two, "3")));
    }

    @Test
    public void aDamagedListReadsAsEmpty() {
        assertTrue(StoryGhostUsers.parse("not json").isEmpty());
        assertEquals(Collections.singletonMap("1", "first"),
                StoryGhostUsers.parse(StoryGhostUsers.withUser("not json", "1", "first")));
    }
}
