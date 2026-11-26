package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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

import org.junit.After;
import org.junit.Test;

public class PinnedRecipientsTest {
    private static final Predicate<String> ANY = pin -> true;
    private static final long NOW = 1_000_000L;
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
        List<String> ordered = PinnedRecipients.pinFirst(items, ranks(), PinnedRecipientsTest::self, new HashMap<>(), true, ANY, NOW);
        assertEquals(Arrays.asList("pin:group", "pin:direct", "a", "b", "c"), ordered);
    }

    @Test
    public void aListWithoutPinnedItemsIsReturnedAsItIs() {
        List<String> items = Collections.unmodifiableList(Arrays.asList("a", "b"));
        assertSame(items, PinnedRecipients.pinFirst(items, ranks(), PinnedRecipientsTest::self, new HashMap<>(), true, ANY, NOW));
    }

    @Test
    public void emptySuggestionsGetThePinnedRecipientsListedBefore() {
        Map<String, PinnedRecipients.Kept<String>> seen = new HashMap<>();
        PinnedRecipients.pinFirst(Arrays.asList("a", "pin:direct"), ranks(), PinnedRecipientsTest::self, seen, true, ANY, NOW);
        assertEquals(Collections.singletonList("pin:direct"),
                PinnedRecipients.pinFirst(new ArrayList<>(), ranks(), PinnedRecipientsTest::self, seen, true, ANY, NOW));
    }

    @Test
    public void clearedRecentsKeepThePinnedPersonButATypedSearchAddsNothing() {
        Map<String, PinnedRecipients.Kept<String>> seen = new HashMap<>();
        PinnedRecipients.pinFirst(Arrays.asList("pin:direct", "a"), ranks(), PinnedRecipientsTest::self, seen, true, ANY, NOW);
        List<String> cleared = Collections.emptyList();
        assertEquals(Collections.singletonList("pin:direct"),
                PinnedRecipients.pinFirst(cleared, ranks(), PinnedRecipientsTest::self, seen, true, ANY, NOW));
        assertSame(cleared, PinnedRecipients.pinFirst(cleared, ranks(), PinnedRecipientsTest::self, seen, false, ANY, NOW));
    }

    @Test
    public void aListedPinIsNotAddedTwice() {
        Map<String, PinnedRecipients.Kept<String>> seen = new HashMap<>();
        seen.put("direct", new PinnedRecipients.Kept<>("pin:direct", NOW));
        List<String> ordered = PinnedRecipients.pinFirst(Arrays.asList("a", "pin:direct"), ranks(),
                PinnedRecipientsTest::self, seen, true, ANY, NOW);
        assertEquals(Arrays.asList("pin:direct", "a"), ordered);
    }

    @After
    public void dropKept() {
        PinnedRecipients.clearCaches();
        PinnedRecipients.cacheAccount = null;
        PinnedRecipients.sessionKnown = false;
        PinnedRecipients.pinsByAccount.clear();
        PinnedRecipients.enabled = () -> false;
        PinnedRecipients.hiddenChatsOn = () -> false;
        PinnedRecipients.clock = () -> NOW;
    }

    /** Search returns for an account with "direct" pinned, while pinning is on. */
    private static void pinFor(String account) {
        PinnedRecipients.enabled = () -> true;
        PinnedRecipients.hiddenChatsOn = () -> false;
        PinnedRecipients.clock = () -> NOW;
        PinnedRecipients.pinsByAccount.put(account, pins());
    }

    private static String person(Object entry) {
        return "friend".equals(entry) ? "friend" : null;
    }

    @SuppressWarnings("rawtypes")
    private static List finish(PinnedRecipients.RecentSearch search, List<Object> results) {
        return PinnedRecipients.finishRecentSearch(search, results, PinnedRecipientsTest::person);
    }

    @Test
    public void aSearchOfTheCurrentAccountPutsThePinnedPersonFirstAndKeepsTheEntry() {
        pinFor("first");
        PinnedRecipients.sessionChanged("first");
        PinnedRecipients.RecentSearch search = PinnedRecipients.beginRecentSearch("first", true);
        assertEquals(Arrays.asList("friend", "a"), finish(search, Arrays.asList("a", "friend")));
        assertEquals(1, PinnedRecipients.recentEntries.size());
    }

    @Test
    public void aSearchRunningWhilePinningIsTurnedOffKeepsNothing() {
        pinFor("first");
        PinnedRecipients.sessionChanged("first");
        PinnedRecipients.RecentSearch search = PinnedRecipients.beginRecentSearch("first", true);
        PinnedRecipients.clearCaches();
        List<Object> results = Arrays.asList("a", "friend");
        assertSame(results, finish(search, results));
        assertTrue(PinnedRecipients.recentEntries.isEmpty());

        // Turned off before the return, without a clear having run yet.
        PinnedRecipients.RecentSearch next = PinnedRecipients.beginRecentSearch("first", true);
        PinnedRecipients.enabled = () -> false;
        assertSame(results, finish(next, results));
        assertTrue(PinnedRecipients.recentEntries.isEmpty());
    }

    @Test
    public void aSearchOfTheAccountBeforeASwitchKeepsNothingAndDoesNotSwitchBack() {
        pinFor("first");
        pinFor("second");
        PinnedRecipients.sessionChanged("first");
        PinnedRecipients.RecentSearch search = PinnedRecipients.beginRecentSearch("first", true);
        PinnedRecipients.sessionChanged("second");
        List<Object> results = Arrays.asList("a", "friend");
        assertSame(results, finish(search, results));
        assertEquals("second", PinnedRecipients.cacheAccount);
        assertTrue(PinnedRecipients.recentEntries.isEmpty());
        // A late list of the earlier account is not even started.
        assertNull(PinnedRecipients.beginRecentSearch("first", true));
        assertEquals("second", PinnedRecipients.cacheAccount);
    }

    @Test
    public void anotherAccountOrALogoutDropsWhatWasKept() {
        PinnedRecipients.sessionChanged("first");
        PinnedRecipients.shareTargets.put("direct", new PinnedRecipients.Kept<>("pin:direct", NOW));
        PinnedRecipients.recentEntries.put("friend", new PinnedRecipients.Kept<>("pin:friend", NOW));
        PinnedRecipients.sessionChanged("first");
        assertEquals(1, PinnedRecipients.shareTargets.size());

        PinnedRecipients.sessionChanged("second");
        assertTrue(PinnedRecipients.shareTargets.isEmpty());
        assertTrue(PinnedRecipients.recentEntries.isEmpty());

        PinnedRecipients.shareTargets.put("direct", new PinnedRecipients.Kept<>("pin:direct", NOW));
        PinnedRecipients.sessionChanged(null);
        assertTrue(PinnedRecipients.shareTargets.isEmpty());
        assertNull(PinnedRecipients.beginRecentSearch("second", true));
    }

    @Test
    public void withoutASessionReportTheFirstListedAccountIsUsed() {
        assertEquals("first", PinnedRecipients.beginRecentSearch("first", true).account);
        assertEquals("first", PinnedRecipients.cacheAccount);
    }

    @Test
    public void aKeptEntryExpiresThirtyMinutesAfterInstagramLastListedIt() {
        Map<String, PinnedRecipients.Kept<String>> seen = new HashMap<>();
        PinnedRecipients.pinFirst(Arrays.asList("pin:direct"), ranks(), PinnedRecipientsTest::self, seen, true, ANY, NOW);
        List<String> empty = Collections.emptyList();
        long almost = NOW + PinnedRecipients.KEEP_MS - 1;
        // Adding it back does not make it fresh again.
        assertEquals(Collections.singletonList("pin:direct"),
                PinnedRecipients.pinFirst(empty, ranks(), PinnedRecipientsTest::self, seen, true, ANY, almost));
        assertSame(empty, PinnedRecipients.pinFirst(empty, ranks(), PinnedRecipientsTest::self, seen, true, ANY,
                NOW + PinnedRecipients.KEEP_MS));
        assertTrue(seen.isEmpty());

        // Listed again by Instagram, it is kept from that time.
        long later = NOW + 2 * PinnedRecipients.KEEP_MS;
        PinnedRecipients.pinFirst(Arrays.asList("pin:direct"), ranks(), PinnedRecipientsTest::self, seen, true, ANY, later);
        assertEquals(Collections.singletonList("pin:direct"),
                PinnedRecipients.pinFirst(empty, ranks(), PinnedRecipientsTest::self, seen, true, ANY,
                        later + PinnedRecipients.KEEP_MS - 1));
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
        Map<String, PinnedRecipients.Kept<String>> seen = new HashMap<>();
        PinnedRecipients.pinFirst(Arrays.asList("pin:direct", "a"), ranks(), PinnedRecipientsTest::self, seen, true, ANY, NOW);
        PinnedRecipients.Pins pins = pins();
        Set<String> hiddenChats = new HashSet<>(Collections.singletonList("direct"));
        Map<String, String> hiddenUsers = Collections.singletonMap("direct", "friend");
        Predicate<String> closed = PinnedRecipients.notHidden(hiddenChats, hiddenUsers, chat -> chat, pins.chats::get);

        List<String> empty = Collections.emptyList();
        assertSame(empty, PinnedRecipients.pinFirst(empty, ranks(), PinnedRecipientsTest::self, seen, true, closed, NOW));
        // What Instagram lists itself still moves up; the hidden chats filter decides on it afterwards.
        assertEquals(Arrays.asList("pin:direct", "a"), PinnedRecipients.pinFirst(Arrays.asList("a", "pin:direct"),
                ranks(), PinnedRecipientsTest::self, seen, true, closed, NOW));
        // With the hidden chats open, everything may come back.
        assertEquals(Collections.singletonList("pin:direct"),
                PinnedRecipients.pinFirst(empty, ranks(), PinnedRecipientsTest::self, seen, true, ANY, NOW));
    }

    @Test
    public void aRecentPersonHiddenThroughAnotherChatIsNotAddedBack() {
        PinnedRecipients.Pins pins = pins();
        Map<String, String> hiddenUsers = Collections.singletonMap("another-chat", "friend");
        Predicate<String> closed = PinnedRecipients.notHidden(Collections.emptySet(), hiddenUsers,
                pins.chatOfPerson::get, person -> person);
        Map<String, Integer> people = Collections.singletonMap("friend", 1);
        Map<String, PinnedRecipients.Kept<String>> seen = new HashMap<>();
        seen.put("friend", new PinnedRecipients.Kept<>("pin:friend", NOW));
        List<String> cleared = Collections.emptyList();
        assertSame(cleared, PinnedRecipients.pinFirst(cleared, people, PinnedRecipientsTest::self, seen, true, closed, NOW));
    }
}
