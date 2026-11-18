package app.morphe.extension.instagram.patches.dm.vault;

import static app.morphe.extension.instagram.patches.dm.vault.VaultFile.CHUNK;
import static app.morphe.extension.instagram.patches.dm.vault.VaultFile.HEADER;
import static app.morphe.extension.instagram.patches.dm.vault.VaultFile.MAX_CHUNKS;
import static app.morphe.extension.instagram.patches.dm.vault.VaultFile.STRIDE;
import static app.morphe.extension.instagram.patches.dm.vault.VaultFile.TAG;
import static app.morphe.extension.instagram.patches.dm.vault.VaultFile.WRAP_KEYSTORE_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VaultFileTest {
    private static final VaultFile.Kek KEK = () -> new SecretKeySpec(filled(32, 7), "AES");
    private static final VaultFile.Kek OTHER_KEK = () -> new SecretKeySpec(filled(32, 8), "AES");
    private static final int DATA = HEADER + WRAP_KEYSTORE_LENGTH;
    private static final int THREE_CHUNKS = 3 * CHUNK + 5;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void roundTripsBoundarySizes() throws IOException {
        for (int size : new int[] {0, 1, CHUNK - 1, CHUNK, CHUNK + 1, THREE_CHUNKS}) {
            byte[] plain = pattern(0, size);
            File file = write(encrypt(plain));
            assertEquals(VaultFile.fileSize(size), file.length());
            try (VaultFile vault = VaultFile.open(file, KEK)) {
                assertEquals(size, vault.length());
                vault.verify();
                assertArrayEquals(plain, readStream(vault));
                assertEquals(-1, vault.readAt(size, new byte[1], 0, 1));
            }
        }
    }

    @Test
    public void readAtMatchesSequentialReadAcrossChunks() throws IOException {
        byte[] plain = pattern(0, THREE_CHUNKS);
        try (VaultFile vault = VaultFile.open(write(encrypt(plain)), KEK)) {
            byte[] sequential = readStream(vault);
            assertArrayEquals(plain, sequential);
            for (int size : new int[] {1, CHUNK, 10 * CHUNK}) {
                for (int start : new int[] {0, CHUNK - 1, 2 * CHUNK + 3}) {
                    ByteArrayOutputStream read = new ByteArrayOutputStream();
                    byte[] buf = new byte[size];
                    long pos = start;
                    int count;
                    while ((count = vault.readAt(pos, buf, 0, size)) > 0) {
                        read.write(buf, 0, count);
                        pos += count;
                    }
                    assertArrayEquals(Arrays.copyOfRange(sequential, start, THREE_CHUNKS), read.toByteArray());
                }
            }
        }
    }

    @Test
    public void streamsTwoHundredMegabytesWithFixedBuffers() throws IOException {
        long size = 200L * 1024 * 1024;
        int piece = 100_003;
        File file = tmp.newFile();
        try (OutputStream out = new FileOutputStream(file)) {
            VaultFile.Writer writer = new VaultFile.Writer(out, KEK);
            assertEquals(CHUNK, writer.plain.length);
            assertEquals(STRIDE, writer.sealed.length);
            for (long pos = 0; pos < size; pos += piece) {
                int count = (int) Math.min(piece, size - pos);
                writer.write(pattern(pos, count), 0, count);
            }
            writer.finish();
        }
        assertEquals(VaultFile.fileSize(size), file.length());
        try (VaultFile vault = VaultFile.open(file, KEK)) {
            assertEquals(size, vault.length());
            assertEquals(STRIDE, vault.plain.length);
            assertEquals(STRIDE, vault.sealed.length);
            InputStream in = vault.openStream();
            byte[] buf = new byte[piece];
            long pos = 0;
            int count;
            while ((count = in.read(buf, 0, piece)) > 0) {
                assertArrayEquals(pattern(pos, count), Arrays.copyOf(buf, count));
                pos += count;
            }
            assertEquals(size, pos);
        }
    }

    @Test
    public void rejectsFlippedBytes() throws IOException {
        byte[] good = encrypt(pattern(0, THREE_CHUNKS));
        // Header and wrap block.
        for (int at : new int[] {0, 4, 7, 9, 11, HEADER, DATA - 1}) {
            assertRejected(flip(good, at));
        }
        // Final chunk is authenticated at open.
        assertRejected(flip(good, good.length - 1));
        assertRejected(flip(good, DATA + 3 * STRIDE));
        // Earlier chunks fail on verify and on read.
        File file = write(flip(good, DATA + STRIDE + 10));
        try (VaultFile vault = VaultFile.open(file, KEK)) {
            assertThrows(IOException.class, vault::verify);
            assertThrows(IOException.class, () -> vault.readAt(CHUNK, new byte[1], 0, 1));
            assertEquals(1, vault.readAt(0, new byte[1], 0, 1));
        }
    }

    @Test
    public void rejectsWrongKey() throws IOException {
        File file = write(encrypt(pattern(0, 10)));
        assertThrows(IOException.class, () -> VaultFile.open(file, OTHER_KEK));
    }

    @Test
    public void rejectsMissingAppendedAndSwappedChunks() throws IOException {
        byte[] good = encrypt(pattern(0, THREE_CHUNKS));
        assertRejected(Arrays.copyOf(good, DATA + 3 * STRIDE));
        byte[] full = encrypt(pattern(0, 2 * CHUNK));
        for (int chunk = 0; chunk < 2; chunk++) {
            byte[] appended = Arrays.copyOf(full, full.length + STRIDE);
            System.arraycopy(full, DATA + chunk * STRIDE, appended, full.length, STRIDE);
            assertRejected(appended);
        }

        byte[] swapped = good.clone();
        System.arraycopy(good, DATA, swapped, DATA + STRIDE, STRIDE);
        System.arraycopy(good, DATA + STRIDE, swapped, DATA, STRIDE);
        try (VaultFile vault = VaultFile.open(write(swapped), KEK)) {
            assertThrows(IOException.class, vault::verify);
            assertThrows(IOException.class, () -> vault.readAt(0, new byte[1], 0, 1));
        }
    }

    @Test
    public void rejectsStructuralViolations() throws IOException {
        byte[] good = encrypt(pattern(0, CHUNK));
        assertRejected(new byte[0]);
        assertRejected(Arrays.copyOf(good, HEADER - 1));
        assertRejected(Arrays.copyOf(good, HEADER));
        assertRejected(set(good, 4, 2));
        assertRejected(set(good, 7, 1));
        assertRejected(set(good, 6, 0));
        assertRejected(set(good, 9, 0));
        assertRejected(set(good, 9, 3));
        assertRejected(set(good, 11, WRAP_KEYSTORE_LENGTH - 1));
        assertRejected(set(good, 11, WRAP_KEYSTORE_LENGTH + 20));
        assertRejected(set(good, 9, 2));
        // Body shorter than one tag.
        assertRejected(Arrays.copyOf(good, DATA + TAG - 1));
        // A second chunk shorter than its tag.
        assertRejected(Arrays.copyOf(good, good.length + TAG - 1));
        try (VaultFile vault = VaultFile.open(write(good), KEK)) {
            assertEquals(CHUNK, vault.length());
        }
    }

    @Test
    public void rejectsPassphraseWrapAsUnsupported() throws IOException {
        byte[] good = encrypt(pattern(0, 100));
        byte[] typeTwo = new byte[good.length + 20];
        System.arraycopy(good, 0, typeTwo, 0, HEADER);
        typeTwo[9] = 2;
        typeTwo[11] = 80;
        System.arraycopy(good, DATA, typeTwo, HEADER + 80, good.length - DATA);
        File file = write(typeTwo);
        IOException e = assertThrows(IOException.class, () -> VaultFile.open(file, KEK));
        assertTrue(e.getMessage(), e.getMessage().contains("not supported"));
    }

    @Test
    public void fileSizeRejectsLengthsOutsideTheFormat() {
        long max = VaultFile.MAX_CHUNKS * VaultFile.CHUNK;
        assertEquals(VaultFile.HEADER + VaultFile.WRAP_KEYSTORE_LENGTH + max + VaultFile.TAG * VaultFile.MAX_CHUNKS,
                VaultFile.fileSize(max));
        assertThrows(IllegalArgumentException.class, () -> VaultFile.fileSize(-1));
        assertThrows(IllegalArgumentException.class, () -> VaultFile.fileSize(max + 1));
    }

    @Test
    public void chunkCountEnforcesLayoutLimits() throws IOException {
        assertEquals(1, VaultFile.chunkCount(TAG));
        assertEquals(1, VaultFile.chunkCount(STRIDE));
        assertEquals(2, VaultFile.chunkCount(STRIDE + TAG));
        assertEquals(MAX_CHUNKS, VaultFile.chunkCount(MAX_CHUNKS * STRIDE));
        assertThrows(IOException.class, () -> VaultFile.chunkCount(TAG - 1));
        assertThrows(IOException.class, () -> VaultFile.chunkCount(STRIDE + TAG - 1));
        assertThrows(IOException.class, () -> VaultFile.chunkCount(MAX_CHUNKS * STRIDE + TAG));
        assertThrows(IOException.class, () -> VaultFile.chunkCount(Long.MAX_VALUE));
    }

    private File write(byte[] data) throws IOException {
        File file = tmp.newFile();
        Files.write(file.toPath(), data);
        return file;
    }

    private void assertRejected(byte[] data) throws IOException {
        File file = write(data);
        assertThrows(IOException.class, () -> VaultFile.open(file, KEK));
    }

    private static byte[] encrypt(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VaultFile.Writer writer = new VaultFile.Writer(out, KEK);
        // Odd pieces cross chunk boundaries.
        for (int pos = 0; pos < plain.length; pos += 7_777) {
            writer.write(plain, pos, Math.min(7_777, plain.length - pos));
        }
        writer.finish();
        return out.toByteArray();
    }

    private static byte[] readStream(VaultFile vault) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = vault.openStream();
        byte[] buf = new byte[4_099];
        int count;
        while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
        return out.toByteArray();
    }

    private static byte[] pattern(long start, int count) {
        byte[] data = new byte[count];
        for (int i = 0; i < count; i++) {
            long pos = start + i;
            data[i] = (byte) ((pos * 2_654_435_761L) >>> 24);
        }
        return data;
    }

    private static byte[] filled(int count, int value) {
        byte[] data = new byte[count];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static byte[] flip(byte[] data, int at) {
        byte[] copy = data.clone();
        copy[at] ^= 0x01;
        return copy;
    }

    private static byte[] set(byte[] data, int at, int value) {
        byte[] copy = data.clone();
        copy[at] = (byte) value;
        return copy;
    }
}
