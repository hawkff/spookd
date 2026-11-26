package app.morphe.extension.instagram.patches.distractionFree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;

public class DistractionFreeHidesTest {
    // Stands in for Instagram's ClipsViewerSource, whose constants keep their names.
    private enum Source { CLIPS_TAB, CLIPS_TAB_HOMECOMING, FEED_TIMELINE }

    @Test
    public void onlyTheReelsTabCountsAsTheReelsTab() {
        assertTrue(DistractionFreeHides.isReelsTab(Source.CLIPS_TAB));
        assertTrue(DistractionFreeHides.isReelsTab(Source.CLIPS_TAB_HOMECOMING));
        assertFalse(DistractionFreeHides.isReelsTab(Source.FEED_TIMELINE));
        assertFalse(DistractionFreeHides.isReelsTab("CLIPS_TAB"));
        assertFalse(DistractionFreeHides.isReelsTab(null));
    }

    @Test
    public void unmarkedHeadersKeepTheirVisibility() {
        assertEquals(View.VISIBLE, DistractionFreeHides.reelsHeaderVisibility(null, View.VISIBLE));
        assertEquals(View.INVISIBLE, DistractionFreeHides.reelsHeaderVisibility(null, View.INVISIBLE));
    }
}
