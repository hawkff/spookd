package app.morphe.extension.instagram.patches.dm.vault;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reader and writer of the chunked AES-256-GCM vault file format (PKV v1).
 * Nothing here touches Instagram objects; callers run it on background threads.
 */
public final class VaultFile implements Closeable {
    /** Supplies the key-encryption key that wraps each file's data key. */
    public interface Kek {
        SecretKey get() throws GeneralSecurityException;
    }

    static final int CHUNK = 65_536;
    static final int TAG = 16;
    static final int STRIDE = CHUNK + TAG;
    static final int HEADER = 12;
    static final int AAD_LENGTH = 9;
    static final int WRAP_KEYSTORE = 1;
    static final int WRAP_PASSPHRASE = 2;
    static final int WRAP_KEYSTORE_LENGTH = 60;
    static final int WRAP_PASSPHRASE_LENGTH = 80;
    static final long MAX_CHUNKS = 1L << 32;
    private static final int IV = 12;
    private static final int DEK = 32;
    private static final String GCM = "AES/GCM/NoPadding";
    private static final byte[] MAGIC = {'P', 'K', 'V', 'T'};
    private static final int VERSION = 1;

    private final RandomAccessFile file;
    private final SecretKey dek;
    private final Cipher cipher;
    private final long dataStart;
    private final long chunks;
    private final int lastStride;
    private final long length;
    final byte[] sealed = new byte[STRIDE];
    // Some providers size decrypt output as input length, tag included.
    final byte[] plain = new byte[STRIDE];
    private long cachedChunk = -1;
    private int cachedLength;

    /** Size of a PKV file holding {@code plaintextLength} bytes, wrap type 1. */
    public static long fileSize(long plaintextLength) {
        if (plaintextLength < 0 || plaintextLength > MAX_CHUNKS * CHUNK) {
            throw new IllegalArgumentException("plaintext length out of range: " + plaintextLength);
        }
        long chunkCount = plaintextLength == 0 ? 1 : (plaintextLength - 1) / CHUNK + 1;
        return HEADER + WRAP_KEYSTORE_LENGTH + plaintextLength + TAG * chunkCount;
    }

