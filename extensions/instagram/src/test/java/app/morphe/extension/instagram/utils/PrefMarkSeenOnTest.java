package app.morphe.extension.instagram.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

public class PrefMarkSeenOnTest {
    @Test
    public void theOldSwitchKeepsMarkingOnEveryMessageSent() {
        assertEquals(new HashSet<>(Arrays.asList("reply", "send")), Pref.migratedMarkSeenOn(true));
        assertTrue(Pref.migratedMarkSeenOn(false).isEmpty());
    }
}
