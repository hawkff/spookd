package app.morphe.extension.instagram.patches.instants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class InstantsHoldTest {
    // Stand in for Instagram's send and discard (or resume with false) calls.
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicInteger drops = new AtomicInteger();
    private final InstantsHold hold = new InstantsHold(sends::incrementAndGet, drops::incrementAndGet);

    @Test
    public void approvalSendsExactlyOnce() {
        hold.send();
        hold.send();
        hold.drop();
        assertEquals(1, sends.get());
        assertEquals(0, drops.get());
    }

    @Test
    public void cancelDropsExactlyOnce() {
        hold.drop();
        hold.drop();
        hold.send();
        assertEquals(0, sends.get());
        assertEquals(1, drops.get());
    }

    @Test
    public void aScreenLeftBeforeThePostedQuestionIsDroppedUnasked() {
        AtomicBoolean asked = new AtomicBoolean();
        hold.askLater(() -> true, shown -> {
            asked.set(true);
            return true;
        });
        assertFalse(asked.get());
        assertEquals(0, sends.get());
        assertEquals(1, drops.get());
    }

    @Test
    public void aShownQuestionWaitsAndItsDuplicateAnswersCountOnce() {
        hold.askLater(() -> false, shown -> true);
        assertEquals(0, sends.get() + drops.get());
        hold.send();
        hold.drop();
        hold.send();
        assertEquals(1, sends.get());
        assertEquals(0, drops.get());
    }

    @Test
    public void aQuestionThatCannotShowSendsOnce() {
        hold.askLater(() -> false, shown -> false);
        hold.drop();
        assertEquals(1, sends.get());
        assertEquals(0, drops.get());
    }

    @Test
    public void aReactionThatSuspendedIsLeftToInstagramToResume() {
        Object suspended = new Object();
        List<Object> resumed = new ArrayList<>();
        InstantsReactions.sendThrough(() -> suspended, suspended, resumed::add);
        assertEquals(Collections.emptyList(), resumed);

        InstantsReactions.sendThrough(() -> Boolean.TRUE, suspended, resumed::add);
        InstantsReactions.sendThrough(() -> {
            throw new IllegalStateException("send failed");
        }, suspended, resumed::add);
        assertEquals(java.util.Arrays.asList(Boolean.TRUE, Boolean.FALSE), resumed);
    }
}