    /**
     * Opens and validates a vault file, then authenticates its final chunk so truncation is
     * rejected here. Every failure, structural or cryptographic, is an IOException.
     */
    public static VaultFile open(File path, Kek kek) throws IOException {
        RandomAccessFile file = new RandomAccessFile(path, "r");
        try {
            VaultFile vault = new VaultFile(file, kek);
            vault.loadChunk(vault.chunks - 1);
            return vault;
        } catch (IOException | RuntimeException e) {
            try {
                file.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private VaultFile(RandomAccessFile file, Kek kek) throws IOException {
        this.file = file;
        long fileLength = file.length();
        if (fileLength < HEADER) throw new IOException("Vault file shorter than its header");
        byte[] header = new byte[HEADER];
        file.readFully(header);
        if (!Arrays.equals(Arrays.copyOf(header, AAD_LENGTH), aad())) {
            throw new IOException("Vault file has a wrong magic, version or chunk size");
        }
        int wrapType = header[9] & 0xFF;
        int wrapLength = ((header[10] & 0xFF) << 8) | (header[11] & 0xFF);
        if (!(wrapType == WRAP_KEYSTORE && wrapLength == WRAP_KEYSTORE_LENGTH)
                && !(wrapType == WRAP_PASSPHRASE && wrapLength == WRAP_PASSPHRASE_LENGTH)) {
            throw new IOException("Vault file has wrap type " + wrapType + " with length " + wrapLength);
        }
        dataStart = HEADER + wrapLength;
        chunks = chunkCount(fileLength - dataStart);
        lastStride = (int) (fileLength - dataStart - Math.multiplyExact(chunks - 1, (long) STRIDE));
        length = fileLength - dataStart - TAG * chunks;
        if (wrapType == WRAP_PASSPHRASE) {
            throw new IOException("Vault file wrap type 2 (passphrase) is not supported");
        }
        byte[] wrapped = new byte[wrapLength];
        file.readFully(wrapped);
        try {
            Cipher unwrap = Cipher.getInstance(GCM);
            unwrap.init(Cipher.DECRYPT_MODE, kek.get(), new GCMParameterSpec(TAG * 8, wrapped, 0, IV));
            byte[] raw = unwrap.doFinal(wrapped, IV, wrapLength - IV);
            dek = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            cipher = Cipher.getInstance(GCM);
        } catch (GeneralSecurityException e) {
            throw new IOException("Vault data key could not be unwrapped", e);
        }
    }

    /** Number of chunks of a body; rejects every body layout the format forbids. */
    static long chunkCount(long body) throws IOException {
        if (body < TAG) throw new IOException("Vault file has no final chunk");
        long count = (body - 1) / STRIDE + 1;
        if (count > MAX_CHUNKS) throw new IOException("Vault file has more than 2^32 chunks");
        long last = body - Math.multiplyExact(count - 1, (long) STRIDE);
        if (last < TAG) throw new IOException("Vault file final chunk is shorter than its tag");
        return count;
    }

    /** Plaintext length. */
    public long length() {
        return length;
    }

    /**
     * Reads up to {@code size} plaintext bytes at {@code pos}, crossing chunks as needed.
     * Returns the count read, or -1 when {@code pos} is at or past the end.
     */
    public synchronized int readAt(long pos, byte[] buf, int off, int size) throws IOException {
        if (pos < 0 || off < 0 || size < 0 || size > buf.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (pos >= length) return -1;
        int done = 0;
        while (done < size && pos < length) {
            long chunk = pos / CHUNK;
            int chunkLength = loadChunk(chunk);
            int at = (int) (pos - chunk * CHUNK);
            int count = Math.min(size - done, chunkLength - at);
            System.arraycopy(plain, at, buf, off + done, count);
            done += count;
            pos += count;
        }
        return done;
    }

    /** Sequential plaintext reader over {@link #readAt}. */
    public InputStream openStream() {
        return new InputStream() {
            private final byte[] one = new byte[1];
            private long pos;

            @Override
            public int read() throws IOException {
                return read(one, 0, 1) == 1 ? one[0] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int count = readAt(pos, b, off, len);
                if (count > 0) pos += count;
                return count;
            }
        };
    }

    /** Authenticates every chunk. */
    public synchronized void verify() throws IOException {
        for (long chunk = 0; chunk < chunks; chunk++) {
            loadChunk(chunk);
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    private int loadChunk(long chunk) throws IOException {
        if (chunk == cachedChunk) return cachedLength;
        cachedChunk = -1;
        boolean last = chunk == chunks - 1;
        int stride = last ? lastStride : STRIDE;
        file.seek(Math.addExact(dataStart, Math.multiplyExact(chunk, (long) STRIDE)));
        file.readFully(sealed, 0, stride);
        try {
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(TAG * 8, nonce(chunk, last)));
            cipher.updateAAD(aad());
            cachedLength = cipher.doFinal(sealed, 0, stride, plain, 0);
        } catch (GeneralSecurityException e) {
            throw new IOException("Vault chunk " + chunk + " failed authentication", e);
        }
        cachedChunk = chunk;
        return cachedLength;
    }

    private static byte[] aad() {
        return new byte[] {MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], VERSION, 0, 1, 0, 0};
    }

    private static byte[] nonce(long chunk, boolean last) {
        byte[] nonce = new byte[IV];
        nonce[7] = (byte) (chunk >>> 24);
        nonce[8] = (byte) (chunk >>> 16);
        nonce[9] = (byte) (chunk >>> 8);
        nonce[10] = (byte) chunk;
        nonce[11] = (byte) (last ? 1 : 0);
        return nonce;
    }

    /**
     * Encrypts a stream into a new vault file under a fresh random data key.
     * Nothing is valid until {@link #finish()} writes the final chunk; the caller owns {@code out}.
     */
    public static final class Writer {
        private final OutputStream out;
        private final SecretKey dek;
        private final Cipher cipher;
        final byte[] plain = new byte[CHUNK];
        final byte[] sealed = new byte[STRIDE];
        private int buffered;
        private long chunk;

        public Writer(OutputStream out, Kek kek) throws IOException {
            this.out = out;
            byte[] raw = new byte[DEK];
            byte[] wrapped;
            try {
                new SecureRandom().nextBytes(raw);
                Cipher wrap = Cipher.getInstance(GCM);
                // The Keystore key requires randomized encryption, so the provider picks the IV.
                wrap.init(Cipher.ENCRYPT_MODE, kek.get());
                byte[] iv = wrap.getIV();
                byte[] sealedKey = wrap.doFinal(raw);
                if (iv == null || iv.length != IV || sealedKey.length != WRAP_KEYSTORE_LENGTH - IV) {
                    throw new IOException("Vault key wrap produced an unexpected layout");
                }
                wrapped = new byte[WRAP_KEYSTORE_LENGTH];
                System.arraycopy(iv, 0, wrapped, 0, IV);
                System.arraycopy(sealedKey, 0, wrapped, IV, sealedKey.length);
                dek = new SecretKeySpec(raw, "AES");
                cipher = Cipher.getInstance(GCM);
            } catch (GeneralSecurityException e) {
                throw new IOException("Vault data key could not be wrapped", e);
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
            out.write(aad());
            out.write(new byte[] {WRAP_KEYSTORE, 0, WRAP_KEYSTORE_LENGTH});
            out.write(wrapped);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (buffered == CHUNK) seal(false);
                int count = Math.min(len, CHUNK - buffered);
                System.arraycopy(b, off, plain, buffered, count);
                buffered += count;
                off += count;
                len -= count;
            }
        }

        /** Writes the final chunk and flushes; call once, after the last write. */
        public void finish() throws IOException {
            seal(true);
            out.flush();
        }

        private void seal(boolean last) throws IOException {
            if (!last && chunk >= MAX_CHUNKS - 1) throw new IOException("Vault file too large");
            int count;
            try {
                cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(TAG * 8, nonce(chunk, last)));
                cipher.updateAAD(aad());
                count = cipher.doFinal(plain, 0, buffered, sealed, 0);
            } catch (GeneralSecurityException e) {
                throw new IOException("Vault chunk " + chunk + " could not be encrypted", e);
            }
            out.write(sealed, 0, count);
            chunk++;
            buffered = 0;
        }
    }
}
