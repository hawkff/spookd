/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.sharedPreference.BaseSharedPref;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import com.instagram.common.session.UserSession;
import com.instagram.model.direct.DirectThreadKey;

/**
 * Chats each account pins from a chat's long-press menu in the inbox. Pinned chats are listed first in the share sheet
 * before anything is typed, and the people of pinned one-to-one chats come first in search's recent list. Pins are
 * kept per account id, in the order they were pinned. Instagram's own entries are moved, never built: one it leaves
 * out is added back only when it was listed earlier since Instagram started.
 */
@SuppressWarnings("unused")
public final class PinnedRecipients {
    // The chat menu can only list buttons Instagram already has; if a press were not caught, these only open details or a replay.
    private static final String[] PIN_BUTTONS = {"DETAILS", "REPLAY_VISUAL_MESSAGE"};
    private static final String USER_CLASS = "com.instagram.user.model.User";
    private static final String KEY_PREFIX = "pins_";
    private static final Field[] NO_FIELDS = new Field[0];
    private static final Store STORE = new Store();
    private static final Map<String, Pins> pinsByAccount = new HashMap<>();
    private static final Map<Class<?>, Field[]> objectFields = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> shareTargets = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> recentEntries = new ConcurrentHashMap<>();
    private static final ThreadLocal<RecentSearch> recentSearch = new ThreadLocal<>();
    // The button added to the menu opened last; menus open and are pressed on the main thread.
    private static String menuButton;
    private static boolean menuButtonPins;
    private static boolean labelPending;

    private PinnedRecipients() { }

    private static final class Store extends BaseSharedPref {
        Store() {
            super("spookd_pinned_recipients");
        }
    }

    /** Called on the main thread when a chat's menu opens; returns its buttons with Pin or Unpin added. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List menuOpening(List buttons, Object menu, Object threadKey) {
        menuButton = null;
        labelPending = false;
        try {
            if (!Pref.pinRecipients() || buttons == null || buttons.isEmpty() || !(threadKey instanceof DirectThreadKey)) {
                return buttons;
            }
            String chat = ((DirectThreadKey) threadKey).A00;
            String account = accountOf(HiddenChats.sessionOf(menu));
            if (chat == null || account == null) return buttons;

            Set<String> present = new HashSet<>();
            for (Object button : buttons) present.add(String.valueOf(button));
            Class<? extends Enum> type = ((Enum) buttons.get(0)).getDeclaringClass();
            for (String candidate : PIN_BUTTONS) {
                if (present.contains(candidate)) continue;
                List extended = new ArrayList(buttons);
                extended.add(Enum.valueOf(type, candidate));
                menuButton = candidate;
                menuButtonPins = !pins(account).chats.containsKey(chat);
                labelPending = true;
                return extended;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "PinnedRecipients.menuOpening failed", t);
        }
        return buttons;
    }

    /** Called on the main thread with the labels Instagram made for the menu's buttons; names the added one. */
    @SuppressWarnings("rawtypes")
    public static String[] menuLabels(List buttons, String[] labels) {
        try {
            if (!labelPending || menuButton == null || buttons == null || labels == null) return labels;
            labelPending = false;
            for (int i = 0; i < buttons.size() && i < labels.length; i++) {
                if (menuButton.equals(String.valueOf(buttons.get(i)))) {
                    labels[i] = str(menuButtonPins ? "spookd_pin_recipients_pin" : "spookd_pin_recipients_unpin");
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "PinnedRecipients.menuLabels failed", t);
        }
        return labels;
    }

    /** Called on the main thread when a chat menu button is pressed; true when it was the added one, so nothing else handles it. */
    public static boolean onMenuButton(Object session, Object button, Object threadKey) {
        try {
            if (menuButton == null || !menuButton.equals(String.valueOf(button))) return false;
            String account = accountOf(session);
            if (account == null || !(threadKey instanceof DirectThreadKey)) return false;
            DirectThreadKey key = (DirectThreadKey) threadKey;
            if (key.A00 == null) return false;
            menuButton = null;
            setPinned(account, key.A00, HiddenChatsShare.otherUser(key.A02, account), menuButtonPins);
            PikoUtils.toast(str(menuButtonPins ? "spookd_pin_recipients_pinned" : "spookd_pin_recipients_unpinned"));
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "PinnedRecipients.onMenuButton failed", t);
            return false;
        }
    }

