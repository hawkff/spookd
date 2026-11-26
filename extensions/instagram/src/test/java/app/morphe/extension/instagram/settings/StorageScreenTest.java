package app.morphe.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class StorageScreenTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static File write(File file, int bytes) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new byte[bytes]);
        return file;
    }

    @Test
    public void clearsTheCacheButKeepsSharedFilesAndLinkTargets() throws IOException {
        File cache = temp.newFolder("cache");
        File outside = write(new File(temp.getRoot(), "saved/keep.jpg"), 5);
        write(new File(cache, "images/a.jpg"), 10);
        write(new File(cache, "b.tmp"), 20);
        File shared = write(new File(cache, "piko_share/x/c.jpg"), 40);
        Files.createSymbolicLink(new File(cache, "link").toPath(), outside.getParentFile().toPath());
        Set<String> keep = Collections.singleton("piko_share");

        assertEquals(30, StorageScreen.sizeOfContents(cache, keep));
        assertEquals(70, StorageScreen.sizeOf(cache));

        StorageScreen.clearContents(cache, keep);

        assertTrue(shared.isFile());
        assertTrue(outside.isFile());
        assertFalse(new File(cache, "images").exists());
        assertFalse(new File(cache, "link").exists());
        assertEquals(0, StorageScreen.sizeOfContents(cache, keep));
    }

    @Test
    public void missingFoldersMeasureAndClearAsEmpty() {
        assertEquals(0, StorageScreen.sizeOf(null));
        assertEquals(0, StorageScreen.sizeOf(new File(temp.getRoot(), "none")));
        StorageScreen.clearContents(new File(temp.getRoot(), "none"), Collections.emptySet());
    }
}
