package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.instagram.model.direct.DirectThreadKey;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class PinnedRecipientsTest {
    private static PinnedRecipients.Pins pins() {
        Map<String, String> chats = new LinkedHashMap<>();
        chats.put("group", "");
        chats.put("direct", "friend");
        return new PinnedRecipients.Pins(chats);
    }

    private static DirectThreadKey key(String chat, String... people) {
        DirectThreadKey key = new DirectThreadKey();
        key.A00 = chat;
        key.A02 = Arrays.asList(people);
        return key;
    }

    @Test
    public void aRecipientIsPinnedByItsChatOrAsTheOtherPersonOfAPinnedChat() {
        PinnedRecipients.Pins pins = pins();
        assertEquals(0, pins.rankOf(key("group", "a", "b"), "me"));
        assertEquals(1, pins.rankOf(key("direct", "friend"), "me"));
        // A person-only suggestion of the pinned one-to-one chat.
        assertEquals(1, pins.rankOf(key(null, "friend"), "me"));
        assertEquals(-1, pins.rankOf(key(null, "stranger"), "me"));
        assertEquals(-1, pins.rankOf(key("other", "friend", "stranger"), "me"));
        assertEquals(-1, pins.rankOf(null, "me"));
        assertEquals(1, pins.personRank("friend"));
        assertEquals(-1, pins.personRank(null));
    }

    @Test
    public void pinnedItemsComeFirstInPinOrderAndTheRestKeepTheirOrder() {
        List<String> items = Arrays.asList("a", "direct", "b", "group", "c");
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranks.put("group", 0);
        ranks.put("direct", 1);
        List<String> ordered = PinnedRecipients.pinnedFirst(items, item -> ranks.getOrDefault(item, -1));
        assertEquals(Arrays.asList("group", "direct", "a", "b", "c"), ordered);
    }

    @Test
    public void aListWithoutPinnedItemsIsReturnedAsItIs() {
        List<String> items = Collections.unmodifiableList(Arrays.asList("a", "b"));
        assertSame(items, PinnedRecipients.pinnedFirst(items, item -> -1));
    }
}
