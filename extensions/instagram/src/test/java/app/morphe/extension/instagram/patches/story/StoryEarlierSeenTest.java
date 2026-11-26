package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class StoryEarlierSeenTest {

    @Test
    public void itemsBeforeTheSelectedOneAreReturnedInOrder() {
        List<String> items = Arrays.asList("a", "b", "c", "d");
        assertEquals(Arrays.asList("a", "b"), StoryEarlierSeen.itemsBefore(items, "c"));
    }

    @Test
    public void firstOrUnknownItemHasNoEarlierItems() {
        List<String> items = Arrays.asList("a", "b");
        assertTrue(StoryEarlierSeen.itemsBefore(items, "a").isEmpty());
        assertTrue(StoryEarlierSeen.itemsBefore(items, "z").isEmpty());
    }

    @Test
    public void moreItemsThanTheRegistryHoldsAreAllSentFewAtATime() {
        RegistryPort port = new RegistryPort();
        StoryEarlierSeen.Drain drain = new StoryEarlierSeen.Drain(stories(40), port);

        drain.pump();
        while (!port.ours.isEmpty()) {
            assertTrue(port.ours.size() <= StoryEarlierSeen.MAX_IN_FLIGHT);
            port.completeOldest();
        }

        assertEquals(40, port.sent);
        assertEquals(StoryEarlierSeen.MAX_IN_FLIGHT, port.maxInFlight);
        assertEquals(0, drain.queued());
        assertEquals(0, drain.inFlight());
    }

    @Test
    public void occupiedRegistryIsRetriedLaterInsteadOfDroppingItems() {
        RegistryPort port = new RegistryPort();
        List<Object> others = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Object request = new Object();
            assertTrue(port.registry.register(request, StorySeenKey.from("1", "other" + i), null));
            others.add(request);
        }
        StoryEarlierSeen.Drain drain = new StoryEarlierSeen.Drain(stories(6), port);

        drain.pump();
        assertEquals(0, port.sent);
        assertEquals(1, port.retries.size());

        port.complete(others.remove(0));
        port.runRetry();
        assertEquals(1, port.sent);

        while (!others.isEmpty()) port.complete(others.remove(0));
        while (!port.ours.isEmpty()) port.completeOldest();
        while (!port.retries.isEmpty()) port.runRetry();
        while (!port.ours.isEmpty()) port.completeOldest();

        assertEquals(6, port.sent);
        assertEquals(0, drain.queued());
    }

    @Test
    public void alwaysFullRegistryStopsAfterBoundedRetries() {
        RegistryPort port = new RegistryPort();
        port.alwaysFull = true;
        StoryEarlierSeen.Drain drain = new StoryEarlierSeen.Drain(stories(3), port);

        drain.pump();
        int retries = 0;
        while (!port.retries.isEmpty()) {
            port.runRetry();
            retries++;
        }

        assertEquals(StoryEarlierSeen.MAX_FULL_RETRIES, retries);
        assertEquals(0, drain.queued());
        assertEquals(0, port.sent);
    }

    @Test
    public void closedViewerStopsTheDrain() {
        RegistryPort port = new RegistryPort();
        StoryEarlierSeen.Drain drain = new StoryEarlierSeen.Drain(stories(10), port);

        drain.pump();
        port.active = false;
        while (!port.ours.isEmpty()) port.completeOldest();

        assertEquals(StoryEarlierSeen.MAX_IN_FLIGHT, port.sent);
        assertEquals(0, drain.queued());
    }

    @Test
    public void markedWithoutOwnPermitTakesNoSlot() {
        assertEquals(StoryEarlierSeen.Outcome.SENT, StoryEarlierSeen.sendOutcome(true, true, false, false));
        assertEquals(StoryEarlierSeen.Outcome.SENT, StoryEarlierSeen.sendOutcome(true, false, true, false));
        assertEquals(StoryEarlierSeen.Outcome.SKIPPED, StoryEarlierSeen.sendOutcome(true, false, false, false));
        assertEquals(StoryEarlierSeen.Outcome.FULL, StoryEarlierSeen.sendOutcome(false, false, false, true));
        assertEquals(StoryEarlierSeen.Outcome.SKIPPED, StoryEarlierSeen.sendOutcome(false, false, false, false));
    }

    @Test
    public void registryHoldsOnlyListenersOfLivePermits() {
        RequestRegistry registry = new RequestRegistry(() -> 0L, 30_000L, 32, key -> {
        });
        StorySeenRequestScope.Listener first = new CountingListener();
        StorySeenRequestScope.Listener second = new CountingListener();
        Object request = new Object();

        assertTrue(registry.register(request, StorySeenKey.from("1", "story"), first));
        assertFalse(registry.register(new Object(), StorySeenKey.from("1", "story"), second));
        assertTrue(registry.holds(first));
        assertFalse(registry.holds(second));

        registry.enter(request);
        registry.complete(request, ignored -> true);
        assertFalse(registry.holds(first));
    }

    @Test
    public void itemPendingElsewhereDoesNotLeakASlot() {
        RegistryPort port = new RegistryPort();
        port.bridgeLike = true;
        assertTrue(port.registry.register(new Object(), StorySeenKey.from("1", "story1"), null));
        StoryEarlierSeen.Drain drain = new StoryEarlierSeen.Drain(stories(10), port);

        drain.pump();
        assertEquals(StoryEarlierSeen.MAX_IN_FLIGHT, drain.inFlight());
        while (!port.ours.isEmpty()) port.completeOldest();

        assertEquals(9, port.sent);
        assertEquals(0, drain.inFlight());
        assertEquals(0, drain.queued());
    }

    @Test
    public void pauseAndResumeBeforeAResultStopsTheOldDrainForGood() {
        Object viewer = new Object();
        StoryAdvance.onViewerResumed(viewer);
        RegistryPort port = new RegistryPort();
        port.token = StoryAdvance.lifecycleToken();
        StoryEarlierSeen.Drain drain = StoryEarlierSeen.start(port.token, stories(10), port);
        assertEquals(StoryEarlierSeen.MAX_IN_FLIGHT, port.sent);

        StoryAdvance.onViewerPaused(viewer);
        StoryAdvance.onViewerResumed(viewer);
        assertFalse(StoryAdvance.isCurrent(port.token));
        while (!port.ours.isEmpty()) port.completeOldest();

        assertEquals(StoryEarlierSeen.MAX_IN_FLIGHT, port.sent);
        assertEquals(0, drain.queued());
        assertEquals(0, drain.inFlight());
        StoryAdvance.onViewerPaused(viewer);
    }

    @Test
    public void noLifecycleTokenStartsNoDrain() {
        Object viewer = new Object();
        StoryAdvance.onViewerResumed(viewer);
        StoryAdvance.onViewerPaused(viewer);
        assertEquals(StoryAdvance.NO_TOKEN, StoryAdvance.lifecycleToken());
        RegistryPort port = new RegistryPort();

        assertNull(StoryEarlierSeen.start(StoryAdvance.lifecycleToken(), stories(3), port));
        assertEquals(0, port.sent);
    }

    @Test
    public void expiredPermitsReleaseTheirSlotsWithoutOtherRegistryTraffic() {
        RegistryPort port = new RegistryPort();
        StoryEarlierSeen.Drain drain = new StoryEarlierSeen.Drain(stories(6), port);

        drain.pump();
        assertEquals(StoryEarlierSeen.MAX_IN_FLIGHT, drain.inFlight());
        assertEquals(1, port.ticks.size());

        port.now = 30_001L;
        port.runTick();

        assertEquals(6, port.sent);
        assertEquals(0, drain.queued());
        assertEquals(2, drain.inFlight());
        assertEquals(1, port.ticks.size());

        while (!port.ours.isEmpty()) port.completeOldest();
        port.runTick();
        assertTrue(port.ticks.isEmpty());
    }

    private static final class CountingListener implements StorySeenRequestScope.Listener {
        @Override
        public void onComplete() {
        }

        @Override
        public void onFailure() {
        }
    }

    private static List<Object> stories(int count) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < count; i++) items.add("story" + i);
        return items;
    }

    /** Sends through a real request registry of 32 slots; requests resolve when the test completes them. */
    private static final class RegistryPort implements StoryEarlierSeen.Port {
        long now;
        final RequestRegistry registry = new RequestRegistry(() -> now, 30_000L, 32, key -> {
        });
        final List<Object> ours = new ArrayList<>();
        final List<Runnable> retries = new ArrayList<>();
        final List<Runnable> ticks = new ArrayList<>();
        // Failures are reported from inside the registry, so like the bridge they resolve later.
        final List<Runnable> posted = new ArrayList<>();
        boolean active = true;
        long token = StoryAdvance.NO_TOKEN;
        boolean alwaysFull;
        // Like the bridge: an item already pending reports success without registering this listener.
        boolean bridgeLike;
        int sent;
        int maxInFlight;

        @Override
        public StoryEarlierSeen.Outcome send(Object item, Runnable onResolved) {
            if (alwaysFull || !registry.hasCapacity()) return StoryEarlierSeen.Outcome.FULL;
            Object request = new Object();
            StorySeenKey key = StorySeenKey.from("1", (String) item);
            StorySeenRequestScope.Listener listener = new StorySeenRequestScope.Listener() {
                @Override
                public void onComplete() {
                    onResolved.run();
                }

                @Override
                public void onFailure() {
                    ours.remove(request);
                    posted.add(onResolved);
                }
            };
            if (bridgeLike && registry.isPending(key)) {
                StoryEarlierSeen.Outcome outcome =
                        StoryEarlierSeen.sendOutcome(true, registry.holds(listener), false, false);
                assertEquals(StoryEarlierSeen.Outcome.SKIPPED, outcome);
                return outcome;
            }
            boolean registered = registry.register(request, key, listener);
            if (!registered) return StoryEarlierSeen.Outcome.FULL;
            ours.add(request);
            sent++;
            maxInFlight = Math.max(maxInFlight, ours.size());
            return StoryEarlierSeen.Outcome.SENT;
        }

        @Override
        public boolean active() {
            return active && (token == StoryAdvance.NO_TOKEN || StoryAdvance.isCurrent(token));
        }

        @Override
        public void retryLater(Runnable pump) {
            retries.add(pump);
        }

        @Override
        public void tickLater(Runnable tick) {
            ticks.add(tick);
        }

        @Override
        public void expireStale() {
            registry.expire();
        }

        void runTick() {
            ticks.remove(0).run();
            while (!posted.isEmpty()) posted.remove(0).run();
        }

        void completeOldest() {
            complete(ours.remove(0));
        }

        void complete(Object request) {
            registry.enter(request);
            registry.complete(request, ignored -> true);
        }

        void runRetry() {
            retries.remove(0).run();
        }
    }
}
