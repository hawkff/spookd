package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.instagram.patches.analyzer.FollowScan.State;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class FollowScanTest {
    private static final String ACCOUNT = "1001";
    private static final long NOW = 1_700_000_000_000L;

    private final long[] clock = {NOW};
    private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> bounds = Collections.synchronizedList(new ArrayList<>());
    /** Sleeps and reported states in the order they happened. */
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());
    private Context context;
    private PikoFollowDb db;
    private FollowScan.Foreground foreground;
    private Script script;
    private FollowScan scan;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        db = new PikoFollowDb(context);
        foreground = new FollowScan.Foreground();
        foreground.onResumed();
        script = new Script();
        // Alternates between the lowest and the highest wait.
        Random extremes = new Random() {
            private int calls;

            @Override
            public int nextInt(int bound) {
                bounds.add(bound);
                return calls++ % 2 == 0 ? 0 : bound - 1;
            }
        };
        FollowScan.Sleeper sleeper = millis -> {
            sleeps.add(millis);
            order.add("sleep " + millis);
            clock[0] += millis;
        };
        scan = new FollowScan(context, db, script, foreground, () -> clock[0], sleeper, extremes);
    }

    @After
    public void tearDown() {
        PikoFollowDb.beforeCommitDecision = null;
        db.close();
    }

    @Test
    public void pacesTwoToFourSecondsAfterEachPageAndStoresBaseline() throws Exception {
        script.add(page("c1", "1", "2"), page(null, "3"), page(null, "4"));

        Recorder recorder = run(3, 1);

        assertEquals(State.FINISHED, recorder.end);
        assertEquals(Arrays.asList(2_000L, 4_000L), sleeps);
        assertEquals(Arrays.asList(2_001, 2_001), bounds);
        assertEquals(Arrays.asList("0:null", "0:c1", "1:null"), script.requests);
        assertEquals(1, script.tokens.size());
        assertFalse(script.tokens.iterator().next().isEmpty());
        assertEquals("1 1/1 FINISHED", recorder.last());
        assertFalse(scan.isRunning());
        assertEquals(1, count("SELECT COUNT(*) FROM follow_scans WHERE account_id = ?"
                + " AND follower_count = 3 AND following_count = 1", ACCOUNT));
        assertTrue(db.history(ACCOUNT).isEmpty());
    }

    @Test
    public void threeBackOffsThenFailureStoresNothing() throws Exception {
        script.add(null, null, null, null);

        Recorder recorder = run(10, 10);

        assertEquals(State.FAILED, recorder.end);
        assertEquals(Arrays.asList(10_000L, 20_000L, 40_000L), sleeps);
        assertEquals(Arrays.asList("0:null", "0:null", "0:null", "0:null"), script.requests);
        assertTrue(recorder.states.contains(State.BACKING_OFF));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_scans"));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_scan_members"));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_users"));
    }

    @Test
    public void successResetsBackOff() throws Exception {
        script.add(null, null, page("c1", "1"), null, null, null, page(null, "2"), page(null, "3"));

        Recorder recorder = run(2, 1);

        assertEquals(State.FINISHED, recorder.end);
        assertEquals(Arrays.asList(10_000L, 20_000L, 2_000L, 10_000L, 20_000L, 40_000L, 4_000L),
                sleeps);
        assertEquals(Arrays.asList("0:null", "0:null", "0:null", "0:c1", "0:c1", "0:c1", "0:c1",
                "1:null"), script.requests);
    }

    @Test
    public void repeatedCursorAndEarlyEmptyPageAreThrottles() throws Exception {
        // The repeat page's rows are dropped; the empty page arrives at 50% of the expected count.
        script.add(page("c1", "1"), page("c1", "2"), page(null), page(null, "2"),
                page(null));

        Recorder recorder = run(2, 0);

        assertEquals(State.FINISHED, recorder.end);
        assertEquals(Arrays.asList(2_000L, 10_000L, 20_000L, 4_000L), sleeps);
        assertEquals(Arrays.asList("0:null", "0:c1", "0:c1", "0:c1", "1:null"), script.requests);
        assertEquals(1, count("SELECT COUNT(*) FROM follow_scans"
                + " WHERE follower_count = 2 AND following_count = 0"));
    }

    @Test
    public void emptyPageWithNewCursorIsThrottle() throws Exception {
        script.add(page("c1", "1"), page("c2"), page(null, "2"), page(null));

        Recorder recorder = run(2, 0);

        assertEquals(State.FINISHED, recorder.end);
        assertTrue(sleeps.contains(10_000L));
        assertEquals(Arrays.asList("0:null", "0:c1", "0:c1", "1:null"), script.requests);
        assertEquals(1, count("SELECT COUNT(*) FROM follow_scans"
                + " WHERE follower_count = 2 AND following_count = 0"));
    }

    @Test
    public void emptyLastPageEndsListAtNinetyPercent() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 19; i++) ids.add(String.valueOf(i));
        script.add(page("c1", ids.toArray(new String[0])), page(null), page(null));

        Recorder recorder = run(20, 0);

        assertEquals(State.FINISHED, recorder.end);
        assertEquals(Arrays.asList(2_000L, 4_000L), sleeps);
        assertEquals(Arrays.asList("0:null", "0:c1", "1:null"), script.requests);
    }

    @Test
    public void refusesWithDistinctReasonsAndNextAllowedTime() throws Exception {
        assertEquals(FollowScan.Refusal.TOO_MANY_ACCOUNTS, scan.start(ACCOUNT, 13_000, 1, null));
        assertEquals(0, scan.nextAllowedStart(ACCOUNT));

        CountDownLatch gate = new CountDownLatch(1);
        script.gate = gate;
        Recorder first = new Recorder();
        assertNull(scan.start(ACCOUNT, 12_000, 1_000, first));
        assertTrue(script.entered.await(10, TimeUnit.SECONDS));
        assertEquals(FollowScan.Refusal.ALREADY_RUNNING, scan.start("2002", 1, 1, null));
        scan.stop();
        gate.countDown();
        assertTrue(first.done.await(10, TimeUnit.SECONDS));
        awaitIdle();
        assertEquals(State.STOPPED, first.end);
        assertEquals(0, count("SELECT COUNT(*) FROM follow_scans"));
        assertEquals(0, scan.nextAllowedStart("2002"));

        clock[0] = NOW + 59 * 60_000L;
        assertEquals(FollowScan.Refusal.TOO_SOON, scan.start(ACCOUNT, 1, 1, null));
        assertEquals(NOW + FollowScan.MIN_START_INTERVAL_MS, scan.nextAllowedStart(ACCOUNT));
        assertEquals(NOW, context.getSharedPreferences("piko_profile_analyzer", Context.MODE_PRIVATE)
                .getLong("last_scan_start_" + ACCOUNT, 0));

        clock[0] = NOW + FollowScan.MIN_START_INTERVAL_MS;
        assertEquals(0, scan.nextAllowedStart(ACCOUNT));
        script.add(page(null, "1"), page(null, "2"));
        Recorder later = new Recorder();
        assertNull(scan.start(ACCOUNT, 1, 1, later));
        assertTrue(later.done.await(10, TimeUnit.SECONDS));
        assertEquals(State.FINISHED, later.end);
    }

    @Test
    public void pauseAndResumeContinueFromTheSameCursor() throws Exception {
        script.add(page("c1", "1"), page(null, "2"), page(null, "3"));
        script.pauseAt = 0;

        Recorder recorder = run(2, 1);

        assertEquals(State.FINISHED, recorder.end);
        assertEquals(Arrays.asList("0:null", "0:c1", "1:null"), script.requests);
        assertEquals(Arrays.asList(2_000L, 4_000L), sleeps);
        assertEquals(1, Collections.frequency(recorder.states, State.PAUSED));
        assertFalse(recorder.states.contains(State.BACKING_OFF));
        assertEquals(1, count("SELECT COUNT(*) FROM follow_scans WHERE follower_count = 2"));
    }

    @Test
    public void pauseNeitherResetsNorAdvancesBackOff() throws Exception {
        script.add(null, null, null, null);
        script.pauseAt = 0;

        Recorder recorder = run(1, 1);

        assertEquals(State.FAILED, recorder.end);
        assertEquals(Arrays.asList(10_000L, 20_000L, 40_000L), sleeps);
        assertEquals(4, script.requests.size());
        assertEquals(1, Collections.frequency(recorder.states, State.PAUSED));
    }

    @Test
    public void stopWakesAPausedScan() throws Exception {
        foreground.onPaused();
        Recorder recorder = new Recorder();
        recorder.resumeOnPause = false;

        assertNull(scan.start(ACCOUNT, 1, 1, recorder));
        assertTrue(recorder.paused.await(10, TimeUnit.SECONDS));
        scan.stop();

        assertTrue(recorder.done.await(10, TimeUnit.SECONDS));
        assertEquals(State.STOPPED, recorder.end);
        assertTrue(script.requests.isEmpty());
        assertEquals(0, count("SELECT COUNT(*) FROM follow_scans"));
    }

    @Test
    public void requestBackgroundedInFlightPausesBeforeAnyWait() throws Exception {
        script.add(null, page(null, "1"), page(null, "2"));
        script.pauseAt = 0;

        Recorder recorder = run(1, 1);

        assertEquals(State.FINISHED, recorder.end);
        int paused = order.indexOf("PAUSED");
        assertTrue(paused >= 0);
        assertTrue(paused < order.indexOf("BACKING_OFF"));
        assertTrue(paused < order.indexOf("sleep 10000"));
        assertEquals(Arrays.asList(10_000L, 2_000L), sleeps);
        assertEquals(Arrays.asList("0:null", "0:null", "1:null"), script.requests);
    }

    @Test
    public void stopDuringCommitRollsBackAndReportsStopped() throws Exception {
        script.add(page(null, "1"), page(null, "2"));
        CountDownLatch inCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PikoFollowDb.beforeCommitDecision = () -> {
            inCommit.countDown();
            // Waits through the stop's interrupt, as a database write would.
            while (true) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                    return;
                } catch (InterruptedException ignored) {
                }
            }
        };
        Recorder recorder = new Recorder();

        assertNull(scan.start(ACCOUNT, 1, 1, recorder));
        assertTrue(inCommit.await(10, TimeUnit.SECONDS));
        scan.stop();
        release.countDown();

        assertTrue(recorder.done.await(10, TimeUnit.SECONDS));
        assertEquals(State.STOPPED, recorder.end);
        assertEquals(0, count("SELECT COUNT(*) FROM follow_scans"));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_scan_members"));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_users"));
        assertEquals(0, count("SELECT COUNT(*) FROM follow_changes"));
    }

    @Test
    public void stopStillWakesAPausedScanAfterAFetchError() throws Exception {
        script.failNext = new AssertionError("fetch");
        assertEquals(State.FAILED, run(1, 1).end);

        foreground.onPaused();
        Recorder recorder = new Recorder();
        recorder.resumeOnPause = false;
        assertNull(scan.start("2002", 1, 1, recorder));
        assertTrue(recorder.paused.await(10, TimeUnit.SECONDS));
        scan.stop();

        assertTrue(recorder.done.await(10, TimeUnit.SECONDS));
        assertEquals(State.STOPPED, recorder.end);
    }

    @Test
    public void aStartFromTheLastReportIsRefusedAsRunning() throws Exception {
        script.add(page(null, "1"), page(null, "2"));
        FollowScan.Refusal[] refusal = new FollowScan.Refusal[1];
        Recorder recorder = new Recorder() {
            @Override
            public void onProgress(int list, int received, int expected, State state) {
                if (state == State.FINISHED) refusal[0] = scan.start("2002", 1, 1, null);
                super.onProgress(list, received, expected, state);
            }
        };

        assertNull(scan.start(ACCOUNT, 1, 1, recorder));
        assertTrue(recorder.done.await(10, TimeUnit.SECONDS));
        awaitIdle();

        assertEquals(FollowScan.Refusal.ALREADY_RUNNING, refusal[0]);
        assertEquals("1 1/1 FINISHED", recorder.last());
        assertEquals(0, scan.nextAllowedStart("2002"));
    }

    private Recorder run(int followers, int following) throws Exception {
        Recorder recorder = new Recorder();
        assertNull(scan.start(ACCOUNT, followers, following, recorder));
        assertTrue(recorder.done.await(10, TimeUnit.SECONDS));
        awaitIdle();
        return recorder;
    }

    /** The scan counts as running until its last report returned. */
    private void awaitIdle() throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (scan.isRunning() && System.currentTimeMillis() < until) Thread.sleep(5);
        assertFalse(scan.isRunning());
    }

    private static PageSource.Page page(String next, String... ids) {
        List<FollowUser> users = new ArrayList<>();
        for (String id : ids) users.add(new FollowUser(id, "user" + id, "Name " + id, false, null));
        return new PageSource.Page(users, next);
    }

    private long count(String sql, String... args) {
        return DatabaseUtils.longForQuery(db.getReadableDatabase(), sql, args);
    }

    /** Answers with scripted pages in order; null is a throttle. */
    private final class Script implements PageSource {
        final List<Page> pages = new ArrayList<>();
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final Set<String> tokens = Collections.synchronizedSet(new HashSet<>());
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch gate;
        volatile int pauseAt = -1;
        volatile Error failNext;
        private int next;

        void add(Page... added) {
            pages.addAll(Arrays.asList(added));
        }

        @Override
        public synchronized Page fetchPage(String accountId, int list, String maxId, String rankToken) {
            requests.add(list + ":" + maxId);
            tokens.add(rankToken);
            entered.countDown();
            CountDownLatch waitFor = gate;
            if (waitFor != null) {
                gate = null;
                try {
                    waitFor.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return page(null, "1");
            }
            Error fail = failNext;
            if (fail != null) {
                failNext = null;
                throw fail;
            }
            if (next == pauseAt) foreground.onPaused();
            return pages.get(next++);
        }
    }

    private class Recorder implements FollowScan.Listener {
        final List<State> states = Collections.synchronizedList(new ArrayList<>());
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean resumeOnPause = true;
        volatile State end;

        @Override
        public void onProgress(int list, int received, int expected, State state) {
            states.add(state);
            order.add(state.name());
            events.add(list + " " + received + "/" + expected + " " + state);
            if (state == State.PAUSED) {
                paused.countDown();
                if (resumeOnPause) foreground.onResumed();
            }
            if (state == State.FINISHED || state == State.FAILED || state == State.STOPPED) {
                end = state;
                done.countDown();
            }
        }

        String last() {
            return events.get(events.size() - 1);
        }
    }
}
