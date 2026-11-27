package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class StoryLinkTest {

    @Test
    public void storyItemLinksToOwnerAndMediaPk() {
        assertEquals("https://www.instagram.com/stories/someone/3456789012345678901/",
                StoryLink.link("1234567", "someone", "3456789012345678901"));
    }

    @Test
    public void mediaIdWithOwnerKeepsOnlyThePk() {
        assertEquals("https://www.instagram.com/stories/someone/345/",
                StoryLink.link(null, "someone", "345_1234567"));
    }

    @Test
    public void highlightReelLinksToTheHighlight() {
        assertEquals("https://www.instagram.com/stories/highlights/17912345678901234/",
                StoryLink.link("highlight:17912345678901234", "someone", "345"));
    }

    @Test
    public void emptyHighlightIdFallsBackToTheItemLink() {
        assertEquals("https://www.instagram.com/stories/someone/345/",
                StoryLink.link("highlight:", "someone", "345"));
    }

    @Test
    public void missingPartsGiveNoLink() {
        assertNull(StoryLink.link(null, null, "345"));
        assertNull(StoryLink.link(null, "", "345"));
        assertNull(StoryLink.link(null, "someone", null));
        assertNull(StoryLink.link(null, "someone", "_1234567"));
    }
}
