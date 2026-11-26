/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import java.util.ArrayList;
import java.util.List;

/**
 * Main thread: lets one deleted-log load run at a time. A reload or delete asked for while a load
 * runs is kept, with every id to delete, and handed out when that load finishes.
 */
final class DeletedLogLoads {
    private boolean busy;
    private boolean reloadQueued;
    private final List<String> queuedDeletes = new ArrayList<>();

    boolean isBusy() {
        return busy;
    }

    /** True when the reload may start now; otherwise it is queued and runs after the current load. */
    boolean startReload(List<String> deleteIds) {
        if (busy) {
            reloadQueued = true;
            if (deleteIds != null) queuedDeletes.addAll(deleteIds);
            return false;
        }
        busy = true;
        return true;
    }

    /** True when an append may start now; an append is never queued. */
    boolean startAppend() {
        if (busy) return false;
        busy = true;
        return true;
    }

    /**
     * Ends the running load. Returns the queued reload's ids to delete, empty for a plain reload,
     * or null when nothing is queued.
     */
    List<String> finish() {
        busy = false;
        if (!reloadQueued) return null;
        reloadQueued = false;
        List<String> deletes = new ArrayList<>(queuedDeletes);
        queuedDeletes.clear();
        return deletes;
    }
}
