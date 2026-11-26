package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import org.junit.Test;

public class DmGhostChatsTest {
    private static final Map<String, String> CHOSEN = Collections.singletonMap("1", "first");

    @Test
    public void allChatsModeCoversChosenAndOtherChats() {
        assertTrue(DmGhostChats.appliesTo(DmGhostChats.MODE_EVERYONE, "1", CHOSEN));
        assertTrue(DmGhostChats.appliesTo(DmGhostChats.MODE_EVERYONE, "2", CHOSEN));
    }

    @Test
    public void exceptModeSendsTheReceiptsOfChosenChats() {
        assertFalse(DmGhostChats.appliesTo(DmGhostChats.MODE_EXCEPT_LISTED, "1", CHOSEN));
        assertTrue(DmGhostChats.appliesTo(DmGhostChats.MODE_EXCEPT_LISTED, "2", CHOSEN));
    }

    @Test
    public void onlyModeCoversChosenChatsOnly() {
        assertTrue(DmGhostChats.appliesTo(DmGhostChats.MODE_ONLY_LISTED, "1", CHOSEN));
        assertFalse(DmGhostChats.appliesTo(DmGhostChats.MODE_ONLY_LISTED, "2", CHOSEN));
    }

    @Test
    public void anUnknownChatOrModeStaysAnonymous() {
        for (String mode : new String[]{DmGhostChats.MODE_EXCEPT_LISTED, DmGhostChats.MODE_ONLY_LISTED}) {
            assertTrue(mode, DmGhostChats.appliesTo(mode, null, CHOSEN));
        }
        assertTrue(DmGhostChats.appliesTo("removed_mode", "2", CHOSEN));
        assertTrue(DmGhostChats.appliesTo(null, "2", CHOSEN));
    }

    @Test
    public void anUnknownAccountStaysAnonymous() {
        assertTrue(DmGhostChats.appliesTo(DmGhostChats.MODE_ONLY_LISTED, null, "2"));
        assertTrue(DmGhostChats.appliesTo(DmGhostChats.MODE_ONLY_LISTED, "", "2"));
    }

    @Test
    public void theMenuEntryLeavesPinRecipientsItsButtonsNextToANativeOne() {
        // Pin recipients borrows DETAILS or REPLAY_VISUAL_MESSAGE; with one of them native it needs the other.
        assertEquals("THREAD_LEVEL_DEBUG", DmGhostChats.freeButton(new HashSet<>(Arrays.asList("DETAILS"))));
        assertEquals("THREAD_LEVEL_DEBUG", DmGhostChats.freeButton(new HashSet<>(Arrays.asList("REPLAY_VISUAL_MESSAGE"))));
        // With the debug entry already listed (debug mode), the entry falls back to a free borrowed one.
        assertEquals("REPLAY_VISUAL_MESSAGE",
                DmGhostChats.freeButton(new HashSet<>(Arrays.asList("THREAD_LEVEL_DEBUG", "DETAILS"))));
        assertNull(DmGhostChats.freeButton(new HashSet<>(Arrays.asList(DmGhostChats.MENU_BUTTONS))));
    }
}
