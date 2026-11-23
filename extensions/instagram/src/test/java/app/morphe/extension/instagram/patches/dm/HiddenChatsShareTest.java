package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.instagram.model.direct.DirectThreadKey;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.After;
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

    @Test
    public void aHiddenPersonAddedAfterTheFirstFilterIsLeftOutOfTheFinishedList() {
        List<Object> suggested = new ArrayList<>(Arrays.asList(
                Target.person("friend"), Target.chat("group"), Target.person("stranger")));
        List<?> first = HiddenChatsShare.keep(suggested, CHATS, USERS);
        assertEquals(1, first.size());

        // Instagram builds a new target for the media author after the first filter and puts it on top.
        List<Object> finished = new ArrayList<>(first);
        finished.add(0, Target.person("friend"));
        Target stranger = (Target) first.get(0);

        assertEquals(Collections.singletonList(stranger), HiddenChatsShare.keep(finished, CHATS, USERS));
        assertSame(first, HiddenChatsShare.keep(first, CHATS, USERS));
    }

    @After
    public void restoreBackfill() {
        HiddenChatsShare.backfillCooldownMs = 30_000;
        HiddenChatsShare.backfillExecutor = task -> new Thread(task).start();
    }

    @Test
    public void aChatTheStoreDoesNotHoldYetIsLookedUpAgainOnALaterRun() {
        // An account upgraded with hidden chats and no users_<account> key yet.
        HiddenChatsShare.backfillCooldownMs = 0;
        HiddenChatsShare.backfillExecutor = Runnable::run;
        Set<String> hidden = new HashSet<>(Arrays.asList("direct", "group"));
        Map<String, Target> store = new HashMap<>();
        Map<String, String> people = new HashMap<>();
        List<String> asked = new ArrayList<>();
        Function<String, Object> threads = chat -> {
            asked.add(chat);
            return store.get(chat);
        };

        HiddenChatsShare.requestBackfill("me", hidden, people, threads, people::putAll);
        assertEquals(new HashSet<>(hidden), new HashSet<>(asked));
        assertTrue(people.isEmpty());

        store.put("direct", Target.cached("direct", "me", "friend"));
        store.put("group", Target.cached("group", "friend", "stranger"));
        HiddenChatsShare.requestBackfill("me", hidden, people, threads, people::putAll);
        assertEquals(Collections.singletonMap("direct", "friend"), people);
        assertTrue(HiddenChatsShare.isHidden(null, Collections.singletonList("friend"), hidden, people));

        // Both are settled now: the group by its chat id and the person by the kept map.
        asked.clear();
        HiddenChatsShare.requestBackfill("me", hidden, people, threads, people::putAll);
        assertTrue(asked.isEmpty());
    }

    @Test
    public void theCallerNeverTouchesTheStoreAndOnlyOneRunIsQueued() {
        HiddenChatsShare.backfillCooldownMs = 0;
        List<Runnable> queued = new ArrayList<>();
        HiddenChatsShare.backfillExecutor = queued::add;
        List<String> asked = new ArrayList<>();
        Map<String, String> people = new HashMap<>();
        Set<String> hidden = Collections.singleton("direct");
        Function<String, Object> threads = chat -> {
            asked.add(chat);
            return Target.cached("direct", "friend");
        };

        HiddenChatsShare.requestBackfill("busy", hidden, people, threads, people::putAll);
        HiddenChatsShare.requestBackfill("busy", hidden, people, threads, people::putAll);
        assertTrue(asked.isEmpty());
        assertEquals(1, queued.size());

        queued.get(0).run();
        assertEquals(Collections.singletonList("direct"), asked);
        assertEquals(Collections.singletonMap("direct", "friend"), people);
    }

    /** A recipient that makes its key on demand, like Instagram's share targets. */
    public static final class Target {
        private final DirectThreadKey key = new DirectThreadKey();

        static Target person(String user) {
            Target target = new Target();
            target.key.A02 = Collections.singletonList(user);
            return target;
        }

        static Target cached(String chat, String... people) {
            Target target = chat(chat);
            target.key.A02 = Arrays.asList(people);
            return target;
        }

        static Target chat(String chat) {
            Target target = new Target();
            target.key.A00 = chat;
            return target;
        }

        public DirectThreadKey key() {
            return key;
        }
    }
}
