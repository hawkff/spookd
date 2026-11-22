package app.morphe.extension.instagram.patches.dm.vault;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.crypto.BadPaddingException;
import javax.crypto.SecretKey;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.db.PikoMessageDb;
import app.morphe.extension.instagram.utils.MediaUrls;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Downloads received DM media into encrypted vault files and keeps them within the size and age
 * limits. Fetches, recovery and the retention sweep run on the vault's own executor threads.
 */
public final class MediaVault {
    static final long CAP_BYTES = 8L << 30;
    static final long RETENTION_MS = TimeUnit.DAYS.toMillis(30);
    static final int MAX_ATTEMPTS = 5;
    static final long RESERVE_STEP = 8L << 20;
    static final long MINUTE_MS = 60_000;
    private static final long FIRST_RETRY_MS = 30_000;
    private static final long SWEEP_FIRST_MS = 30_000;
    private static final long SWEEP_EVERY_MS = TimeUnit.HOURS.toMillis(6);
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String PART = ".part";
    private static final String PKV = ".pkv";

    /** Network access; tests replace it with a fake. */
    interface ConnectionOpener {
        boolean isOnline();

        HttpURLConnection open(URL url) throws IOException;
    }

    private static volatile MediaVault instance;

    final VaultStore store;
    private final ConnectionOpener opener;
    private final LongSupplier clock;
    private final VaultFile.Kek kek;
    private final File dir;
    private final ScheduledExecutorService executor;
    // Lock order: this (recovery, user delete) before storageLock; never the reverse.
    private final Object storageLock = new Object();
    // Guarded by storageLock: bytes of stored files, and bytes reserved by active writers.
    long storedBytes;
    long reservations;
    private final Set<Long> activeWriters = ConcurrentHashMap.newKeySet();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final Map<Long, HttpURLConnection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private boolean recovered;

    MediaVault(VaultStore store, ConnectionOpener opener, LongSupplier clock, VaultFile.Kek kek,
               File dir, ScheduledExecutorService executor) {
        this.store = store;
        this.opener = opener;
        this.clock = clock;
        this.kek = kek;
        this.dir = dir;
        this.executor = executor;
    }

    /** The process-wide vault. Throws when the app context is not set yet. */
    public static MediaVault get() {
        MediaVault vault = instance;
        if (vault == null) {
            synchronized (MediaVault.class) {
                if (instance == null) instance = create(PikoUtils.getContext());
                vault = instance;
            }
        }
        return vault;
    }

