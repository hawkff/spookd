package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class DeletedLogLoadsTest {

    @Test
    public void aDeleteAndARefreshDuringASuspendedAppendRunAfterIt() {
        DeletedLogLoads loads = new DeletedLogLoads();
        // The append's page fetch is suspended: it has started and not finished.
        assertTrue(loads.startAppend());
        assertTrue(loads.isBusy());

        // A confirmed delete and a picture refresh's reload both arrive meanwhile.
        assertFalse(loads.startReload(Arrays.asList("a", "b")));
        assertFalse(loads.startReload(null));
        assertFalse(loads.startAppend());

        // The fetch resumes: one reload is handed out, carrying every id to delete.
        assertEquals(Arrays.asList("a", "b"), loads.finish());
        assertFalse(loads.isBusy());
        assertTrue(loads.startReload(Arrays.asList("a", "b")));
        assertNull(loads.finish());
    }

    @Test
    public void aRefreshAloneIsQueuedAsAPlainReload() {
        DeletedLogLoads loads = new DeletedLogLoads();
        assertTrue(loads.startAppend());
        assertFalse(loads.startReload(null));
        assertEquals(Collections.emptyList(), loads.finish());
        assertNull(loads.finish());
    }

    @Test
    public void nothingIsQueuedWhenIdle() {
        DeletedLogLoads loads = new DeletedLogLoads();
        assertTrue(loads.startReload(null));
        assertNull(loads.finish());
        assertTrue(loads.startAppend());
        assertNull(loads.finish());
    }
}
