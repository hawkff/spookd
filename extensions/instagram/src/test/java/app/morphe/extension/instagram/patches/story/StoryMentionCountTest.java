package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class StoryMentionCountTest {

    @Test
    public void twoRecordsForOnePersonCountOnce() {
        assertEquals(2, StoryMentionCount.people(Arrays.asList("11", "11", "22")));
    }

    @Test
    public void missingUserIdsAreNotCounted() {
        assertEquals(1, StoryMentionCount.people(Arrays.asList(null, "", "33")));
    }
}
