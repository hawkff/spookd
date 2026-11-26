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
import java.util.function.ToIntFunction;

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
 * kept per account id, in the order they were pinned.
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

    /** Called on the main thread with the share sheet's suggested recipients; returns them with the pinned ones first. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List orderShareTargets(Object adapter, List targets, String query) {
        try {
            if (targets == null || targets.size() < 2 || (query != null && !query.isEmpty()) || !Pref.pinRecipients()) {
                return targets;
            }
            String account = accountOf(HiddenChats.sessionOf(adapter));
            if (account == null) return targets;
            Pins pins = pins(account);
            if (pins.chats.isEmpty()) return targets;
            return pinnedFirst((List<Object>) targets, target -> pins.rankOf(HiddenChatsShare.keyOf(target), account));
        } catch (Throwable t) {
            Logger.printException(() -> "PinnedRecipients.orderShareTargets failed", t);
            return targets;
        }
    }

    /** Called with search's recent entries, possibly off the main thread; returns them with pinned people first. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List orderRecentSearches(Object dataSource, List results) {
        try {
            if (results == null || results.size() < 2 || !Pref.pinRecipients()) return results;
            String account = accountOf(HiddenChats.sessionOf(dataSource));
            if (account == null) return results;
            Pins pins = pins(account);
            if (pins.people.isEmpty()) return results;
            return pinnedFirst((List<Object>) results, result -> pins.personRank(userIdOf(result)));
        } catch (Throwable t) {
            Logger.printException(() -> "PinnedRecipients.orderRecentSearches failed", t);
            return results;
        }
    }

    /** The items with a rank first, by rank and then in their own order, then the rest; the same list when none has one. */
    static <T> List<T> pinnedFirst(List<T> items, ToIntFunction<T> rank) {
        TreeMap<Integer, List<T>> pinned = new TreeMap<>();
        List<T> rest = new ArrayList<>(items.size());
        for (T item : items) {
            int r = rank.applyAsInt(item);
            if (r < 0) rest.add(item);
            else pinned.computeIfAbsent(r, k -> new ArrayList<>()).add(item);
        }
        if (pinned.isEmpty()) return items;
        List<T> ordered = new ArrayList<>(items.size());
        for (List<T> group : pinned.values()) ordered.addAll(group);
        ordered.addAll(rest);
        return ordered;
    }

    /** The pins of an account: chat id to the other person of a one-to-one chat, or "" for a group. */
    static final class Pins {
        final Map<String, String> chats;
        final Map<String, Integer> people = new HashMap<>();
        private final Map<String, Integer> chatRanks = new HashMap<>();

        Pins(Map<String, String> chats) {
            this.chats = Collections.unmodifiableMap(chats);
            int rank = 0;
            for (Map.Entry<String, String> pin : chats.entrySet()) {
                chatRanks.put(pin.getKey(), rank);
                if (!pin.getValue().isEmpty()) people.putIfAbsent(pin.getValue(), rank);
                rank++;
            }
        }

        /** A recipient is pinned by its chat, or, without a pinned chat, as the other person of a pinned one-to-one chat. */
        int rankOf(DirectThreadKey key, String account) {
            if (key == null) return -1;
            Integer rank = key.A00 == null ? null : chatRanks.get(key.A00);
            return rank != null ? rank : personRank(HiddenChatsShare.otherUser(key.A02, account));
        }

        int personRank(String person) {
            Integer rank = person == null ? null : people.get(person);
            return rank == null ? -1 : rank;
        }
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
        else chats.remove(chat);
        STORE.setString(KEY_PREFIX + account, new JSONObject(chats).toString());
        pinsByAccount.put(account, new Pins(chats));
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

    /** The profile of a recent search entry: a field of the entry, or of the entity it holds; null for tags and places. */
    static Object userIn(Object entry) throws IllegalAccessException {
        if (entry == null) return null;
        for (Field field : objectFields(entry.getClass())) {
            Object value = field.get(entry);
            if (value == null) continue;
            if (isUser(value.getClass())) return value;
            for (Field inner : objectFields(value.getClass())) {
                if (isUser(inner.getType())) return inner.get(value);
            }
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
