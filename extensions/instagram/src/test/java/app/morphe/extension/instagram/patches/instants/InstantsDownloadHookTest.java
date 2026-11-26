package app.morphe.extension.instagram.patches.instants;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.instagram.db.SpookdInstantsDb;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class InstantsDownloadHookTest {
    @Test
    public void aCaptureQueuedBeforeAClearCannotInsertAfterIt() throws Exception {
        SpookdInstantsDb db = SpookdInstantsDb.getInstance(RuntimeEnvironment.getApplication());
        CountDownLatch release = new CountDownLatch(1);
        InstantsDownloadHook.sDbExecutor.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            db.insertOrIgnore("1", "alice", "11", "https://example.com/a.jpg", null, false, null, null);
        });
        AtomicBoolean cleared = new AtomicBoolean();
        Thread clear = new Thread(() -> {
            try {
                cleared.set(InstantsDownloadHook.clearVault(db));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        clear.start();

        clear.join(200);
        assertTrue(clear.isAlive());
        release.countDown();
        clear.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(clear.isAlive());
        assertTrue(cleared.get());
        assertTrue(db.getAll().isEmpty());
        assertFalse(db.has("1"));
    }
}
