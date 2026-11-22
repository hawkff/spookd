package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import app.morphe.extension.instagram.patches.analyzer.FollowScan.State;

public final class ProfileAnalyzerTest {
    private static final long NEXT = 1_700_000_000_000L;

    @Test
    public void onlyTheCurrentAccountsOwnCountsAreUsed() {
        ProfileAnalyzer.recordCounts("1001", 10, 20);
        // Switched to 2002, whose own profile has not been shown yet.
        assertNull(ProfileAnalyzer.countsFor("2002"));
        assertNull(ProfileAnalyzer.countsFor(null));
        assertEquals(10, ProfileAnalyzer.countsFor("1001").followers);
        assertEquals(20, ProfileAnalyzer.countsFor("1001").following);
    }

    @Test
    public void aRunningScanShowsItsProgressBeforeAnyRefusal() {
        assertEquals(ProfileAnalyzer.ROW_SCANNING, ProfileAnalyzer.rowSummaryKey(State.RUNNING, false, true, NEXT));
        assertEquals(ProfileAnalyzer.ROW_SCANNING, ProfileAnalyzer.rowSummaryKey(State.BACKING_OFF, true, false, NEXT));
        assertEquals(ProfileAnalyzer.ROW_PAUSED, ProfileAnalyzer.rowSummaryKey(State.PAUSED, false, true, NEXT));
    }

    @Test
    public void withoutTheOwnProfileTheRowAsksToOpenIt() {
        assertEquals(ProfileAnalyzer.ROW_OPEN_PROFILE, ProfileAnalyzer.rowSummaryKey(null, false, false, 0));
        assertEquals(ProfileAnalyzer.ROW_OPEN_PROFILE, ProfileAnalyzer.rowSummaryKey(State.FINISHED, false, true, NEXT));
    }

    @Test
    public void tooManyAccountsComesBeforeTheNextAllowedStart() {
        assertEquals(ProfileAnalyzer.ROW_TOO_MANY, ProfileAnalyzer.rowSummaryKey(null, true, true, NEXT));
    }

    @Test
    public void anEndedScanShowsTheNextAllowedStartOrStart() {
        assertEquals(ProfileAnalyzer.ROW_NEXT_SCAN, ProfileAnalyzer.rowSummaryKey(State.FINISHED, true, false, NEXT));
        assertEquals(ProfileAnalyzer.ROW_NEXT_SCAN, ProfileAnalyzer.rowSummaryKey(State.FAILED, true, false, NEXT));
        assertEquals(ProfileAnalyzer.ROW_START, ProfileAnalyzer.rowSummaryKey(State.STOPPED, true, false, 0));
        assertEquals(ProfileAnalyzer.ROW_START, ProfileAnalyzer.rowSummaryKey(null, true, false, 0));
    }
}