    /**
     * Called on the main thread with the share sheet's suggested recipients before anything is typed; returns them with
     * the pinned ones first. A pinned recipient Instagram leaves out is added back when the sheet listed it earlier
     * since Instagram started; the hidden chats filter runs on the result.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List orderShareTargets(Object adapter, List targets, String query) {
        try {
            if (targets == null || (query != null && !query.isEmpty()) || !Pref.pinRecipients()) return targets;
            String account = accountOf(HiddenChats.sessionOf(adapter));
            if (account == null) return targets;
            Pins pins = pins(account);
            if (pins.chats.isEmpty()) return targets;
            Map<String, Object> seen = cacheOf(shareTargets, account);
            synchronized (seen) {
                return pinFirst((List<Object>) targets, pins.chatRanks,
                        target -> pins.chatOf(HiddenChatsShare.keyOf(target), account), seen, true,
                        addable(account, chat -> chat, pins.chats::get));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "PinnedRecipients.orderShareTargets failed", t);
            return targets;
        }
    }

    /**
     * Called at the start of search's recent list, possibly off the main thread, before Instagram reuses its arguments;
     * keeps the account and whether the query is empty for the returns of the same call.
     */
    public static void noteRecentSearch(Object dataSource, boolean emptyQuery) {
        try {
            String account = Pref.pinRecipients() ? accountOf(HiddenChats.sessionOf(dataSource)) : null;
            // With recents hidden, nothing is added back to the empty list.
            if (account == null) recentSearch.remove();
            else recentSearch.set(new RecentSearch(account, emptyQuery && !Pref.hideRecentSearches()));
        } catch (Throwable t) {
            recentSearch.remove();
            Logger.printException(() -> "PinnedRecipients.noteRecentSearch failed", t);
        }
    }

    /**
     * Called with what search's recent list returns, on the thread that noted the call; returns it with pinned people
     * first. With an empty query, a pinned person Instagram leaves out (like after clearing recents) is added back when
     * the list showed them earlier since Instagram started.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List orderRecentSearches(List results) {
        RecentSearch search = recentSearch.get();
        recentSearch.remove();
        try {
            if (search == null || results == null) return results;
            Pins pins = pins(search.account);
            if (pins.people.isEmpty()) return results;
            Map<String, Object> seen = cacheOf(recentEntries, search.account);
            synchronized (seen) {
                return pinFirst((List<Object>) results, pins.people, PinnedRecipients::userIdOf, seen, search.emptyQuery,
                        addable(search.account, pins.chatOfPerson::get, person -> person));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "PinnedRecipients.orderRecentSearches failed", t);
            return results;
        }
    }

    /**
     * The pinned items first, by the rank of their pin and then in their own order, then the rest. Each pinned item is
     * kept in {@code seen} by its pin; with {@code addMissing}, a pin without an item that {@code mayAdd} accepts gets
     * the one kept for it, so no pin shows twice. The same list comes back when nothing is pinned or added.
     */
    static <T> List<T> pinFirst(List<T> items, Map<String, Integer> ranks, Function<T, String> pinOf,
                                Map<String, T> seen, boolean addMissing, Predicate<String> mayAdd) {
        TreeMap<Integer, List<T>> pinned = new TreeMap<>();
        Set<String> present = new HashSet<>();
        List<T> rest = new ArrayList<>(items.size());
        for (T item : items) {
            String pin = pinOf.apply(item);
            Integer rank = pin == null ? null : ranks.get(pin);
            if (rank == null) {
                rest.add(item);
                continue;
            }
            present.add(pin);
            seen.put(pin, item);
            pinned.computeIfAbsent(rank, r -> new ArrayList<>()).add(item);
        }
        if (addMissing) {
            for (Map.Entry<String, Integer> pin : ranks.entrySet()) {
                T kept = present.contains(pin.getKey()) || !mayAdd.test(pin.getKey()) ? null : seen.get(pin.getKey());
                if (kept != null) pinned.computeIfAbsent(pin.getValue(), r -> new ArrayList<>()).add(kept);
            }
        }
        if (pinned.isEmpty()) return items;
        List<T> ordered = new ArrayList<>(items.size() + pinned.size());
        for (List<T> group : pinned.values()) ordered.addAll(group);
        ordered.addAll(rest);
        return ordered;
    }

    /** The pins of an account: chat id to the other person of a one-to-one chat, or "" for a group. */
    static final class Pins {
        final Map<String, String> chats;
        // Rank of each pinned chat, and of the other person of each pinned one-to-one chat, by pin order.
        final Map<String, Integer> chatRanks = new HashMap<>();
        final Map<String, Integer> people = new HashMap<>();
        final Map<String, String> chatOfPerson = new HashMap<>();

        Pins(Map<String, String> chats) {
            this.chats = Collections.unmodifiableMap(chats);
            int rank = 0;
            for (Map.Entry<String, String> pin : chats.entrySet()) {
                chatRanks.put(pin.getKey(), rank);
                if (!pin.getValue().isEmpty() && people.putIfAbsent(pin.getValue(), rank) == null) {
                    chatOfPerson.put(pin.getValue(), pin.getKey());
                }
                rank++;
            }
        }

