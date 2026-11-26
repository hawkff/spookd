package app.morphe.extension.instagram.patches.story.viewers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;

// Robolectric for the platform JSON classes.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class ViewerStarsTest {

    @Test
    public void eachAccountHasItsOwnKeySoACopiedSettingsFileDoesNotCarryStars() {
        assertEquals("story_viewer_stars_A", ViewerStars.key("A"));
        assertNotEquals(ViewerStars.key("A"), ViewerStars.key("B"));
        assertNull(ViewerStars.key(null));
        assertNull(ViewerStars.key(""));
    }

    @Test
    public void toggleAddsThenRemoves() {
        String one = ViewerStars.toggled("{}", "1", "anna");
        String two = ViewerStars.toggled(one, "2", null);
        assertEquals(new HashSet<>(Arrays.asList("1", "2")), ViewerStars.ids(two));
        assertEquals(Collections.singleton("2"), ViewerStars.ids(ViewerStars.toggled(two, "1", "anna")));
    }

    @Test
    public void unreadableStarsReadAsNone() {
        assertEquals(Collections.emptySet(), ViewerStars.ids("not json"));
        assertEquals(Collections.emptySet(), ViewerStars.ids(null));
        assertEquals(Collections.singleton("3"), ViewerStars.ids(ViewerStars.toggled("broken", "3", "c")));
    }
}
