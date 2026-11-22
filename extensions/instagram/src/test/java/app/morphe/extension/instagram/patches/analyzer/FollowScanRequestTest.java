package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class FollowScanRequestTest {

    @Test
    public void aFetchThatNeverReturnsIsCancelledAfterTheTimeout() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        String[] thread = new String[1];
        long start = System.nanoTime();
        Object page = FollowScanRequest.withTimeout(() -> {
            thread[0] = Thread.currentThread().getName();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return "late";
        }, 200);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertNull(page);
        assertTrue("waited " + elapsedMs + " ms", elapsedMs < 5_000);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(FollowScanRequest.FETCH_THREAD_NAME, thread[0]);
    }

    @Test
    public void aThirdFetchIsRefusedWhileTwoIgnoreTheirCancel() throws Exception {
        awaitNoOutstandingFetch();
        AtomicInteger started = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        Callable<Object> stubborn = () -> {
            started.incrementAndGet();
            try {
                while (true) {
                    try {
                        if (release.await(10, TimeUnit.SECONDS)) return "late";
                    } catch (InterruptedException ignored) {
                        // Ignores the cancel on purpose.
                    }
                }
            } finally {
                finished.countDown();
            }
        };
        try {
            assertNull(FollowScanRequest.withTimeout(stubborn, 100));
            assertNull(FollowScanRequest.withTimeout(stubborn, 100));
            long start = System.nanoTime();
            assertNull(FollowScanRequest.withTimeout(stubborn, 5_000));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
            assertEquals(2, started.get());
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        awaitNoOutstandingFetch();
    }

    private static void awaitNoOutstandingFetch() throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (FollowScanRequest.outstandingFetches() != 0 && System.currentTimeMillis() < until) {
            Thread.sleep(5);
        }
        assertEquals(0, FollowScanRequest.outstandingFetches());
    }

    @Test
    public void aFetchThatReturnsGivesItsResult() {
        assertEquals("page", FollowScanRequest.withTimeout(() -> "page", 5_000));
    }

    @Test
    public void aFailingFetchGivesNull() {
        assertNull(FollowScanRequest.withTimeout(() -> {
            throw new IllegalStateException();
        }, 5_000));
    }

    @Test
    public void aFullNameEqualToTheUsernameIsKept() {
        assertEquals("alice", FollowScanRequest.followUser("1", "alice", "alice", false, null).fullName);
    }

    @Test
    public void onlyAMissingOrEmptyFullNameIsNull() {
        assertNull(FollowScanRequest.followUser("1", "alice", null, false, null).fullName);
        assertNull(FollowScanRequest.followUser("1", "alice", "", false, null).fullName);
        assertEquals("Alice Smith", FollowScanRequest.followUser("1", "alice", "Alice Smith", true, "p").fullName);
    }

    @Test
    public void aUserWithoutAnIdIsSkipped() {
        assertNull(FollowScanRequest.followUser(null, "alice", "Alice", false, null));
        assertNull(FollowScanRequest.followUser("", "alice", "Alice", false, null));
    }
}