    private static MediaVault create(Context context) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, task -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "piko-vault"));
        ConnectionOpener network = new ConnectionOpener() {
            @Override
            public boolean isOnline() {
                return Utils.isNetworkConnected();
            }

            @Override
            public HttpURLConnection open(URL url) throws IOException {
                return (HttpURLConnection) url.openConnection();
            }
        };
        return new MediaVault(new VaultStore(PikoMessageDb.getInstance(context).getWritableDatabase()),
                network, System::currentTimeMillis, VaultKeys.INSTANCE,
                new File(context.getNoBackupFilesDir(), "piko_vault"), executor);
    }

    /**
     * Queues the media of a received item ({@code kind} photo, video or voice) for download.
     * Runs on the caller's thread and never throws. Skips items whose link is missing or
     * expired, and messages already older than the retention.
     */
    public void enqueue(String itemId, String accountId, String kind, String url, long msgTimestamp) {
        try {
            start();
            long now = clock.getAsLong();
            long expiresAt = MediaUrls.expiresAt(url);
            if (itemId == null || itemId.isEmpty() || expiresAt <= now
                    || msgTimestamp < now - RETENTION_MS
                    || !("photo".equals(kind) || "video".equals(kind) || "voice".equals(kind))) return;
            long id = store.insertQueued(itemId, accountId, kind, url, expiresAt, msgTimestamp);
            if (id != -1) schedule(id, 0);
        } catch (Exception e) {
            Logger.printException(() -> "MediaVault.enqueue failed", e);
        }
    }

    /**
     * Drops the media of these saved message ids: unlinks stored files and stops their fetches.
     * Runs on the caller's background thread, may wait for startup recovery, and throws on database errors.
     */
    public void deleteForMessages(List<String> messageIds) {
        // Waits for a running recovery, so its total cannot drop a file this delete failed to unlink.
        synchronized (this) {
            for (String messageId : messageIds) {
                dropRow(() -> store.rowForMessage(messageId));
            }
        }
    }

    /**
     * Drops the media of one row id: unlinks its stored file and stops its fetch.
     * Runs on the caller's background thread, may wait for startup recovery, and throws on database errors.
     */
    public void delete(long id) {
        synchronized (this) {
            dropRow(() -> store.row(id));
        }
    }

    /** Drops the row {@code lookup} reads under the storage lock; the caller holds this monitor. */
    private void dropRow(Supplier<VaultStore.Media> lookup) {
        VaultStore.Media row;
        synchronized (storageLock) {
            row = lookup.get();
            if (row == null || store.drop(row.id) != 1) return;
            if (VaultStore.STORED.equals(row.state) && unlink(file(row.id, PKV))) {
                storedBytes -= row.sizeBytes;
            }
        }
        HttpURLConnection connection = connections.get(row.id);
        if (connection != null) connection.disconnect();
    }

    /** A stored media file and its kind: photo, video or voice. */
    public static final class Stored {
        public final VaultFile file;
        public final String kind;

        Stored(VaultFile file, String kind) {
            this.file = file;
            this.kind = kind;
        }
    }

    /**
     * Opens the stored media of a saved message id; null when it has none stored. The caller closes the file.
     * Runs on the caller's background thread; throws IOException when the file is unreadable or deleted meanwhile.
     */
    public Stored openForMessage(String messageId) throws IOException {
        return openRow(store.rowForMessage(messageId));
    }

    /** {@link #openForMessage} by row id, as {@link #storedItems} lists it. */
    public Stored open(long id) throws IOException {
        return openRow(store.row(id));
    }

    private Stored openRow(VaultStore.Media row) throws IOException {
        if (row == null || !VaultStore.STORED.equals(row.state)) return null;
        return new Stored(VaultFile.open(file(row.id, PKV), kek), row.kind);
    }

    /** Ids of unsent saved messages whose media is stored. Runs on the caller's background thread. */
    public Set<String> storedMessageIds() {
        return store.storedMessageIds();
    }

    /** One stored media row; the sender is null when its message is not saved. */
    public static final class Item {
        public final long id;
        public final String kind;
        public final String mime;
        public final long sizeBytes;
        public final long msgTimestamp;
        public final String accountId;
        public final String senderUsername;
        public final String senderId;

        Item(long id, String kind, String mime, long sizeBytes, long msgTimestamp, String accountId,
             String senderUsername, String senderId) {
            this.id = id;
            this.kind = kind;
            this.mime = mime;
            this.sizeBytes = sizeBytes;
            this.msgTimestamp = msgTimestamp;
            this.accountId = accountId;
            this.senderUsername = senderUsername;
            this.senderId = senderId;
        }
    }

    /** Every stored media row, kept or not, newest first. Runs on the caller's background thread. */
    public List<Item> storedItems() {
        return store.storedItems();
    }

    /** Starts recovery, queued downloads and the retention sweep once per process; returns at once. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        executor.execute(() -> run("recovery", () -> {
        }));
        executor.scheduleWithFixedDelay(() -> run("retention", this::sweep),
                SWEEP_FIRST_MS, SWEEP_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    /** Runs an executor task after recovery; logs failures so periodic tasks keep running. */
    private void run(String name, Runnable body) {
        try {
            recover();
            body.run();
        } catch (Exception e) {
            Logger.printException(() -> "MediaVault " + name + " failed", e);
        }
    }

    private void schedule(long id, long delayMs) {
        if (!pending.add(id)) return;
        executor.schedule(() -> {
            try {
                // The marker leaves under the recovery monitor: no other recovery can see it in between.
                synchronized (this) {
                    try {
                        recover();
                    } finally {
                        pending.remove(id);
                    }
                }
                fetch(id);
            } catch (Exception e) {
                Logger.printException(() -> "MediaVault fetch " + id + " failed", e);
            }
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private void fetch(long id) {
        if (!activeWriters.add(id)) return;
        long retryDelay;
        try {
            retryDelay = attempt(id);
        } finally {
            activeWriters.remove(id);
        }
        if (retryDelay >= 0) schedule(id, retryDelay);
    }

    /** One download attempt. Returns the delay before the next one, or -1 when none is due. */
    private long attempt(long id) {
        VaultStore.Media row = store.row(id);
        if (row == null || !VaultStore.QUEUED.equals(row.state)) return -1;
        // A default cookie handler would attach the app's cookies to the request.
        if (CookieHandler.getDefault() != null) {
            Logger.printInfo(() -> "MediaVault: a default CookieHandler is set; not fetching");
            return -1;
        }
        if (!opener.isOnline()) return MINUTE_MS;
        long now = clock.getAsLong();
        if (!store.countAttempt(id, now + MINUTE_MS, now)) {
            store.fail(id);
            return -1;
        }
        URL url = allowedUrl(row.url);
        if (url == null) {
            store.fail(id);
            return -1;
        }
        try {
            if (!download(id, url)) return -1;
        } catch (IOException e) {
            Logger.printInfo(() -> "MediaVault: fetch " + id + " failed; retrying", e);
        }
        now = clock.getAsLong();
        long at = Math.min(now + (FIRST_RETRY_MS << row.attempts), row.expiresAt - MINUTE_MS);
        return Math.max(0, at - now);
    }

    /** Streams one response into a published vault file. Returns true when a retry is due. */
    private boolean download(long id, URL url) throws IOException {
        File part = file(id, PART);
        File pkv = file(id, PKV);
        HttpURLConnection connection = opener.open(url);
        long reserved = 0;
        try {
            // Under the monitor a delete holds, so it either finds this connection or wins here.
            synchronized (this) {
                VaultStore.Media row = store.row(id);
                if (row == null || !VaultStore.QUEUED.equals(row.state)) return false;
                connections.put(id, connection);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            int code = connection.getResponseCode();
            if (code == 429 || code >= 500) return true;
            if (code != HttpURLConnection.HTTP_OK) return failed(id);
            String mime = connection.getContentType();
            long declared = connection.getContentLengthLong();
            // Clamped so fileSize stays in range; anything above the cap fails admission anyway.
            long need = declared < 0 ? RESERVE_STEP : VaultFile.fileSize(Math.min(declared, CAP_BYTES));
            synchronized (storageLock) {
                if (declared >= 0) {
                    if (need > CAP_BYTES - store.keptBytes() - reservations) return failed(id);
                    evict(Long.MAX_VALUE, need);
                }
                if (storedBytes + reservations + need > CAP_BYTES) return true;
                reservations += need;
                reserved = need;
            }
            long received = 0;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                VaultFile.Writer writer = new VaultFile.Writer(out, kek);
                byte[] buffer = new byte[VaultFile.CHUNK];
                for (int count; (count = in.read(buffer)) != -1; ) {
                    received += count;
                    if (declared >= 0 && received > declared) return true;
                    // Only an unknown length outgrows its reservation; it never evicts.
                    while (VaultFile.fileSize(received) > reserved) {
                        synchronized (storageLock) {
                            if (storedBytes + reservations + RESERVE_STEP > CAP_BYTES) return true;
                            reservations += RESERVE_STEP;
                            reserved += RESERVE_STEP;
                        }
                    }
                    writer.write(buffer, 0, count);
                }
                if (received < declared) return true;
                writer.finish();
                out.getFD().sync();
            }
            if (!part.renameTo(pkv)) throw new IOException("Could not rename " + part);
            syncDirectory();
            long size = pkv.length();
            boolean published = false;
            try {
                synchronized (storageLock) {
                    published = store.publish(id, size, mime) == 1;
                    if (published) {
                        storedBytes += size;
                        reservations -= reserved;
                        reserved = 0;
                    }
                }
            } finally {
                // Deleted or dropped meanwhile, or the statement failed: the file goes, the row stays.
                if (!published) discard(pkv);
            }
            return false;
        } finally {
            connections.remove(id);
            connection.disconnect();
            discard(part);
            if (reserved > 0) {
                synchronized (storageLock) {
                    reservations -= reserved;
                }
            }
        }
    }

    /**
     * Deletes a file before the reservation that covered it is released. A file that stays keeps
     * its bytes counted until recovery at the next start deletes or publishes it.
     */
    private void discard(File file) {
        if (unlink(file)) return;
        long size = file.length();
        synchronized (storageLock) {
            storedBytes += size;
        }
    }

    /** Deletes a file; false, logged, when it is still there. */
    private static boolean unlink(File file) {
        if (file.delete() || !file.exists()) return true;
        Logger.printException(() -> "MediaVault: could not delete " + file);
        return false;
    }

    private boolean failed(long id) {
        store.fail(id);
        return false;
    }

    /** The url as a URL only if it is an https CDN link without user info on the default port. */
    static URL allowedUrl(String value) {
        try {
            URL url = new URL(value);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            boolean cdn = host.equals("cdninstagram.com") || host.endsWith(".cdninstagram.com")
                    || host.endsWith(".fbcdn.net");
            return "https".equals(url.getProtocol()) && url.getUserInfo() == null
                    && (url.getPort() == -1 || url.getPort() == 443) && cdn ? url : null;
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /** Drops unkept stored media older than {@code before}, oldest first, until {@code need} fits; 0 drops all. */
    private void evict(long before, long need) {
        synchronized (storageLock) {
            for (List<long[]> page; !(page = store.evictionCandidates(before)).isEmpty(); ) {
                for (long[] row : page) {
                    if (need > 0 && storedBytes + reservations + need <= CAP_BYTES) return;
                    dropStored(row[0], row[1], before);
                }
            }
        }
    }

    /** Drops one stored item unless it was unsent meanwhile; unlinks its file only if the row changed. */
    boolean dropStored(long id, long sizeBytes, long before) {
        synchronized (storageLock) {
            if (store.dropStored(id, before) != 1) return false;
            if (unlink(file(id, PKV))) storedBytes -= sizeBytes;
            return true;
        }
    }

    /** Retention: drops unkept stored and queued media past the retention, then old tombstones; kept media stays. */
    void sweep() {
        long before = clock.getAsLong() - RETENTION_MS;
        evict(before, 0);
        store.dropQueuedBefore(before);
        store.deleteTombstonesBefore(before);
    }

    /** Reconciles files with rows after a crash; runs once, before any fetch, then schedules queued rows. */
    synchronized void recover() {
        if (recovered) return;
        // Without the key a complete file cannot be told from a corrupt one: keep every file, retry later.
        SecretKey key;
        try {
            key = kek.get();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Vault key unavailable; recovery will retry", e);
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
        File[] files = dir.listFiles();
        if (files == null) throw new IllegalStateException("Cannot list " + dir);
        // Files that cannot be deleted stay counted, so admission never ignores their space.
        long leftover = 0;
        for (File file : files) {
            String name = file.getName();
            if ((name.endsWith(PART) || (name.endsWith(PKV) && !keepFound(file, name, key)))
                    && !unlink(file)) {
                leftover += file.length();
            }
        }
        for (long[] row : store.stored()) {
            if (!file(row[0], PKV).exists()) store.markLost(row[0]);
        }
        synchronized (storageLock) {
            storedBytes = store.storedBytes() + leftover;
            reservations = 0;
        }
        long now = clock.getAsLong();
        for (long[] row : store.queued()) schedule(row[0], row[1] - now);
        recovered = true;
    }

    /** Whether a .pkv found at startup stays; a complete one left queued by a crash is published. */
    private boolean keepFound(File file, String name, SecretKey key) {
        long id;
        try {
            id = Long.parseLong(name.substring(0, name.length() - PKV.length()));
        } catch (NumberFormatException e) {
            return false;
        }
        VaultStore.Media row = store.row(id);
        if (row == null) return false;
        if (VaultStore.STORED.equals(row.state)) return true;
        if (!VaultStore.QUEUED.equals(row.state)) return false;
        try (VaultFile vault = VaultFile.open(file, () -> key)) {
            vault.verify();
        } catch (IOException e) {
            // Only a layout or authentication failure proves the file invalid; anything else may pass later.
            if (e.getCause() == null || e.getCause() instanceof BadPaddingException) return false;
            throw new IllegalStateException("Cannot check " + file + "; recovery will retry", e);
        }
        synchronized (storageLock) {
            return store.publish(id, file.length(), null) == 1;
        }
    }

    private void syncDirectory() {
        try {
            FileDescriptor fd = Os.open(dir.getPath(), OsConstants.O_RDONLY, 0);
            try {
                Os.fsync(fd);
            } finally {
                Os.close(fd);
            }
        } catch (Exception | LinkageError e) {
            // Not fatal: recovery marks the row failed if the rename is lost.
            Logger.printException(() -> "MediaVault: directory sync failed", e);
        }
    }

    private File file(long id, String extension) {
        return new File(dir, id + extension);
    }
}
