package app.morphe.extension.instagram.patches.analyzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
