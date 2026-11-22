package app.morphe.extension.instagram.patches.filter.story;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FilterStoryTest {
    private static final int DEFAULT_MIN = 1;
    private static final int DEFAULT_MAX = 9999;

    @Test
    public void zeroCountItemsAreDroppedUnderDefaults() {
        assertTrue(FilterStory.outsideCountRange(0, DEFAULT_MIN, DEFAULT_MAX));
    }

    @Test
    public void countsInsideTheDefaultRangeAreKept() {
        assertFalse(FilterStory.outsideCountRange(1, DEFAULT_MIN, DEFAULT_MAX));
        assertFalse(FilterStory.outsideCountRange(DEFAULT_MAX, DEFAULT_MIN, DEFAULT_MAX));
        assertTrue(FilterStory.outsideCountRange(DEFAULT_MAX + 1, DEFAULT_MIN, DEFAULT_MAX));
    }
}
