package app.morphe.extension.instagram.patches.dm.vault;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.crypto.spec.SecretKeySpec;

import app.morphe.extension.instagram.db.SpookdMessageDb;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class MediaVaultLifecycleTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long CAP = MediaVault.CAP_BYTES;
    private static final VaultFile.Kek KEK = () -> new SecretKeySpec(new byte[32], "AES");
    private static final String LIVE = url(NOW + 3_600_000);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final long[] clock = {NOW};
    private SpookdMessageDb messages;
    private SQLiteDatabase db;
    private File dir;
    private FakeNetwork network;
    private ManualExecutor executor;
    private MediaVault vault;

    @Before
    public void setUp() throws Exception {
        // A fresh helper per test; the singleton would keep an earlier test's context.
        Constructor<SpookdMessageDb> constructor = SpookdMessageDb.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        messages = constructor.newInstance(RuntimeEnvironment.getApplication());
        db = messages.getWritableDatabase();
        dir = tmp.newFolder("piko_vault");
        network = new FakeNetwork();
        restart();
    }

    @After
    public void tearDown() {
        messages.close();
    }

    /** A crash: the old instance is dropped and a new one works on the same directory and database. */
    private void restart() {
        restart(KEK);
    }

    private void restart(VaultFile.Kek kek) {
        executor = new ManualExecutor();
        vault = new MediaVault(new VaultStore(db), network, () -> clock[0], kek, dir, executor);
    }

    @Test
    public void fetchWaitingForRecoveryIsNotScheduledAgainByIt() throws Exception {
        long crashed = queuedWithFile("crashed");
        CountDownLatch inRecovery = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        restart(heldKek(inRecovery, release, false));
        network.respond = url -> new FakeConnection(url, 503, new byte[0]);
        Throwable[] failures = new Throwable[2];
        Thread recovery = thread(vault::recover, failures, 0);
        Thread fetcher = null;
        try {
            recovery.start();
            assertTrue(inRecovery.await(10, TimeUnit.SECONDS));
            long id = enqueue("new");
            // Recovery, retention, then the new row's fetch, which runs on a second thread.
            assertEquals(Arrays.asList(0L, 30_000L, 0L), executor.delays);
            executor.delays.remove(2);
            fetcher = thread(executor.tasks.remove(2), failures, 1);
            fetcher.start();
            waitUntilBlocked(fetcher);
            release.countDown();
            recovery.join(10_000);
            fetcher.join(10_000);
            assertFalse(recovery.isAlive());
            assertFalse(fetcher.isAlive());
            assertNull(failures[0]);
            assertNull(failures[1]);

            assertEquals("stored", column("state", crashed));
            assertEquals("1", column("attempts", id));
            assertEquals("queued", column("state", id));
            assertEquals(1, network.opened.size());
            // No second fetch for the row: only its 30 s retry is left besides the start tasks.
            assertEquals(Arrays.asList(0L, 30_000L, 30_000L), executor.delays);
        } finally {
            finish(release, recovery, fetcher);
        }
    }

    @Test
    public void fetchWhoseRecoveryFailsIsScheduledByTheNextRecovery() throws Exception {
        long crashed = queuedWithFile("crashed");
        CountDownLatch inRecovery = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        restart(heldKek(inRecovery, release, true));
        long id = enqueue("new");
        assertEquals(Arrays.asList(0L, 30_000L, 0L), executor.delays);
        executor.delays.remove(2);
        Throwable[] failures = new Throwable[2];
        Thread fetcher = thread(executor.tasks.remove(2), failures, 0);
        Thread recovery = thread(vault::recover, failures, 1);
        try {
            // The fetch task's recovery holds the key, then fails; the other recovery waits for it.
            fetcher.start();
            assertTrue(inRecovery.await(10, TimeUnit.SECONDS));
            recovery.start();
            waitUntilBlocked(recovery);
            release.countDown();
            fetcher.join(10_000);
            recovery.join(10_000);
            assertFalse(fetcher.isAlive());
            assertFalse(recovery.isAlive());
        } finally {
            finish(release, fetcher, recovery);
        }
        // The fetch task logs its own failure; the second recovery succeeds.
        assertNull(failures[0]);
        assertNull(failures[1]);
        assertEquals("stored", column("state", crashed));
        assertEquals("queued", column("state", id));
        assertTrue(network.opened.isEmpty());

        // The second recovery scheduled the row again, so it is fetched.
        executor.runAll();
        assertEquals("stored", column("state", id));
        assertEquals("1", column("attempts", id));
    }

    @Test
    public void deleteDuringRecoveryKeepsAnUndeletableFileCounted() throws Exception {
        long crashed = queuedWithFile("crashed");
        long stored = storedRow("stored", 5000, NOW - 1000);
        unsend("stored");
        // A non-empty directory under the file's name cannot be unlinked.
        assertTrue(pkv(stored).delete());
        assertTrue(pkv(stored).mkdir());
        assertTrue(new File(pkv(stored), "x").createNewFile());
        CountDownLatch inRecovery = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        restart(heldKek(inRecovery, release, false));
        Throwable[] failures = new Throwable[2];
        Thread recovery = thread(vault::recover, failures, 0);
        Thread delete = thread(() -> vault.deleteForMessages(Collections.singletonList("stored")),
                failures, 1);
        try {
            // Recovery is held at the key, before its scan, while the user deletes the stored item.
            recovery.start();
            assertTrue(inRecovery.await(10, TimeUnit.SECONDS));
            delete.start();
            waitUntilBlocked(delete);
            release.countDown();
            recovery.join(10_000);
            delete.join(10_000);
            assertFalse(recovery.isAlive());
            assertFalse(delete.isAlive());
        } finally {
            finish(release, recovery, delete);
        }
        assertNull(failures[0]);
        assertNull(failures[1]);
        assertEquals("dropped", column("state", stored));
        assertTrue(pkv(stored).exists());
        assertEquals(5000 + pkv(crashed).length(), vault.storedBytes);
    }

    @Test
    public void failedPublicationDeletesTheFileAndReleasesTheReservation() {
        db.execSQL("CREATE TRIGGER reject_publish BEFORE UPDATE OF state ON saved_media"
                + " WHEN NEW.state = 'stored' BEGIN SELECT RAISE(ABORT, 'rejected'); END");
        network.respond = url -> new FakeConnection(url, 200, body(100_000));
        long id = enqueue("1");
        executor.runAll();

        assertEquals(1, network.opened.size());
        assertEquals("queued", column("state", id));
        assertEquals("1", column("attempts", id));
        assertEquals(0, dir.list().length);
        assertEquals(0, vault.storedBytes);
        assertEquals(0, vault.reservations);
    }

    /** A queued row with the complete file a crash after the rename leaves; recovery publishes it. */
    private long queuedWithFile(String itemId) throws IOException {
        long id = new VaultStore(db).insertQueued(itemId, "acct", "photo", LIVE, NOW + 3_600_000, NOW - 1000, null);
        writePkv(pkv(id), body(1000));
        return id;
    }

    /** The test key; its first use signals {@code reached}, waits for {@code release}, then fails if asked. */
    private static VaultFile.Kek heldKek(CountDownLatch reached, CountDownLatch release, boolean failFirst) {
        AtomicBoolean first = new AtomicBoolean(true);
        return () -> {
            if (first.getAndSet(false)) {
                reached.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new GeneralSecurityException("never released");
                } catch (InterruptedException e) {
                    throw new GeneralSecurityException(e);
                }
                if (failFirst) throw new IllegalStateException("recovery fails once");
            }
            return KEK.get();
        };
    }

    private static Thread thread(Runnable body, Throwable[] failures, int slot) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failures[slot] = t;
            }
        });
        thread.setDaemon(true);
        return thread;
    }

    /** Releases the held key and stops the workers, so a failed assertion leaves no live thread. */
    private static void finish(CountDownLatch release, Thread... threads) throws InterruptedException {
        release.countDown();
        for (Thread thread : threads) {
            if (thread == null) continue;
            thread.join(10_000);
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(1_000);
            }
        }
    }

    private static void waitUntilBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertTrue("thread never blocked", System.nanoTime() < deadline);
            Thread.sleep(1);
        }
    }

    @Test
    public void storesEncryptedFileWithoutCredentialsThenDeletesIt() throws IOException {
        byte[] body = body(100_000);
        network.respond = url -> new FakeConnection(url, 200, body);
        long id = enqueue("100");
        executor.runAll();

        long size = VaultFile.fileSize(body.length);
        assertEquals("stored", column("state", id));
        assertNull(column("url", id));
        assertEquals("image/jpeg", column("mime", id));
        assertEquals("1", column("attempts", id));
        assertEquals(String.valueOf(size), column("size_bytes", id));
        assertEquals(size, pkv(id).length());
        assertFalse(part(id).exists());
        assertEquals(size, vault.storedBytes);
        assertEquals(0, vault.reservations);
        try (VaultFile file = VaultFile.open(pkv(id), KEK)) {
            byte[] plain = new byte[body.length];
            assertEquals(body.length, file.readAt(0, plain, 0, plain.length));
            assertArrayEquals(body, plain);
        }
        FakeConnection connection = network.only();
        assertEquals(LIVE, connection.getURL().toString());
        assertFalse(connection.getInstanceFollowRedirects());
        assertFalse(connection.getUseCaches());
        assertTrue(connection.getRequestProperties().isEmpty());
        assertTrue(connection.disconnected);

        assertTrue(vault.storedMessages().isEmpty());
        unsend("100");
        assertEquals(Collections.singleton("100"), vault.storedMessages().keySet());

        vault.deleteForMessages(Collections.singletonList("100"));
        assertEquals("dropped", column("state", id));
        assertFalse(pkv(id).exists());
        assertEquals(0, vault.storedBytes);
        assertTrue(vault.storedMessages().isEmpty());

        // The tombstone blocks a second capture.
        vault.enqueue("100", "acct", "photo", LIVE, NOW - 1000, null);
        assertEquals(1, count());
        assertEquals("dropped", column("state", id));
    }

    @Test
    public void opensOnlyStoredMediaOfAMessageWithItsKind() throws IOException {
        byte[] body = body(100_000);
        network.respond = url -> new FakeConnection(url, 200, body);
        vault.enqueue("stored", "acct", "video", LIVE, NOW - 1000, null);
        enqueue("dropped");
        executor.runAll();
        enqueue("queued");
        for (String item : new String[]{"stored", "dropped", "queued"}) unsend(item);
        vault.deleteForMessages(Collections.singletonList("dropped"));

        MediaVault.Stored stored = vault.openForMessage("stored");
        assertNotNull(stored);
        try {
            assertEquals("video", stored.kind);
            byte[] plain = new byte[body.length];
            assertEquals(body.length, stored.file.readAt(0, plain, 0, plain.length));
            assertArrayEquals(body, plain);
        } finally {
            stored.file.close();
        }
        assertEquals("queued", column("state", id("queued")));
        assertNull(vault.openForMessage("queued"));
        assertEquals("dropped", column("state", id("dropped")));
        assertNull(vault.openForMessage("dropped"));
        assertNull(vault.openForMessage("unknown"));
    }

    @Test
    public void listsEveryStoredItemWithItsSenderNewestFirst() throws IOException {
        long older = storedRow("older", 1000, NOW - 2000);
        long dropped = storedRow("dropped", 3000, NOW - 500);
        assertEquals(1, vault.store.drop(dropped));
        long fetched = enqueue("fetched");
        executor.runAll();
        enqueue("queued");
        unsend("fetched");

        List<MediaVault.Item> items = vault.storedItems();
        assertEquals(2, items.size());
        MediaVault.Item kept = items.get(0);
        assertEquals(fetched, kept.id);
        assertEquals("photo", kept.kind);
        assertEquals("image/jpeg", kept.mime);
        assertEquals(VaultFile.fileSize(1000), kept.sizeBytes);
        assertEquals(NOW - 1000, kept.msgTimestamp);
        assertEquals("acct", kept.accountId);
        assertEquals("bob", kept.senderUsername);
        assertEquals("42", kept.senderId);
        assertEquals("fetched", kept.itemId);
        MediaVault.Item unkept = items.get(1);
        assertEquals(older, unkept.id);
        assertEquals(1000, unkept.sizeBytes);
        assertEquals(NOW - 2000, unkept.msgTimestamp);
        assertNull(unkept.accountId);
        assertNull(unkept.senderUsername);
        assertNull(unkept.senderId);
        assertEquals("older", unkept.itemId);
    }

    @Test
    public void opensAndDeletesByRowIdKeepingTheAccounting() throws IOException {
        long other = storedRow("other", 5000, NOW - 2000);
        byte[] body = body(100_000);
        network.respond = url -> new FakeConnection(url, 200, body);
        long id = enqueue("1");
        executor.runAll();
        unsend("1");
        assertEquals(5000 + VaultFile.fileSize(body.length), vault.storedBytes);

        MediaVault.Stored stored = vault.open(id);
        assertNotNull(stored);
        try {
            assertEquals("photo", stored.kind);
            byte[] plain = new byte[body.length];
            assertEquals(body.length, stored.file.readAt(0, plain, 0, plain.length));
            assertArrayEquals(body, plain);
        } finally {
            stored.file.close();
        }

        vault.delete(id);
        assertEquals("dropped", column("state", id));
        assertEquals("0", column("size_bytes", id));
        assertFalse(pkv(id).exists());
        assertEquals(5000, vault.storedBytes);
        assertNull(vault.open(id));
        assertTrue(vault.storedMessages().isEmpty());
        assertEquals(1, vault.storedItems().size());
        assertEquals(other, vault.storedItems().get(0).id);

        // A second delete and an unknown id change nothing.
        vault.delete(id);
        vault.delete(999);
        assertEquals(5000, vault.storedBytes);
        assertTrue(pkv(other).exists());

        // A queued row is dropped before its fetch runs.
        long queued = enqueue("queued");
        vault.delete(queued);
        executor.runAll();
        assertEquals("dropped", column("state", queued));
        assertEquals(1, network.opened.size());
        assertEquals(5000, vault.storedBytes);
        assertEquals(0, vault.reservations);
    }

    @Test
    public void offlineWaitsWithoutCountingAndServerErrorsBackOff() {
        network.online = false;
        long id = enqueue("1");
        executor.run(3);
        assertEquals("0", column("attempts", id));
        assertEquals(Collections.singletonList(MediaVault.MINUTE_MS), executor.delays);
        assertTrue(network.opened.isEmpty());

        network.online = true;
        network.respond = url -> new FakeConnection(url, 503, new byte[0]);
        executor.run(1);
        assertEquals("1", column("attempts", id));
        assertEquals(Collections.singletonList(30_000L), executor.delays);
        executor.run(1);
        assertEquals("2", column("attempts", id));
        assertEquals(Collections.singletonList(60_000L), executor.delays);
        assertEquals("queued", column("state", id));
    }

    @Test
    public void refusesDisallowedUrlsBeforeConnecting() {
        String path = "/v/a.jpg?oe=" + Long.toHexString((NOW + 3_600_000) / 1000);
        String[] urls = {
                "http://scontent.cdninstagram.com" + path,
                "https://example.com" + path,
                "https://scontent.fbcdn.net.example.com" + path,
                "https://user@scontent.fbcdn.net" + path,
                "https://scontent.fbcdn.net:8443" + path,
        };
        for (int i = 0; i < urls.length; i++) {
            vault.enqueue("item" + i, "acct", "photo", urls[i], NOW - 1000, null);
        }
        executor.runAll();

        assertEquals(urls.length, count());
        for (int i = 0; i < urls.length; i++) {
            long id = id("item" + i);
            assertEquals(urls[i], "failed", column("state", id));
            assertNull(column("url", id));
        }
        assertTrue(network.opened.isEmpty());
        assertNotNull(MediaVault.allowedUrl("https://scontent.fbcdn.net:443" + path));
        assertNotNull(MediaVault.allowedUrl("https://cdninstagram.com" + path));
    }

    @Test
    public void redirectFailsWithoutBeingFollowed() {
        network.respond = url -> new FakeConnection(url, 302, new byte[0]);
        long id = enqueue("1");
        executor.runAll();

        assertEquals("failed", column("state", id));
        assertNull(column("url", id));
        assertFalse(network.only().getInstanceFollowRedirects());
        assertFalse(pkv(id).exists());
    }

    @Test
    public void refusesToFetchWhileADefaultCookieHandlerIsSet() {
        CookieHandler.setDefault(new CookieManager());
        try {
            long id = enqueue("1");
            executor.runAll();
            assertEquals("queued", column("state", id));
            assertEquals("0", column("attempts", id));
            assertTrue(network.opened.isEmpty());
        } finally {
            CookieHandler.setDefault(null);
        }
    }

    @Test
    public void expiredLinkIsNeverRequested() {
        vault.enqueue("expired", "acct", "photo", url(NOW - 1000), NOW - 1000, null);
        vault.enqueue("ancient", "acct", "photo", LIVE, NOW - MediaVault.RETENTION_MS - 1, null);
        assertEquals(0, count());

        vault.enqueue("soon", "acct", "photo", url(NOW + 600_000), NOW - 1000, null);
        long id = id("soon");
        clock[0] = NOW + 600_000;
        executor.runAll();

        assertEquals("failed", column("state", id));
        assertEquals("0", column("attempts", id));
        assertTrue(network.opened.isEmpty());
    }

    @Test
    public void crashAfterAttemptIncrementGivesNoExtraAttempt() {
        long used = enqueue("used");
        long fresh = enqueue("fresh");
        db.execSQL("UPDATE saved_media SET attempts = 4 WHERE id = ?", new Object[]{used});
        assertTrue(vault.store.countAttempt(used, NOW + MediaVault.MINUTE_MS, NOW));
        assertTrue(vault.store.countAttempt(fresh, NOW + MediaVault.MINUTE_MS, NOW));

        restart();
        vault.recover();
        executor.runAll();

        assertEquals("failed", column("state", used));
        assertEquals("5", column("attempts", used));
        assertEquals("stored", column("state", fresh));
        assertEquals("2", column("attempts", fresh));
        assertEquals(1, network.opened.size());
    }

    @Test
    public void crashDuringStreamingLeavesAPartFileThatRecoveryDeletes() throws IOException {
        long id = enqueue("1");
        assertTrue(vault.store.countAttempt(id, NOW + MediaVault.MINUTE_MS, NOW));
        Files.write(part(id).toPath(), new byte[1000]);

        restart();
        vault.recover();
        assertFalse(part(id).exists());
        assertEquals("queued", column("state", id));
        assertEquals("1", column("attempts", id));
        assertEquals(0, vault.storedBytes);
        assertEquals(0, vault.reservations);

        executor.runAll();
        assertEquals("stored", column("state", id));
        assertEquals("2", column("attempts", id));
    }

    @Test
    public void crashAfterRenameBeforeCommitPublishesValidFileAndDeletesCorruptOne() throws IOException {
        byte[] body = body(5000);
        long valid = enqueue("valid");
        long corrupt = enqueue("corrupt");
        writePkv(pkv(valid), body);
        writePkv(pkv(corrupt), body);
        byte[] bytes = Files.readAllBytes(pkv(corrupt).toPath());
        bytes[bytes.length - 1] ^= 1;
        Files.write(pkv(corrupt).toPath(), bytes);

        restart();
        vault.recover();

        assertEquals("stored", column("state", valid));
        assertNull(column("url", valid));
        assertEquals(String.valueOf(pkv(valid).length()), column("size_bytes", valid));
        assertEquals(pkv(valid).length(), vault.storedBytes);
        assertFalse(pkv(corrupt).exists());
        assertEquals("queued", column("state", corrupt));
        assertTrue(network.opened.isEmpty());
    }

    @Test
    public void crashAfterCommitBeforeUnlinkDeletesTheFile() throws IOException {
        long evicted = storedRow("evicted", 1000, NOW - 1000);
        long deleted = storedRow("deleted", 2000, NOW - 1000);
        long lost = storedRow("lost", 3000, NOW - 1000);
        long kept = storedRow("kept", 4000, NOW - 1000);
        assertTrue(pkv(lost).delete());
        File orphan = new File(dir, "999.pkv");
        assertTrue(orphan.createNewFile());
        assertEquals(1, vault.store.dropStored(evicted, Long.MAX_VALUE));
        assertEquals(1, vault.store.drop(deleted));

        restart();
        vault.recover();

        assertFalse(pkv(evicted).exists());
        assertFalse(pkv(deleted).exists());
        assertFalse(orphan.exists());
        assertEquals("failed", column("state", lost));
        assertEquals("0", column("size_bytes", lost));
        assertTrue(pkv(kept).exists());
        assertEquals("stored", column("state", kept));
        assertEquals(4000, vault.storedBytes);
    }

    @Test
    public void unsendBetweenEvictionSelectionAndStatementKeepsTheFile() throws IOException {
        long id = storedRow("1", 1000, NOW - 1000);
        vault.recover();
        List<long[]> candidates = vault.store.evictionCandidates(Long.MAX_VALUE);
        assertEquals(1, candidates.size());
        assertEquals(id, candidates.get(0)[0]);

        unsend("1");
        assertFalse(vault.dropStored(id, 1000, Long.MAX_VALUE));
        assertTrue(pkv(id).exists());
        assertEquals("stored", column("state", id));
        assertEquals(1000, vault.storedBytes);
    }

    @Test
    public void userDeleteDuringFetchKeepsTheRowDroppedAndDiscardsOutput() {
        for (boolean honorDisconnect : new boolean[]{true, false}) {
            String item = "item-" + honorDisconnect;
            unsend(item);
            network.respond = url -> {
                FakeConnection connection = new FakeConnection(url, 200, body(100_000));
                connection.honorDisconnect = honorDisconnect;
                connection.midStream = () -> vault.deleteForMessages(Collections.singletonList(item));
                return connection;
            };
            long id = enqueue(item);
            executor.runAll();

            assertEquals(item, "dropped", column("state", id));
            assertTrue(network.opened.get(network.opened.size() - 1).disconnected);
            assertFalse(pkv(id).exists());
            assertFalse(part(id).exists());
        }
        assertEquals(2, network.opened.size());
        assertEquals(0, vault.storedBytes);
        assertEquals(0, vault.reservations);
        assertEquals(0, dir.list().length);
    }

    @Test
    public void reservationsKeepTwoWritersNearTheCapFromOvercommitting() throws IOException {
        byte[] body = body(100_000);
        long need = VaultFile.fileSize(body.length);
        storedRow("kept", CAP - need - need / 2, NOW - 1000);
        unsend("kept");
        vault.recover();
        long[] seen = new long[2];
        network.respond = url -> {
            FakeConnection connection = new FakeConnection(url, 200, body);
            if (network.opened.isEmpty()) {
                // The second writer runs while the first one streams.
                connection.midStream = () -> {
                    seen[0] = vault.reservations;
                    executor.runAll();
                    seen[1] = vault.reservations;
                };
            }
            return connection;
        };
        long first = enqueue("first");
        long second = enqueue("second");
        executor.runAll();

        assertEquals(need, seen[0]);
        assertEquals(need, seen[1]);
        assertEquals("stored", column("state", first));
        assertEquals("failed", column("state", second));
        assertEquals(2, network.opened.size());
        assertEquals(CAP - need / 2, vault.storedBytes);
        assertEquals(0, vault.reservations);
    }

    @Test
    public void unknownLengthPastFreeSpaceAbortsWithoutEviction() throws IOException {
        long old = storedRow("old", CAP - MediaVault.RESERVE_STEP - 1000, NOW - 1000);
        vault.recover();
        network.respond = url -> {
            FakeConnection connection = new FakeConnection(url, 200, body((int) MediaVault.RESERVE_STEP + 1));
            connection.declared = -1;
            return connection;
        };
        long id = enqueue("unknown");
        executor.run(3);

        assertEquals("stored", column("state", old));
        assertTrue(pkv(old).exists());
        assertEquals("queued", column("state", id));
        assertEquals("1", column("attempts", id));
        assertFalse(part(id).exists());
        assertFalse(pkv(id).exists());
        assertEquals(CAP - MediaVault.RESERVE_STEP - 1000, vault.storedBytes);
        assertEquals(0, vault.reservations);
        assertEquals(1, executor.tasks.size());
    }

    @Test
    public void wrongContentLengthIsRetriedAndNeverStored() {
        for (int[] lengths : new int[][]{{2000, 1000}, {1000, 2000}}) {
            String item = "declared-" + lengths[0];
            network.respond = url -> {
                FakeConnection connection = new FakeConnection(url, 200, body(lengths[1]));
                connection.declared = lengths[0];
                return connection;
            };
            int before = network.opened.size();
            long id = enqueue(item);
            executor.runAll();

            assertEquals(item, "failed", column("state", id));
            assertEquals(String.valueOf(MediaVault.MAX_ATTEMPTS), column("attempts", id));
            assertEquals(MediaVault.MAX_ATTEMPTS, network.opened.size() - before);
            assertFalse(pkv(id).exists());
            assertFalse(part(id).exists());
        }
        assertEquals(0, vault.storedBytes);
        assertEquals(0, vault.reservations);
    }

    @Test
    public void itemLargerThanFreeableFailsWithoutEviction() throws IOException {
        storedRow("kept", CAP - 50_000, NOW - 1000);
        unsend("kept");
        long old = storedRow("old", 10_000, NOW - 2000);
        vault.recover();
        network.respond = url -> new FakeConnection(url, 200, body(100_000));
        long id = enqueue("big");
        executor.runAll();

        assertEquals("failed", column("state", id));
        assertEquals("stored", column("state", old));
        assertTrue(pkv(old).exists());
        assertEquals(1, network.opened.size());
        assertEquals(CAP - 40_000, vault.storedBytes);
        assertEquals(0, vault.reservations);
    }

    @Test
    public void evictsOldestUnkeptMediaUntilTheItemFits() throws IOException {
        storedRow("kept", CAP - 150_000, NOW - 1000);
        unsend("kept");
        long oldest = storedRow("oldest", 60_000, NOW - 3000);
        long newer = storedRow("newer", 30_000, NOW - 2000);
        vault.recover();
        network.respond = url -> new FakeConnection(url, 200, body(100_000));
        long id = enqueue("new");
        executor.runAll();

        assertEquals("stored", column("state", id));
        assertEquals("dropped", column("state", oldest));
        assertFalse(pkv(oldest).exists());
        assertEquals("stored", column("state", newer));
        assertTrue(pkv(newer).exists());
        assertEquals(CAP - 120_000 + VaultFile.fileSize(100_000), vault.storedBytes);
    }

    @Test
    public void ordinaryAndMigratedMediaWithoutAModeIsSweptAndEvicted() throws Exception {
        // A version 5 saved_media table, upgraded to version 6 as an app update does.
        db.execSQL("DROP TABLE saved_media");
        java.lang.reflect.Method v5 = SpookdMessageDb.class.getDeclaredMethod("savedMediaStatements");
        v5.setAccessible(true);
        for (String statement : (String[]) v5.invoke(null)) db.execSQL(statement);
        long migratedOld = storedRow("migratedOld", 1000, NOW - MediaVault.RETENTION_MS - 1);
        long migrated = storedRow("migrated", 60_000, NOW - 3000);
        db.setVersion(5);
        messages.close();
        Constructor<SpookdMessageDb> constructor = SpookdMessageDb.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        messages = constructor.newInstance(RuntimeEnvironment.getApplication());
        db = messages.getWritableDatabase();
        assertEquals(6, db.getVersion());
        assertNull(column("view_mode", migrated));
        restart();

        long ordinaryOld = storedRow("ordinaryOld", 1000, NOW - MediaVault.RETENTION_MS - 1);
        long ordinary = storedRow("ordinary", 60_000, NOW - 2500);
        storedRow("kept", CAP - 150_000, NOW - 1000);
        unsend("kept");
        vault.recover();
        vault.sweep();
        assertEquals(0, count("migratedOld"));
        assertEquals(0, count("ordinaryOld"));
        assertFalse(pkv(migratedOld).exists());
        assertFalse(pkv(ordinaryOld).exists());

        network.respond = url -> new FakeConnection(url, 200, body(100_000));
        long id = enqueue("new");
        executor.runAll();
        assertEquals("stored", column("state", id));
        assertEquals("dropped", column("state", migrated));
        assertEquals("dropped", column("state", ordinary));
        assertFalse(pkv(migrated).exists());
        assertFalse(pkv(ordinary).exists());
        assertEquals(CAP - 150_000 + VaultFile.fileSize(100_000), vault.storedBytes);
    }

    @Test
    public void viewOnceMediaIsNeverEvictedButFillsTheCap() throws IOException {
        long once = storedRow("once", CAP - 150_000, NOW - 4000);
        long twice = storedRow("twice", 10_000, NOW - 3500);
        long oldest = storedRow("oldest", 60_000, NOW - 3000);
        viewMode(once, "once");
        viewMode(twice, "replayable");
        vault.recover();
        network.respond = url -> new FakeConnection(url, 200, body(100_000));
        long fits = enqueue("fits");
        executor.runAll();

        assertEquals("stored", column("state", fits));
        assertEquals("dropped", column("state", oldest));
        assertEquals("stored", column("state", once));
        assertEquals("stored", column("state", twice));
        assertTrue(pkv(once).exists());
        assertTrue(pkv(twice).exists());
        assertEquals(CAP - 140_000 + VaultFile.fileSize(100_000), vault.storedBytes);

        // Only view-once media and the new item are left, so the next item cannot make room.
        viewMode(fits, "once");
        long big = enqueue("big");
        executor.runAll();
        assertEquals("failed", column("state", big));
        assertEquals("stored", column("state", fits));
        assertEquals(CAP - 140_000 + VaultFile.fileSize(100_000), vault.storedBytes);
        assertEquals(0, vault.reservations);
    }

    @Test
    public void retentionKeepsViewOnceMediaAndStoresItsMode() throws IOException {
        long tenDaysAgo = NOW - TimeUnit.DAYS.toMillis(10);
        long once = storedRow("once", 1000, tenDaysAgo);
        long twice = storedRow("twice", 2000, tenDaysAgo + 1);
        long other = storedRow("other", 4000, tenDaysAgo);
        viewMode(once, "once");
        viewMode(twice, "replayable");
        viewMode(other, "keep_in_chat");
        vault.recover();
        long queued = new VaultStore(db).insertQueued("queued", "acct", "photo", LIVE, NOW + 3_600_000,
                tenDaysAgo, "once");
        vault.enqueue("new", "acct", "voice", LIVE, NOW - 1000, "replayable");
        assertEquals("replayable", column("view_mode", id("new")));

        clock[0] = NOW + MediaVault.RETENTION_MS;
        vault.sweep();

        assertEquals("stored", column("state", once));
        assertEquals("stored", column("state", twice));
        assertTrue(pkv(once).exists());
        assertTrue(pkv(twice).exists());
        assertEquals("queued", column("state", queued));
        assertEquals(0, count("other"));
        assertFalse(pkv(other).exists());
        assertEquals(3000, vault.storedBytes);
        List<MediaVault.Item> items = vault.storedItems();
        assertEquals(2, items.size());
        assertEquals("replayable", items.get(0).viewMode);
        assertEquals("once", items.get(1).viewMode);

        // A user delete still removes it.
        vault.delete(once);
        assertEquals("dropped", column("state", once));
        assertFalse(pkv(once).exists());
        assertEquals(2000, vault.storedBytes);
    }

    @Test
    public void deletedListShowsUnsentAndStoredViewOnceMessagesOnce() throws IOException {
        for (String item : new String[]{"once", "twice", "queuedOnce", "keep", "plain", "droppedOnce"}) {
            messages.upsertMessage(item, null, "thread", "42", "bob", "", "visual_media", NOW - 1000);
        }
        unsend("unsent");
        unsend("unsentOnce");
        viewMode(storedRow("once", 1000, NOW - 1000), "once");
        viewMode(storedRow("twice", 1000, NOW - 1000), "replayable");
        viewMode(storedRow("unsentOnce", 1000, NOW - 1000), "once");
        viewMode(storedRow("keep", 1000, NOW - 1000), "keep_in_chat");
        long dropped = storedRow("droppedOnce", 1000, NOW - 1000);
        viewMode(dropped, "once");
        new VaultStore(db).insertQueued("queuedOnce", "acct", "photo", LIVE, NOW + 3_600_000, NOW - 1000, "once");
        vault.recover();
        vault.delete(dropped);

        List<String> listed = new ArrayList<>();
        for (String[] row : messages.getDeletedMessages()) listed.add(row[0]);
        Collections.sort(listed);
        assertEquals(Arrays.asList("once", "twice", "unsent", "unsentOnce"), listed);

        java.util.Map<String, String> stored = vault.storedMessages();
        assertEquals(3, stored.size());
        assertEquals("once", stored.get("once"));
        assertEquals("replayable", stored.get("twice"));
        assertEquals("once", stored.get("unsentOnce"));
        assertEquals("spookd_media_view_once", MediaVault.markResource(stored.get("once")));
        assertEquals("spookd_media_view_twice", MediaVault.markResource(stored.get("twice")));
        assertNull(MediaVault.markResource("keep_in_chat"));
        assertNull(MediaVault.markResource(null));
    }

    @Test
    public void retentionSweepDuringAWriteLeavesItsPartFile() throws IOException {
        long tenDaysAgo = NOW - TimeUnit.DAYS.toMillis(10);
        long kept = storedRow("kept", 1000, tenDaysAgo);
        unsend("kept");
        long old = storedRow("old", 2000, tenDaysAgo);
        vault.recover();
        long[] writer = new long[1];
        boolean[] partSeen = new boolean[2];
        network.respond = url -> {
            FakeConnection connection = new FakeConnection(url, 200, body(100_000));
            connection.midStream = () -> {
                partSeen[0] = part(writer[0]).exists();
                clock[0] = NOW + MediaVault.RETENTION_MS;
                vault.sweep();
                partSeen[1] = part(writer[0]).exists();
            };
            return connection;
        };
        writer[0] = enqueue("writer");
        executor.runAll();

        assertTrue(partSeen[0]);
        assertTrue(partSeen[1]);
        // The sweep dropped the queued row and then deleted it as an old tombstone.
        assertEquals(0, count("writer"));
        assertFalse(pkv(writer[0]).exists());
        assertFalse(part(writer[0]).exists());
        assertEquals(0, count("old"));
        assertFalse(pkv(old).exists());
        assertEquals("stored", column("state", kept));
        assertTrue(pkv(kept).exists());
        assertEquals(1000, vault.storedBytes);
        assertEquals(0, vault.reservations);
    }

    @Test
    public void retentionSweepKeepsTheQueuedDownloadOfAnUnsentMessage() {
        unsend("writer");
        network.respond = url -> {
            FakeConnection connection = new FakeConnection(url, 200, body(100_000));
            connection.midStream = () -> {
                clock[0] = NOW + MediaVault.RETENTION_MS;
                vault.sweep();
            };
            return connection;
        };
        long id = enqueue("writer");
        executor.runAll();

        assertEquals("stored", column("state", id));
        assertTrue(pkv(id).exists());
        assertEquals(VaultFile.fileSize(100_000), vault.storedBytes);
    }

    @Test
    public void deleteBeforeTheRequestStopsTheDownloadWithoutEviction() throws IOException {
        long old = storedRow("old", CAP - 50_000, NOW - 2000);
        vault.recover();
        unsend("deleted");
        network.respond = url -> {
            // The attempt is already counted; the delete lands before the request is sent.
            vault.deleteForMessages(Collections.singletonList("deleted"));
            return new FakeConnection(url, 200, body(100_000));
        };
        long id = enqueue("deleted");
        executor.runAll();

        assertEquals("dropped", column("state", id));
        assertEquals("1", column("attempts", id));
        assertFalse(network.only().requested);
        assertEquals("stored", column("state", old));
        assertTrue(pkv(old).exists());
        assertFalse(pkv(id).exists());
        assertFalse(part(id).exists());
        assertEquals(CAP - 50_000, vault.storedBytes);
        assertEquals(0, vault.reservations);
    }

    @Test
    public void unavailableKeyKeepsEveryFileUntilARecoveryWithTheKey() throws IOException {
        long crashed = queuedWithFile("crashed");
        File leftover = new File(dir, "777.part");
        assertTrue(leftover.createNewFile());
        AtomicBoolean failedOnce = new AtomicBoolean();
        restart(() -> {
            if (!failedOnce.getAndSet(true)) throw new GeneralSecurityException("keystore unavailable");
            return KEK.get();
        });

        assertThrows(IllegalStateException.class, vault::recover);
        assertTrue(pkv(crashed).exists());
        assertTrue(leftover.exists());
        assertEquals("queued", column("state", crashed));

        // Recovery is not marked done, so the next task's recovery runs and publishes the file.
        vault.recover();
        assertEquals("stored", column("state", crashed));
        assertEquals(String.valueOf(pkv(crashed).length()), column("size_bytes", crashed));
        assertFalse(leftover.exists());
        assertEquals(pkv(crashed).length(), vault.storedBytes);
    }

    private long enqueue(String itemId) {
        vault.enqueue(itemId, "acct", "photo", LIVE, NOW - 1000, null);
        return id(itemId);
    }

    private long id(String itemId) {
        return DatabaseUtils.longForQuery(db, "SELECT id FROM saved_media WHERE item_id = ?",
                new String[]{itemId});
    }

    private String column(String column, long id) {
        return DatabaseUtils.stringForQuery(db, "SELECT " + column + " FROM saved_media WHERE id = ?",
                new String[]{String.valueOf(id)});
    }

    private long count() {
        return DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM saved_media", null);
    }

    private long count(String itemId) {
        return DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM saved_media WHERE item_id = ?",
                new String[]{itemId});
    }

    /** A stored row with a placeholder file; recovery keeps stored files without reading them. */
    private long storedRow(String itemId, long size, long msgTimestamp) throws IOException {
        ContentValues values = new ContentValues();
        values.put("item_id", itemId);
        values.put("kind", "photo");
        values.put("url_expires_at", 0L);
        values.put("msg_timestamp", msgTimestamp);
        values.put("state", "stored");
        values.put("size_bytes", size);
        long id = db.insertOrThrow("saved_media", null, values);
        assertTrue(pkv(id).createNewFile());
        return id;
    }

    private void viewMode(long id, String mode) {
        db.execSQL("UPDATE saved_media SET view_mode = ? WHERE id = ?", new Object[]{mode, id});
    }

    private void unsend(String itemId) {
        messages.upsertMessage(itemId, null, "thread", "42", "bob", "", "media", NOW - 1000);
        assertTrue(messages.markDeleted(itemId));
    }

    private File pkv(long id) {
        return new File(dir, id + ".pkv");
    }

    private File part(long id) {
        return new File(dir, id + ".part");
    }

    private static String url(long expiresAtMs) {
        return "https://scontent-lhr8-1.cdninstagram.com/v/t51/a.jpg?stp=dst&oe="
                + Long.toHexString(expiresAtMs / 1000) + "&_nc_sid=1";
    }

    private static byte[] body(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + 7);
        return data;
    }

    private static void writePkv(File file, byte[] plain) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            VaultFile.Writer writer = new VaultFile.Writer(out, KEK);
            writer.write(plain, 0, plain.length);
            writer.finish();
        }
    }

    private static final class FakeNetwork implements MediaVault.ConnectionOpener {
        boolean online = true;
        final List<FakeConnection> opened = new ArrayList<>();
        Function<URL, FakeConnection> respond = url -> new FakeConnection(url, 200, body(1000));

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public HttpURLConnection open(URL url) {
            FakeConnection connection = respond.apply(url);
            opened.add(connection);
            return connection;
        }

        FakeConnection only() {
            assertEquals(1, opened.size());
            return opened.get(0);
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int code;
        private final byte[] body;
        long declared;
        boolean honorDisconnect = true;
        boolean disconnected;
        boolean requested;
        /** Runs once, when half of the body has been read. */
        Runnable midStream;

        FakeConnection(URL url, int code, byte[] body) {
            super(url);
            this.code = code;
            this.body = body;
            this.declared = body.length;
        }

        @Override
        public int getResponseCode() {
            requested = true;
            return code;
        }

        @Override
        public long getContentLengthLong() {
            return declared;
        }

        @Override
        public String getContentType() {
            return "image/jpeg";
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                private int pos;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (midStream != null && pos >= body.length / 2) {
                        Runnable hook = midStream;
                        midStream = null;
                        hook.run();
                    }
                    if (disconnected && honorDisconnect) throw new IOException("disconnected");
                    if (pos == body.length) return -1;
                    int count = Math.min(Math.min(len, 4096), body.length - pos);
                    System.arraycopy(body, pos, b, off, count);
                    pos += count;
                    return count;
                }
            };
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }

    /** Runs scheduled tasks only when the test says so, ignoring their delays. */
    private static final class ManualExecutor extends AbstractExecutorService
            implements ScheduledExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        void run(int count) {
            for (int i = 0; i < count; i++) {
                delays.remove(0);
                tasks.remove(0).run();
            }
        }

        void runAll() {
            for (int i = 0; i < 100 && !tasks.isEmpty(); i++) run(1);
            assertTrue(tasks.isEmpty());
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            delays.add(unit.toMillis(delay));
            return null;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                         TimeUnit unit) {
            return schedule(command, initialDelay, unit);
        }

        @Override
        public void execute(Runnable command) {
            schedule(command, 0, TimeUnit.MILLISECONDS);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
