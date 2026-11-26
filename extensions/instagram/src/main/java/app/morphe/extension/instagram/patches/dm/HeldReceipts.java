/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Seen receipts kept back, by account and item id, oldest first; used on the main thread only. */
final class HeldReceipts {
    interface Sender {
        void send(Object receipt) throws Exception;
    }

    private final int max;
    private final Map<String, Object> held = new LinkedHashMap<>();

    HeldReceipts(int max) {
        this.max = max;
    }

    /** The key of an item of an account, or null when either is unknown. */
    static String key(String account, String itemId) {
        if (account == null || account.isEmpty() || itemId == null || itemId.isEmpty()) return null;
        return account + ":" + itemId;
    }

    /** Keeps the newest receipt of an item; a new viewer builds a new one for the same item. */
    void keep(String key, Object receipt) {
        if (key == null) return;
        held.remove(key);
        held.put(key, receipt);
        if (held.size() > max) {
            Iterator<String> oldest = held.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    boolean has(String key) {
        return key != null && held.containsKey(key);
    }

    /** Whether the eye may show or send for the item; with the eye switch off every kept receipt is dropped unsent. */
    boolean usable(boolean eyeOn, String key) {
        if (!eyeOn) {
            held.clear();
            return false;
        }
        return has(key);
    }

    /** Sends the receipt of the item and then forgets it; when sending throws, it stays for another try. */
    boolean send(String key, Sender sender) throws Exception {
        Object receipt = key == null ? null : held.get(key);
        if (receipt == null) return false;
        sender.send(receipt);
        held.remove(key);
        return true;
    }
}
