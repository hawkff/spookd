package app.morphe.extension.instagram.patches.story;

import static org.junit.Assert.assertEquals;
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

    private static List<Object> stories(int count) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < count; i++) items.add("story" + i);
        return items;
    }

    /** Sends through a real request registry of 32 slots; requests resolve when the test completes them. */
    private static final class RegistryPort implements StoryEarlierSeen.Port {
        final RequestRegistry registry = new RequestRegistry(() -> 0L, 30_000L, 32, key -> {
        });
        final List<Object> ours = new ArrayList<>();
        final List<Runnable> retries = new ArrayList<>();
        boolean active = true;
        boolean alwaysFull;
        int sent;
        int maxInFlight;

        @Override
        public StoryEarlierSeen.Outcome send(Object item, Runnable onResolved) {
            if (alwaysFull || !registry.hasCapacity()) return StoryEarlierSeen.Outcome.FULL;
            Object request = new Object();
            boolean registered = registry.register(request, StorySeenKey.from("1", (String) item),
                    new StorySeenRequestScope.Listener() {
                        @Override
                        public void onComplete() {
                            onResolved.run();
                        }

                        @Override
                        public void onFailure() {
                            onResolved.run();
                        }
                    });
            if (!registered) return StoryEarlierSeen.Outcome.FULL;
            ours.add(request);
            sent++;
            maxInFlight = Math.max(maxInFlight, ours.size());
            return StoryEarlierSeen.Outcome.SENT;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void retryLater(Runnable pump) {
            retries.add(pump);
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
