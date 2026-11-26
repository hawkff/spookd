package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class StoryEarlierSeenTest {

    @Test
    public void itemsBeforeTheSelectedOneAreReturnedInOrder() {
        List<String> items = Arrays.asList("a", "b", "c", "d");
        assertEquals(Arrays.asList("a", "b"), StoryEarlierSeen.itemsBefore(items, "c"));
    }

    @Test
    public void firstOrUnknownItemHasNoEarlierItems() {
        List<String> items = Arrays.asList("a", "b");
        assertTrue(StoryEarlierSeen.itemsBefore(items, "a").isEmpty());
        assertTrue(StoryEarlierSeen.itemsBefore(items, "z").isEmpty());
    }
}
