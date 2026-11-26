package app.morphe.extension.instagram.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrefInstantsTest {
    @Test
    public void turnOnAllGhostModesKeepsInstantsUnseen() {
        assertTrue(Pref.instantsDontMarkSeen(true, false, true));
        assertTrue(Pref.instantsDontMarkSeen(true, true, false));
        assertFalse(Pref.instantsDontMarkSeen(true, false, false));
        // Without the patch there is no seen hook to apply it.
        assertFalse(Pref.instantsDontMarkSeen(false, true, true));
    }
}
