/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.sharedPreference.BaseSharedPref;

/** The chats each account has hidden, kept per account id so accounts never see each other's list. */
public class HiddenChatsPref extends BaseSharedPref {

    private static final HiddenChatsPref INSTANCE = new HiddenChatsPref();

    private HiddenChatsPref() {
        super("piko_hidden_chats");
    }

    private static final String KEY_PREFIX = "chats_";
    private static final Map<String, Set<String>> idsByUser = new HashMap<>();

    private static String key(String userId) {
        return KEY_PREFIX + userId;
    }

    /** The ids of the accounts that have saved hidden chats. */
    public static Set<String> users() {
        Set<String> users = new LinkedHashSet<>();
        JSONObject all = INSTANCE.all();
        if (all == null) return users;
        for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (key.startsWith(KEY_PREFIX)) users.add(key.substring(KEY_PREFIX.length()));
        }
        return users;
    }

    private static final Pattern THREAD_ID = Pattern.compile("mThreadId='([^']*)'");

    /** The id that names a chat; the rest of a saved key (like the short id) is not always there. */
    public static String idOf(String chat) {
        Matcher matcher = THREAD_ID.matcher(chat);
        return matcher.find() ? matcher.group(1) : chat;
    }

    /** The ids of the chats an account has hidden. */
    public static synchronized Set<String> ids(String userId) {
        Set<String> ids = idsByUser.get(userId);
        if (ids != null) return ids;
        ids = new HashSet<>();
        for (String chat : get(userId)) ids.add(idOf(chat));
        ids = Collections.unmodifiableSet(ids);
        idsByUser.put(userId, ids);
        return ids;
    }

    public static Set<String> get(String userId) {
        Set<String> chats = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(INSTANCE.getString(key(userId), "[]"));
            for (int i = 0; i < array.length(); i++) chats.add(array.getString(i));
        } catch (Exception ignored) { }
        return chats;
    }

    private static void save(String userId, Set<String> chats) {
        INSTANCE.setString(key(userId), new JSONArray(chats).toString());
    }

    public static synchronized void add(String userId, String chat) {
        Set<String> chats = get(userId);
        String id = idOf(chat);
        chats.removeIf(saved -> idOf(saved).equals(id));
        chats.add(chat);
        save(userId, chats);
        idsByUser.remove(userId);
    }

    public static synchronized void remove(String userId, String chat) {
        Set<String> chats = get(userId);
        String id = idOf(chat);
        if (chats.removeIf(saved -> idOf(saved).equals(id))) save(userId, chats);
        idsByUser.remove(userId);
        if (hiddenUsers(userId).containsKey(id)) {
            Map<String, String> users = new HashMap<>(hiddenUsers(userId));
            users.remove(id);
            saveUsers(userId, users);
        }
    }

    // The other person of each hidden one-to-one chat, by chat id: suggestions name only that person, not the chat.
    private static final String USERS_PREFIX = "users_";
    private static final Map<String, Map<String, String>> usersByAccount = new HashMap<>();

    /** The other person of each hidden one-to-one chat of an account, by chat id; read from disk once per account. */
    public static synchronized Map<String, String> hiddenUsers(String userId) {
        Map<String, String> users = usersByAccount.get(userId);
        if (users != null) return users;
        users = new HashMap<>();
        try {
            JSONObject saved = new JSONObject(INSTANCE.getString(USERS_PREFIX + userId, "{}"));
            for (Iterator<String> chats = saved.keys(); chats.hasNext(); ) {
                String chat = chats.next();
                users.put(chat, saved.getString(chat));
            }
        } catch (Exception ignored) { }
        users = Collections.unmodifiableMap(users);
        usersByAccount.put(userId, users);
        return users;
    }

    /** Keeps the other person of a hidden one-to-one chat; writes only when the chat is hidden and the person is new. */
    public static synchronized void addUser(String userId, String chatId, String otherUser) {
        if (!ids(userId).contains(chatId) || otherUser.equals(hiddenUsers(userId).get(chatId))) return;
        Map<String, String> users = new HashMap<>(hiddenUsers(userId));
        users.put(chatId, otherUser);
        saveUsers(userId, users);
    }

    private static void saveUsers(String userId, Map<String, String> users) {
        INSTANCE.setString(USERS_PREFIX + userId, new JSONObject(users).toString());
        usersByAccount.put(userId, Collections.unmodifiableMap(users));
    }
}
