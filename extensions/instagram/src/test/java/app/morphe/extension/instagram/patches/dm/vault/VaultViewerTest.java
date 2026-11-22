package app.morphe.extension.instagram.patches.dm.vault;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.instagram.patches.dm.InboxLock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class VaultViewerTest {
    private static final int CHUNK = VaultFile.CHUNK;
    private static final int THREE_CHUNKS = 3 * CHUNK + 5;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Activity activity;

    @After
    public void tearDown() throws Exception {
        setPrompting(false);
        if (activity != null) {
            pending().remove(activity);
            confirmed().remove(activity);
        }
    }

    @Test
    public void sampleSizeKeepsTheScreenCoveredAndTheBitmapBounded() {
        assertEquals(1, VaultViewer.sampleSize(1080, 1920, 1080, 2400));
        assertEquals(1, VaultViewer.sampleSize(4000, 3000, 1080, 2400));
        assertEquals(2, VaultViewer.sampleSize(2160, 4800, 1080, 2400));
        assertEquals(8, VaultViewer.sampleSize(8640, 19200, 1080, 2400));
        // A long strip: the screen rule never samples it, the pixel cap does.
        int sample = VaultViewer.sampleSize(100_000, 1000, 1080, 2400);
        assertTrue((long) (100_000 / sample) * (1000 / sample) <= 16_000_000);
        assertEquals(4, sample);
        assertEquals(1, VaultViewer.sampleSize(1, 1, 0, 0));
    }

    @Test
    public void closeWithNoPromptUpEndsTheRequestAndTheConfirmation() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).create().get();
        pending().put(activity, new Object());
        confirmed().add(activity);

        for (int i = 0; i < 2; i++) {
            VaultViewer.close(activity);
            assertFalse(pending().containsKey(activity));
            assertFalse(confirmed().contains(activity));
        }
    }

    @Test
    public void closeWhileAPromptIsUpStillEndsTheRequest() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).create().get();
        pending().put(activity, new Object());
        confirmed().add(activity);
        setPrompting(true);

        VaultViewer.close(activity);
        // With no request left, a success that arrives after the stop finds nothing to confirm.
        assertFalse(pending().containsKey(activity));
        assertFalse(confirmed().contains(activity));
    }

    @Test
    public void plainMediaRoundTripsBoundarySizes() throws IOException {
        for (int size : new int[] {0, 1, CHUNK - 1, CHUNK, CHUNK + 1, THREE_CHUNKS}) {
            byte[] plain = pattern(size);
            try (PlainMedia media = new PlainMedia(write(plain))) {
                assertEquals(size, media.length());
                assertArrayEquals(plain, readStream(media));
                assertEquals(-1, media.readAt(size, new byte[1], 0, 1));
                assertEquals(-1, media.readAt(size + 10, new byte[1], 0, 1));
            }
        }
    }

    @Test
    public void plainMediaReadAtMatchesSequentialReadFromAnyPosition() throws IOException {
        byte[] plain = pattern(THREE_CHUNKS);
        try (PlainMedia media = new PlainMedia(write(plain))) {
            for (int size : new int[] {1, CHUNK, 10 * CHUNK}) {
                for (int start : new int[] {0, CHUNK - 1, 2 * CHUNK + 3}) {
                    ByteArrayOutputStream read = new ByteArrayOutputStream();
                    byte[] buf = new byte[size];
                    long pos = start;
                    int count;
                    while ((count = media.readAt(pos, buf, 0, size)) > 0) {
                        read.write(buf, 0, count);
                        pos += count;
                    }
                    assertArrayEquals(Arrays.copyOfRange(plain, start, THREE_CHUNKS), read.toByteArray());
                }
            }
            byte[] buf = new byte[10];
            assertEquals(4, media.readAt(2, buf, 6, 4));
            assertArrayEquals(Arrays.copyOfRange(plain, 2, 6), Arrays.copyOfRange(buf, 6, 10));
            assertEquals(0, media.readAt(0, buf, 0, 0));
        }
    }

    @Test
    public void plainMediaRejectsOutOfRangeArguments() throws IOException {
        try (PlainMedia media = new PlainMedia(write(pattern(100)))) {
            byte[] buf = new byte[10];
            assertThrows(IndexOutOfBoundsException.class, () -> media.readAt(-1, buf, 0, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> media.readAt(0, buf, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> media.readAt(0, buf, 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> media.readAt(0, buf, 5, 6));
        }
        assertThrows(IOException.class, () -> new PlainMedia(new File(tmp.getRoot(), "missing")));
    }

    private File write(byte[] data) throws IOException {
        File file = tmp.newFile();
        Files.write(file.toPath(), data);
        return file;
    }

    private static byte[] readStream(ViewerMedia media) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = media.openStream();
        byte[] buf = new byte[4_099];
        int count;
        while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
        return out.toByteArray();
    }

    private static byte[] pattern(int count) {
        byte[] data = new byte[count];
        for (int i = 0; i < count; i++) data[i] = (byte) ((i * 2_654_435_761L) >>> 24);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<Activity, Object> pending() throws Exception {
        return (Map<Activity, Object>) field(VaultViewer.class, "pending").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<Activity> confirmed() throws Exception {
        return (Set<Activity>) field(VaultViewer.class, "confirmed").get(null);
    }

    private static void setPrompting(boolean prompting) throws Exception {
        field(InboxLock.class, "prompting").setBoolean(null, prompting);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
