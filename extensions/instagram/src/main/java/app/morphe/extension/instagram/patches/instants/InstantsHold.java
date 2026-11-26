/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import app.morphe.extension.shared.Logger;

/** An Instant send held for its question: the first of {@link #send} and {@link #drop} acts, later calls do nothing. */
final class InstantsHold {
    private final AtomicBoolean settled = new AtomicBoolean();
    private final Runnable send;
    private final Runnable drop;

    InstantsHold(Runnable send, Runnable drop) {
        this.send = send;
        this.drop = drop;
    }

    void send() {
        if (settled.compareAndSet(false, true)) send.run();
    }

    void drop() {
        if (settled.compareAndSet(false, true)) drop.run();
    }

    /**
     * Main thread, for a question posted from another thread: a screen {@code left} meanwhile drops the
     * send; a question {@code ask} could not show lets it go.
     */
    void askLater(BooleanSupplier left, Predicate<InstantsHold> ask) {
        boolean gone;
        try {
            gone = left.getAsBoolean();
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to check the Instants screen", t);
            gone = false;
        }
        if (gone) drop();
        else if (!ask.test(this)) send();
    }
}
