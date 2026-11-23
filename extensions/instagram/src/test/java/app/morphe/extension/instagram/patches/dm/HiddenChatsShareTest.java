package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class HiddenChatsShareTest {
    private static final Set<String> CHATS = new HashSet<>(Arrays.asList("group", "direct"));
    private static final Map<String, String> USERS = Collections.singletonMap("direct", "friend");

    @Test
    public void aRecipientWithAChatIdIsHiddenOnlyWhenItsChatIs() {
        assertTrue(HiddenChatsShare.isHidden("group", Arrays.asList("a", "b"), CHATS, USERS));
        assertTrue(HiddenChatsShare.isHidden("direct", null, CHATS, USERS));
        // Another chat with the hidden person stays, since only that chat was hidden.
        assertFalse(HiddenChatsShare.isHidden("other", Collections.singletonList("friend"), CHATS, USERS));
    }

    @Test
    public void aRecipientWithoutAChatIdIsHiddenOnlyWhenItIsTheOtherPersonOfAHiddenChat() {
        assertTrue(HiddenChatsShare.isHidden(null, Collections.singletonList("friend"), CHATS, USERS));
        assertFalse(HiddenChatsShare.isHidden(null, Collections.singletonList("stranger"), CHATS, USERS));
        assertFalse(HiddenChatsShare.isHidden(null, Arrays.asList("friend", "stranger"), CHATS, USERS));
        assertFalse(HiddenChatsShare.isHidden(null, Collections.emptyList(), CHATS, USERS));
        assertFalse(HiddenChatsShare.isHidden(null, null, CHATS, USERS));
    }

    @Test
    public void theOtherPersonIsTheOnlyOneBesidesTheAccount() {
        assertEquals("friend", HiddenChatsShare.otherUser(Collections.singletonList("friend"), "me"));
        assertEquals("friend", HiddenChatsShare.otherUser(Arrays.asList("me", "friend"), "me"));
        assertNull(HiddenChatsShare.otherUser(Arrays.asList("friend", "stranger"), "me"));
        assertNull(HiddenChatsShare.otherUser(Collections.singletonList("me"), "me"));
        assertNull(HiddenChatsShare.otherUser(null, "me"));
    }
}
