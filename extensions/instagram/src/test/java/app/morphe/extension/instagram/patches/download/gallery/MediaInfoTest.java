package app.morphe.extension.instagram.patches.download.gallery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import app.morphe.extension.instagram.patches.dm.vault.PlainMedia;
import app.morphe.extension.instagram.patches.dm.vault.ViewerMedia;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class MediaInfoTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aPhotosSizeAndFormatAreReadFromItsFile() throws Exception {
        File file = tmp.newFile("photo.png");
        Bitmap bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }

        MediaInfo.Probe probe = MediaInfo.read(() -> new PlainMedia(file), GalleryItem.PHOTO);

        assertEquals(3, probe.width);
        assertEquals(2, probe.height);
        assertEquals("image/png", probe.codec);
        assertFalse(probe.unreadable());
    }

    @Test
    public void failedReadsAreUnreadable() {
        assertTrue(MediaInfo.read(() -> null, GalleryItem.VIDEO).unreadable());
        assertTrue(MediaInfo.read(() -> {
            throw new IOException("deleted meanwhile");
        }, GalleryItem.PHOTO).unreadable());
        Media garbage = new Media("not an image".getBytes(StandardCharsets.UTF_8), null);
        assertTrue(MediaInfo.read(() -> garbage, GalleryItem.PHOTO).unreadable());
        assertEquals(1, garbage.closes);
    }

    @Test
    public void theOpenedMediaIsClosedOnEveryPath() {
        Media failing = new Media(new byte[0], new IllegalStateException("read failed"));
        assertTrue(MediaInfo.read(() -> failing, GalleryItem.PHOTO).unreadable());
        assertEquals(1, failing.closes);

        Media outOfMemory = new Media(new byte[0], new OutOfMemoryError());
        assertTrue(MediaInfo.read(() -> outOfMemory, GalleryItem.PHOTO).unreadable());
        assertEquals(1, outOfMemory.closes);
    }

    @Test
    public void aQuarterTurnedVideoHasItsSidesSwapped() {
        assertArrayEquals(new int[]{1080, 1920}, MediaInfo.upright(1920, 1080, 90));
        assertArrayEquals(new int[]{1080, 1920}, MediaInfo.upright(1920, 1080, 270));
        assertArrayEquals(new int[]{1080, 1920}, MediaInfo.upright(1920, 1080, -90));
        assertArrayEquals(new int[]{1920, 1080}, MediaInfo.upright(1920, 1080, 0));
        assertArrayEquals(new int[]{1920, 1080}, MediaInfo.upright(1920, 1080, 180));
    }

    @Test
    public void trackTypesListKnownTracksInOrder() {
        assertEquals("video/avc, audio/mp4a-latm",
                MediaInfo.trackTypes(Arrays.asList("video/avc", null, "", "audio/mp4a-latm")));
        assertNull(MediaInfo.trackTypes(Collections.emptyList()));
        assertNull(MediaInfo.trackTypes(Collections.singletonList(null)));
        MediaInfo.Probe audio = new MediaInfo.Probe();
        audio.durationMs = 3000L;
        assertFalse(audio.unreadable());
    }

    /** In-memory media that counts closes; with {@code failure}, opening its stream throws it. */
    private static final class Media implements ViewerMedia {
        private final byte[] bytes;
        private final Throwable failure;
        int closes;

        Media(byte[] bytes, Throwable failure) {
            this.bytes = bytes;
            this.failure = failure;
        }

        @Override
        public long length() {
            return bytes.length;
        }

        @Override
        public int readAt(long pos, byte[] buf, int off, int size) {
            if (pos >= bytes.length) return -1;
            int count = (int) Math.min(size, bytes.length - pos);
            System.arraycopy(bytes, (int) pos, buf, off, count);
            return count;
        }

        @Override
        public InputStream openStream() {
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            return ViewerMedia.super.openStream();
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
