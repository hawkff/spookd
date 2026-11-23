/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Automatic receipts that wait while the seen request registry is full; past the bound the oldest is dropped. */
final class DeferredReceipts<T> {
    private final int capacity;
    private final BooleanSupplier registryFull;
    private final Predicate<T> send;
    private final ArrayDeque<T> waiting = new ArrayDeque<>();

    DeferredReceipts(int capacity, BooleanSupplier registryFull, Predicate<T> send) {
        this.capacity = capacity;
        this.registryFull = registryFull;
        this.send = send;
    }

    /** Sends now, or keeps the receipt when the registry is full. True while receipts wait for a retry. */
    synchronized boolean submit(T receipt) {
        if (waiting.isEmpty() && send.test(receipt)) return false;
        if (waiting.isEmpty() && !registryFull.getAsBoolean()) return false;
        if (waiting.size() >= capacity) waiting.pollFirst();
        waiting.addLast(receipt);
        return true;
    }

    /** Sends waiting receipts, oldest first, until the registry fills again. True while receipts still wait. */
    synchronized boolean retry() {
        while (!waiting.isEmpty() && !registryFull.getAsBoolean()) {
            T receipt = waiting.pollFirst();
            if (!send.test(receipt) && registryFull.getAsBoolean()) {
                waiting.addFirst(receipt);
                break;
            }
        }
        return !waiting.isEmpty();
    }
}
