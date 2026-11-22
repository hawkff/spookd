package app.morphe.extension.instagram.patches.filter.story;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

public class FilterStoryTest {
    private static final Set<String> NONE = Collections.emptySet();

    @Test
    public void defaultsAndWiderRangesFilterNothing() {
        assertTrue(FilterStory.atDefaults(NONE, NONE, 1, 9999));
        assertTrue(FilterStory.atDefaults(NONE, NONE, 0, 100000));
    }

    @Test
    public void anySetFilterNeedsTheItems() {
        assertFalse(FilterStory.atDefaults(Collections.singleton("archive_day_media_reel"), NONE, 1, 9999));
        assertFalse(FilterStory.atDefaults(NONE, Collections.singleton("verified"), 1, 9999));
        assertFalse(FilterStory.atDefaults(NONE, NONE, 2, 9999));
        assertFalse(FilterStory.atDefaults(NONE, NONE, 1, 9998));
    }
}
