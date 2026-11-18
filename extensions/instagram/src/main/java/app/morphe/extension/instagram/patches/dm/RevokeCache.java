/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** In-memory LRU of saved message text keyed by server id and client context, for anti-revoke. */
final class RevokeCache {

    static final RevokeCache INSTANCE = new RevokeCache(4096);

    static final class Saved {
        final String messageId;
        final String content;

        Saved(String messageId, String content) {
            this.messageId = messageId;
            this.content = content;
        }
    }

    private final Map<String, Saved> entries;
    private long generation;

    RevokeCache(final int capacity) {
        entries = new LinkedHashMap<String, Saved>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Saved> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Capture before reading the database; a put with an older value is dropped. */
    synchronized long generation() {
        return generation;
    }

    /** Points both aliases at the message, unless an invalidation ran since {@code readGeneration}. */
    synchronized void put(String serverId, String clientContext, String messageId, String content,
                          long readGeneration) {
        if (messageId == null || readGeneration != generation) return;
        Saved saved = new Saved(messageId, content);
        String serverKey = serverKey(serverId);
        String clientKey = clientKey(clientContext);
        if (serverKey != null) entries.put(serverKey, saved);
        if (clientKey != null) entries.put(clientKey, saved);
    }

    /** The saved text for either alias, server id first, or null on a miss. */
    synchronized Saved get(String serverId, String clientContext) {
        String serverKey = serverKey(serverId);
        Saved saved = serverKey != null ? entries.get(serverKey) : null;
        if (saved != null) return saved;
        String clientKey = clientKey(clientContext);
        return clientKey != null ? entries.get(clientKey) : null;
    }

    /** Removes every alias that points at one of these messages and rejects puts read before now. */
    synchronized void removeMessages(Collection<String> messageIds) {
        generation++;
        Set<String> ids = new HashSet<>(messageIds);
        for (Iterator<Saved> it = entries.values().iterator(); it.hasNext(); ) {
            if (ids.contains(it.next().messageId)) it.remove();
        }
    }

    // Separate prefixes keep a server id from matching an equal client context.
    private static String serverKey(String serverId) {
        String id = trimToNull(serverId);
        return id != null ? "s:" + id : null;
    }

    private static String clientKey(String clientContext) {
        String id = trimToNull(clientContext);
        return id != null ? "c:" + id : null;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
