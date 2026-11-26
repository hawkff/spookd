/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Sends receipts for the owner's items before the one marked with the eye button. */
public final class StoryEarlierSeen {
    static final int MAX_IN_FLIGHT = 4;
    static final int MAX_FULL_RETRIES = 30;
    private static final long FULL_RETRY_DELAY_MILLIS = 1_000L;

    enum Outcome { SENT, SKIPPED, FULL }

    interface Port {
        /** One receipt; with SENT, {@code onResolved} runs once on the drain's thread after it resolves. */
        Outcome send(Object item, Runnable onResolved);

        boolean active();

        void retryLater(Runnable pump);
    }

    /** Sends queued receipts a few at a time and waits for free request slots instead of dropping items. */
    static final class Drain {
        private final ArrayDeque<Object> queue = new ArrayDeque<>();
        private final Port port;
        private int inFlight;
        private int fullRetries;

        Drain(List<?> items, Port port) {
            for (Object item : items) {
                if (item != null) queue.add(item);
            }
            this.port = port;
        }

        void pump() {
            if (!port.active()) {
                queue.clear();
                return;
            }
            while (inFlight < MAX_IN_FLIGHT && !queue.isEmpty()) {
                Outcome outcome = port.send(queue.peekFirst(), this::resolved);
                if (outcome == Outcome.FULL) {
                    // Our own resolutions pump again; with none in flight, check back later, a bounded number of times.
                    if (inFlight == 0) {
                        if (fullRetries++ < MAX_FULL_RETRIES) port.retryLater(this::pump);
                        else queue.clear();
                    }
                    return;
                }
                queue.pollFirst();
                if (outcome == Outcome.SENT) inFlight++;
            }
        }

        private void resolved() {
            inFlight--;
            pump();
        }

        int inFlight() {
            return inFlight;
        }

        int queued() {
            return queue.size();
        }
    }

    private StoryEarlierSeen() {
    }

    /** After the eye button marked {@code item}, on the main thread; the drain also runs there. */
    static void markBefore(Object session, Object item, Object reel, Object source) {
        try {
            if (!SettingsStatus.markEarlierStoriesSeen
                    || !SharedPref.getBooleanPref(Settings.MARK_EARLIER_STORIES_SEEN)) return;
            List<?> items = itemsNative(reel, session);
            if (items == null) return;
            new Drain(itemsBefore(items, item), bridgePort(session, reel, source)).pump();
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to mark earlier stories as seen", throwable);
        }
    }

    static List<?> itemsBefore(List<?> items, Object item) {
        int index = items.indexOf(item);
        // A copy, so marking cannot interfere with Instagram changing its live list.
        return index > 0 ? new ArrayList<Object>(items.subList(0, index)) : Collections.emptyList();
    }

    private static Port bridgePort(Object session, Object reel, Object source) {
        // Without the viewer hooks there is nothing to watch, and the drain ends with its list.
        Object viewer = StoryAdvance.currentViewer();
        return new Port() {
            @Override
            public Outcome send(Object item, Runnable onResolved) {
                try {
                    if (StorySeenBridge.state(session, item) != StorySeenBridge.SeenState.UNMARKED) {
                        return Outcome.SKIPPED;
                    }
                    if (!StorySeenRequestScope.hasCapacity()) return Outcome.FULL;
                    boolean[] counted = new boolean[1];
                    AtomicBoolean resolved = new AtomicBoolean();
                    // Posted, so the result is read after send returns; a failed schedule also reports a failure.
                    Runnable resolve = () -> {
                        if (resolved.compareAndSet(false, true)) {
                            Utils.runOnMainThread(() -> {
                                if (counted[0]) onResolved.run();
                            });
                        }
                    };
                    StorySeenRequestScope.Listener listener = new StorySeenRequestScope.Listener() {
                        @Override
                        public void onComplete() {
                            resolve.run();
                        }

                        @Override
                        public void onFailure() {
                            resolve.run();
                        }
                    };
                    if (StorySeenBridge.markSeen(session, item, reel, source, listener)) {
                        counted[0] = true;
                        return Outcome.SENT;
                    }
                    return StorySeenRequestScope.hasCapacity() ? Outcome.SKIPPED : Outcome.FULL;
                } catch (Throwable throwable) {
                    Logger.printException(() -> "Failed to mark an earlier story as seen", throwable);
                    return Outcome.SKIPPED;
                }
            }

            @Override
            public boolean active() {
                return viewer == null || StoryAdvance.currentViewer() == viewer;
            }

            @Override
            public void retryLater(Runnable pump) {
                Utils.runOnMainThreadDelayed(() -> {
                    try {
                        pump.run();
                    } catch (Throwable throwable) {
                        Logger.printException(() -> "Failed to mark earlier stories as seen", throwable);
                    }
                }, FULL_RETRY_DELAY_MILLIS);
            }
        };
    }

    // The patch replaces this body with the reel's ordered item list.
    public static List<?> itemsNative(Object reel, Object session) {
        return null;
    }
}
