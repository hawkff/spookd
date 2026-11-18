package app.morphe.extension.instagram.patches.dm.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VaultViewerTest {
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
}
