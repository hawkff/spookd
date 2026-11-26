package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.instagram.model.direct.DirectThreadKey;
import com.instagram.user.model.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.Test;

public class PinnedRecipientsTest {
    private static final Predicate<String> ANY = pin -> true;
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

    private static Map<String, Integer> ranks() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranks.put("group", 0);
        ranks.put("direct", 1);
        return ranks;
    }

    private static String self(String item) {
        return item.startsWith("pin:") ? item.substring(4) : null;
    }

    @Test
    public void aRecipientIsPinnedByItsChatOrAsTheOtherPersonOfAPinnedChat() {
        PinnedRecipients.Pins pins = pins();
        assertEquals("group", pins.chatOf(key("group", "a", "b"), "me"));
        assertEquals("direct", pins.chatOf(key("direct", "friend"), "me"));
        // A person-only suggestion of the pinned one-to-one chat.
        assertEquals("direct", pins.chatOf(key(null, "friend"), "me"));
        assertNull(pins.chatOf(key(null, "stranger"), "me"));
        assertNull(pins.chatOf(key("other", "friend", "stranger"), "me"));
        assertNull(pins.chatOf(null, "me"));
        assertEquals(Integer.valueOf(1), pins.people.get("friend"));
    }

    @Test
    public void pinnedItemsComeFirstInPinOrderAndTheRestKeepTheirOrder() {
        List<String> items = Arrays.asList("a", "pin:direct", "b", "pin:group", "c");
        List<String> ordered = PinnedRecipients.pinFirst(items, ranks(), PinnedRecipientsTest::self, new HashMap<>(), true, ANY);
        assertEquals(Arrays.asList("pin:group", "pin:direct", "a", "b", "c"), ordered);
    }

    @Test
    public void aListWithoutPinnedItemsIsReturnedAsItIs() {
        List<String> items = Collections.unmodifiableList(Arrays.asList("a", "b"));
        assertSame(items, PinnedRecipients.pinFirst(items, ranks(), PinnedRecipientsTest::self, new HashMap<>(), true, ANY));
    }

    @Test
    public void emptySuggestionsGetThePinnedRecipientsListedBefore() {
        Map<String, String> seen = new HashMap<>();
        PinnedRecipients.pinFirst(Arrays.asList("a", "pin:direct"), ranks(), PinnedRecipientsTest::self, seen, true, ANY);
        assertEquals(Collections.singletonList("pin:direct"),
                PinnedRecipients.pinFirst(new ArrayList<>(), ranks(), PinnedRecipientsTest::self, seen, true, ANY));
    }

    @Test
    public void clearedRecentsKeepThePinnedPersonButATypedSearchAddsNothing() {
        Map<String, String> seen = new HashMap<>();
        PinnedRecipients.pinFirst(Arrays.asList("pin:direct", "a"), ranks(), PinnedRecipientsTest::self, seen, true, ANY);
        List<String> cleared = Collections.emptyList();
        assertEquals(Collections.singletonList("pin:direct"),
                PinnedRecipients.pinFirst(cleared, ranks(), PinnedRecipientsTest::self, seen, true, ANY));
        assertSame(cleared, PinnedRecipients.pinFirst(cleared, ranks(), PinnedRecipientsTest::self, seen, false, ANY));
    }

    @Test
    public void aListedPinIsNotAddedTwice() {
        Map<String, String> seen = new HashMap<>();
        seen.put("direct", "pin:direct");
        List<String> ordered = PinnedRecipients.pinFirst(Arrays.asList("a", "pin:direct"), ranks(),
                PinnedRecipientsTest::self, seen, true, ANY);
        assertEquals(Arrays.asList("pin:direct", "a"), ordered);
    }

    @Test
    public void anotherAccountDoesNotGetWhatTheFirstOneListed() {
        Map<String, Map<String, Object>> caches = new HashMap<>();
        Map<String, Object> first = PinnedRecipients.cacheOf(caches, "first");
        first.put("direct", "pin:direct");
        Map<String, Object> second = PinnedRecipients.cacheOf(caches, "second");
        List<Object> none = new ArrayList<>();
        assertSame(none, PinnedRecipients.pinFirst(none, ranks(), item -> null, second, true, ANY));
        assertSame(first, PinnedRecipients.cacheOf(caches, "first"));
    }

    @Test
    public void theProfileIsFoundInTheEntryTheEntityOrAKeywordItHolds() throws Exception {
        User user = new User("friend");
        assertSame(user, PinnedRecipients.userIn(new Holder(user)));
        assertSame(user, PinnedRecipients.userIn(new Holder(new Holder(user))));
        // A profile shown as a keyword: entry, then entity, then keyword.
        assertSame(user, PinnedRecipients.userIn(new Holder(new Holder(new Holder(user)))));
        assertNull(PinnedRecipients.userIn(new Holder("#tag")));
        assertNull(PinnedRecipients.userIn(null));
    }

    /** One object field, like Instagram's entries, entities and keywords. */
    static final class Holder {
        final Object value;

        Holder(Object value) {
            this.value = value;
        }
    }

    @Test
    public void aPinnedChatHiddenAfterItWasListedIsNotAddedBackUntilHiddenChatsOpen() {
        Map<String, String> seen = new HashMap<>();
        PinnedRecipients.pinFirst(Arrays.asList("pin:direct", "a"), ranks(), PinnedRecipientsTest::self, seen, true, ANY);
        PinnedRecipients.Pins pins = pins();
        Set<String> hiddenChats = new HashSet<>(Collections.singletonList("direct"));
        Map<String, String> hiddenUsers = Collections.singletonMap("direct", "friend");
        Predicate<String> closed = PinnedRecipients.notHidden(hiddenChats, hiddenUsers, chat -> chat, pins.chats::get);

        List<String> empty = Collections.emptyList();
        assertSame(empty, PinnedRecipients.pinFirst(empty, ranks(), PinnedRecipientsTest::self, seen, true, closed));
        // What Instagram lists itself still moves up; the hidden chats filter decides on it afterwards.
        assertEquals(Arrays.asList("pin:direct", "a"), PinnedRecipients.pinFirst(Arrays.asList("a", "pin:direct"),
                ranks(), PinnedRecipientsTest::self, seen, true, closed));
        // With the hidden chats open, everything may come back.
        assertEquals(Collections.singletonList("pin:direct"),
                PinnedRecipients.pinFirst(empty, ranks(), PinnedRecipientsTest::self, seen, true, ANY));
    }

    @Test
    public void aRecentPersonHiddenThroughAnotherChatIsNotAddedBack() {
        PinnedRecipients.Pins pins = pins();
        Map<String, String> hiddenUsers = Collections.singletonMap("another-chat", "friend");
        Predicate<String> closed = PinnedRecipients.notHidden(Collections.emptySet(), hiddenUsers,
                pins.chatOfPerson::get, person -> person);
        Map<String, Integer> people = Collections.singletonMap("friend", 1);
        Map<String, String> seen = new HashMap<>();
        seen.put("friend", "pin:friend");
        List<String> cleared = Collections.emptyList();
        assertSame(cleared, PinnedRecipients.pinFirst(cleared, people, PinnedRecipientsTest::self, seen, true, closed));
    }
}
