package app.morphe.extension.instagram.patches.dm.presence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.Test;

public class ActivityAlertsTest {
    private static final long MINUTE = 60 * 1000L;

    @Test
    public void theFirstKnownStateIsNotAChange() {
        ActivityAlerts.Tracker tracker = new ActivityAlerts.Tracker();
        assertFalse(tracker.changed("a:1", true, 0));
        assertTrue(tracker.changed("a:1", false, 10 * MINUTE));
        assertFalse(tracker.changed("a:1", false, 11 * MINUTE));
    }

    @Test
    public void togglesWithinTwoMinutesOfTheLastAreIgnored() {
        ActivityAlerts.Tracker tracker = new ActivityAlerts.Tracker();
        tracker.changed("a:1", false, 0);
        assertTrue(tracker.changed("a:1", true, MINUTE));
        assertFalse(tracker.changed("a:1", false, MINUTE + 30_000));
        assertFalse(tracker.changed("a:1", true, 2 * MINUTE));
        assertTrue(tracker.changed("a:1", false, 5 * MINUTE));
    }

    @Test
    public void missingPresenceOnlyEndsAKnownActiveState() {
        ActivityAlerts.Tracker tracker = new ActivityAlerts.Tracker();
        assertFalse(tracker.changed("a:1", null, 0));
        assertFalse(tracker.changed("a:1", true, MINUTE));
        assertTrue(tracker.changed("a:1", null, 10 * MINUTE));
        assertFalse(tracker.changed("a:1", null, 20 * MINUTE));
        // A person never seen stays unknown, so their first known state is still not a change.
        assertFalse(tracker.changed("a:2", null, 0));
        assertFalse(tracker.changed("a:2", true, MINUTE));
    }

    @Test
    public void anEmptyReplacementEndsAnActiveStateAndAllowsTheNextComeback() {
        ActivityAlerts.Tracker tracker = new ActivityAlerts.Tracker();
        tracker.changed("a:1", true, 0);
        // Nobody is active in the new snapshot, so the person has no presence.
        assertTrue(tracker.changed("a:1", null, 10 * MINUTE));
        assertTrue(tracker.changed("a:1", true, 20 * MINUTE));
    }

    @Test
    public void aSessionEndForgetsOnlyThatAccount() {
        ActivityAlerts.Tracker tracker = new ActivityAlerts.Tracker();
        tracker.changed("a:1", true, 0);
        tracker.changed("b:1", true, 0);
        tracker.forget("a:");
        assertFalse(tracker.changed("a:1", false, 10 * MINUTE));
        assertTrue(tracker.changed("b:1", false, 10 * MINUTE));
    }

    @Test
    public void typingAndReadNotifyOncePerTwoMinutesPerKey() {
        ActivityAlerts.Tracker tracker = new ActivityAlerts.Tracker();
        assertTrue(tracker.due("a:1:typing", 0));
        assertFalse(tracker.due("a:1:typing", MINUTE));
        assertTrue(tracker.due("a:1:read", MINUTE));
        assertTrue(tracker.due("a:1:typing", 2 * MINUTE));
    }

    @Test
    public void onlyRecentReadsCount() {
        long now = 1_700_000_000_000L;
        assertTrue(ActivityAlerts.recent(String.valueOf(now - MINUTE), now));
        assertFalse(ActivityAlerts.recent(String.valueOf(now - 6 * MINUTE), now));
        assertFalse(ActivityAlerts.recent("", now));
        assertFalse(ActivityAlerts.recent(null, now));
    }

    @Test
    public void refreshIntervalIsInstagramsUnlessMinutesArePicked() {
        assertEquals(30_000L, ActivityAlerts.refreshMs("default", 30_000L));
        assertEquals(5 * MINUTE, ActivityAlerts.refreshMs("5", 30_000L));
        assertEquals(30_000L, ActivityAlerts.refreshMs("0", 30_000L));
        assertEquals(30_000L, ActivityAlerts.refreshMs(null, 30_000L));
    }

    @Test
    public void entriesHoldThePersonAndTheChat() {
        String entry = ActivityAlerts.entry("12", "3400");
        assertEquals("12", ActivityAlerts.userOf(entry));
        assertEquals("3400", ActivityAlerts.chatOf(entry));
        assertNull(ActivityAlerts.userOf("broken"));
    }

    @Test
    public void onlyOneToOneChatsHaveAPerson() {
        assertEquals("2", ActivityAlerts.otherUser(Arrays.asList("1", "2"), "1"));
        assertEquals("2", ActivityAlerts.otherUser(Collections.singletonList("2"), "1"));
        assertNull(ActivityAlerts.otherUser(Arrays.asList("1", "2", "3"), "1"));
        assertNull(ActivityAlerts.otherUser(Arrays.asList("1", "2"), null));
    }

    @Test
    public void theMenuEntryUsesAButtonNoOtherEntryBorrows() {
        assertEquals("LAUNCH_INTEROP_UPGRADE_FLOW", ActivityAlerts.freeButton(new HashSet<>(
                Arrays.asList("THREAD_LEVEL_DEBUG", "DETAILS", "REPLAY_VISUAL_MESSAGE"))));
        assertEquals("MUTE_NOTIFICATIONS", ActivityAlerts.freeButton(new HashSet<>(
                Collections.singletonList("LAUNCH_INTEROP_UPGRADE_FLOW"))));
        assertNull(ActivityAlerts.freeButton(new HashSet<>(Arrays.asList(ActivityAlerts.MENU_BUTTONS))));
    }
}