        /** The pinned chat of a recipient: its own chat, or, without one pinned, the chat of its one other person. */
        String chatOf(DirectThreadKey key, String account) {
            if (key == null) return null;
            if (key.A00 != null && chats.containsKey(key.A00)) return key.A00;
            String other = HiddenChatsShare.otherUser(key.A02, account);
            return other == null ? null : chatOfPerson.get(other);
        }
    }

    private static final class RecentSearch {
        final String account;
        final boolean emptyQuery;

        RecentSearch(String account, boolean emptyQuery) {
            this.account = account;
            this.emptyQuery = emptyQuery;
        }
    }

    /**
     * Which pins may be added back for an account: while its hidden chats are closed, none whose chat, or whose person
     * through any chat, is hidden. What Instagram lists itself is left to the hidden chats filters.
     */
    private static Predicate<String> addable(String account, Function<String, String> chatOfPin,
                                             Function<String, String> personOfPin) {
        if (!Pref.hiddenChats() || HiddenChats.isOpenFor(account)) return pin -> true;
        return notHidden(HiddenChatsPref.ids(account), HiddenChatsPref.hiddenUsers(account), chatOfPin, personOfPin);
    }

    static Predicate<String> notHidden(Set<String> hiddenChats, Map<String, String> hiddenUsers,
                                       Function<String, String> chatOfPin, Function<String, String> personOfPin) {
        return pin -> {
            String chat = chatOfPin.apply(pin);
            String person = personOfPin.apply(pin);
            return (chat == null || !hiddenChats.contains(chat))
                    && (person == null || person.isEmpty() || !hiddenUsers.containsValue(person));
        };
    }

    /** What Instagram listed for each pin of an account, kept only for this process. */
    static Map<String, Object> cacheOf(Map<String, Map<String, Object>> caches, String account) {
        return caches.computeIfAbsent(account, a -> new HashMap<>());
    }

    private static synchronized Pins pins(String account) {
        Pins pins = pinsByAccount.get(account);
        if (pins != null) return pins;
        Map<String, String> chats = new LinkedHashMap<>();
        try {
            JSONObject saved = new JSONObject(STORE.getString(KEY_PREFIX + account, "{}"));
            for (Iterator<String> keys = saved.keys(); keys.hasNext(); ) {
                String chat = keys.next();
                chats.put(chat, saved.optString(chat, ""));
            }
        } catch (Exception ignored) { }
        pins = new Pins(chats);
        pinsByAccount.put(account, pins);
        return pins;
    }

    private static synchronized void setPinned(String account, String chat, String person, boolean pinned) {
        Map<String, String> chats = new LinkedHashMap<>(pins(account).chats);
        if (pinned) chats.put(chat, person == null ? "" : person);
        else forget(account, chat, chats.remove(chat));
        STORE.setString(KEY_PREFIX + account, new JSONObject(chats).toString());
        pinsByAccount.put(account, new Pins(chats));
    }

    private static void forget(String account, String chat, String person) {
        Map<String, Object> targets = cacheOf(shareTargets, account);
        synchronized (targets) {
            targets.remove(chat);
        }
        if (person == null || person.isEmpty()) return;
        Map<String, Object> entries = cacheOf(recentEntries, account);
        synchronized (entries) {
            entries.remove(person);
        }
    }

    private static String accountOf(Object session) {
        if (!(session instanceof UserSession)) return null;
        String account = ((UserSession) session).getUserId();
        return account == null || account.isEmpty() ? null : account;
    }

    private static String userIdOf(Object result) {
        try {
            Object user = userIn(result);
            return user == null ? null : new UserData(user).getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The profile of a recent search entry: held by the entry, by the entity it holds, or by what that entity holds (a
     * profile shown as a keyword); null for tags and places.
     */
    static Object userIn(Object entry) throws IllegalAccessException {
        return userIn(entry, 3);
    }

    private static Object userIn(Object value, int depth) throws IllegalAccessException {
        if (value == null || depth == 0 || isFramework(value.getClass())) return null;
        Field[] fields = objectFields(value.getClass());
        for (Field field : fields) {
            Object inner = field.get(value);
            if (inner != null && isUser(inner.getClass())) return inner;
        }
        for (Field field : fields) {
            Object user = userIn(field.get(value), depth - 1);
            if (user != null) return user;
        }
        return null;
    }

    private static boolean isUser(Class<?> type) {
        return USER_CLASS.equals(type.getName());
    }

    private static Field[] objectFields(Class<?> type) {
        Field[] fields = objectFields.get(type);
        if (fields != null) return fields;
        List<Field> found = new ArrayList<>();
        for (Class<?> c = type; c != null && !isFramework(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                found.add(field);
            }
        }
        fields = found.isEmpty() ? NO_FIELDS : found.toArray(new Field[0]);
        objectFields.put(type, fields);
        return fields;
    }

    private static boolean isFramework(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.") || name.startsWith("androidx.");
    }
}
