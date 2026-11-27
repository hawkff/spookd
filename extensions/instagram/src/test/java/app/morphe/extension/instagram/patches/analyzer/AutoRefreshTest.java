package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutoRefreshTest {
    private static final long LAST = 1_700_000_000_000L;
    private static final long WEEK = 7 * AutoRefresh.DAY_MS;

    @Test
    public void aScanIsDueOnceTheIntervalPassed() {
        assertFalse(AutoRefresh.isDue(LAST, 7, LAST + WEEK - 1));
        assertTrue(AutoRefresh.isDue(LAST, 7, LAST + WEEK));
        assertTrue(AutoRefresh.isDue(LAST, 1, LAST + AutoRefresh.DAY_MS));
    }

    @Test
    public void offOrWithoutAScanNothingIsDue() {
        assertFalse(AutoRefresh.isDue(LAST, 0, LAST + 100 * WEEK));
        assertFalse(AutoRefresh.isDue(0, 7, LAST));
    }

    @Test
    public void aClockSetBackBeforeTheLastScanMakesItDue() {
        assertTrue(AutoRefresh.isDue(LAST, 14, LAST - 1));
    }
}
