/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import com.instagram.common.session.UserSession;
import com.instagram.model.direct.DirectThreadKey;

/**
 * Leaves the hidden chats out of the recipients the share sheet suggests before anything is typed, and out of the
 * suggestions of the new message screen. Search results are left alone, and nothing is left out while the hidden
 * chats are open. A group is known by its chat id; a person is known by the other person of a hidden one-to-one chat.
 */
@SuppressWarnings("unused")
public final class HiddenChatsShare {
    private static final Method[] NO_METHODS = new Method[0];
    private static final Map<Class<?>, Method[]> keyMethods = new ConcurrentHashMap<>();

    private HiddenChatsShare() { }

    /**
     * Called on the main thread when the share sheet fills its rows, with the query it shows; returns the account whose
     * hidden chats are left out of those lists, or null for a search or when nothing has to be left out.
     */
    public static String defaultListAccount(Object adapter, String query) {
        try {
            return query == null || query.isEmpty() ? accountToFilter(HiddenChats.sessionOf(adapter)) : null;
        } catch (Throwable t) {
            Logger.printException(() -> "HiddenChatsShare.defaultListAccount failed", t);
            return null;
        }
    }

    /** Called on the main thread with each finished list of the share sheet; returns it without the hidden chats of the account. */
    @SuppressWarnings("rawtypes")
    public static List filterTargets(List targets, String account) {
        try {
            if (account == null || targets == null || targets.isEmpty()) return targets;
            return keep(targets, HiddenChatsPref.ids(account), HiddenChatsPref.hiddenUsers(account));
        } catch (Throwable t) {
            Logger.printException(() -> "HiddenChatsShare.filterTargets failed", t);
            return targets;
        }
    }

    /** Called on the main thread before a recipient picker adds a recipient; true leaves out a suggested hidden chat. */
    public static boolean skipRecipient(Object session, Object target, boolean suggested) {
        try {
            if (!suggested) return false;
            String account = accountToFilter(session);
            return account != null
                    && isHidden(target, HiddenChatsPref.ids(account), HiddenChatsPref.hiddenUsers(account));
        } catch (Throwable t) {
            Logger.printException(() -> "HiddenChatsShare.skipRecipient failed", t);
            return false;
        }
    }

    /** Keeps the other person of a hidden one-to-one chat from its key, which lists the people of the chat. */
    static void rememberUser(String account, Object key) {
        try {
            if (!(key instanceof DirectThreadKey)) return;
            DirectThreadKey threadKey = (DirectThreadKey) key;
            String other = otherUser(threadKey.A02, account);
            if (threadKey.A00 != null && other != null) HiddenChatsPref.addUser(account, threadKey.A00, other);
        } catch (Throwable t) {
            Logger.printException(() -> "HiddenChatsShare.rememberUser failed", t);
        }
    }

    /** The account whose hidden chats are left out, or null when nothing has to be. */
    private static String accountToFilter(Object session) {
        if (!Pref.hiddenChats() || !(session instanceof UserSession)) return null;
        String account = ((UserSession) session).getUserId();
        if (account == null || HiddenChats.isOpenFor(account) || HiddenChatsPref.ids(account).isEmpty()) return null;
        return account;
    }

    /** The targets that are not hidden chats, or the same list when none is. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List keep(List targets, Set<String> chats, Map<String, String> users) {
        List kept = new ArrayList(targets.size());
        for (Object target : targets) {
            if (!isHidden(target, chats, users)) kept.add(target);
        }
        return kept.size() == targets.size() ? targets : kept;
    }

    private static boolean isHidden(Object target, Set<String> chats, Map<String, String> users) {
        DirectThreadKey key = keyOf(target);
        return key != null && isHidden(key.A00, key.A02, chats, users);
    }

    /** A recipient with a chat id is hidden when its chat is; one without is hidden when it is only the other person of a hidden chat. */
    static boolean isHidden(String chatId, List<?> people, Set<String> chats, Map<String, String> users) {
        if (chatId != null) return chats.contains(chatId);
        return people != null && people.size() == 1 && users.containsValue(String.valueOf(people.get(0)));
    }

    /** The one person of a chat other than the account, or null for a group or a chat with nobody else. */
    static String otherUser(List<?> people, String account) {
        if (people == null) return null;
        String other = null;
        for (Object person : people) {
            if (person == null || person.equals(account)) continue;
            if (other != null) return null;
            other = String.valueOf(person);
        }
        return other;
    }

    // A recipient makes its key on demand, from its chat id or from its people; the method names are obfuscated.
    private static DirectThreadKey keyOf(Object target) {
        if (target == null) return null;
        for (Method method : keyMethods.computeIfAbsent(target.getClass(), HiddenChatsShare::keyMethodsOf)) {
            try {
                Object key = method.invoke(target);
                if (key instanceof DirectThreadKey) return (DirectThreadKey) key;
            } catch (Exception ignored) {
                // A recipient of another kind can throw instead of making a key; the next method is tried.
            }
        }
        return null;
    }

    private static Method[] keyMethodsOf(Class<?> type) {
        List<Method> found = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterTypes().length == 0 && !Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == DirectThreadKey.class) found.add(method);
        }
        return found.isEmpty() ? NO_METHODS : found.toArray(new Method[0]);
    }
}
