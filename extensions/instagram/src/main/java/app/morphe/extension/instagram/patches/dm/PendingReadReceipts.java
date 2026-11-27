/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.MarkChatAsReadScope;

/** Read receipts DM ghost kept back, by account and chat id, oldest first. */
final class PendingReadReceipts {
    private static final int MAX_PENDING = 64;
    private final Map<String, MarkChatAsReadScope.Operation> pending = new LinkedHashMap<>();

    synchronized void remember(String account, String threadId, MarkChatAsReadScope.Operation receipt) {
        String key = HeldReceipts.key(account, threadId);
        if (key == null) return;
        pending.remove(key);
        pending.put(key, receipt);
        if (pending.size() > MAX_PENDING) {
            Iterator<String> oldest = pending.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    synchronized MarkChatAsReadScope.Operation take(String account, String threadId) {
        String key = HeldReceipts.key(account, threadId);
        return key == null ? null : pending.remove(key);
    }
}
