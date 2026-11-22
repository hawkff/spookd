/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.devFlags;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongToIntFunction;

/**
 * Snapshot of string-keyed flag overrides ("universal" or "universal::param") as sorted primitive keys,
 * so lookups neither build strings nor box ids.
 */
final class FlagOverrides<V> {
    private final int[] overriddenUniversalIds;
    private final int[] universalKeys;
    private final Object[] universalValues;
    private final long[] paramKeys;
    private final Object[] paramValues;

    FlagOverrides(Map<String, V> flags) {
        TreeSet<Integer> overridden = new TreeSet<>();
        TreeMap<Integer, V> universalFlags = new TreeMap<>();
        TreeMap<Long, V> paramFlags = new TreeMap<>();
        for (Map.Entry<String, V> entry : flags.entrySet()) {
            String flagId = entry.getKey();
            V value = entry.getValue();
            if (flagId == null || value == null) continue;
            int end = flagId.indexOf("::");
            Integer universalId = parseId(end < 0 ? flagId : flagId.substring(0, end));
            if (universalId == null) continue;
            if (end < 0) {
                universalFlags.put(universalId, value);
            } else {
                Integer paramId = parseId(flagId.substring(end + 2));
                if (paramId == null) continue;
                paramFlags.put(packedKey(universalId, paramId), value);
            }
            overridden.add(universalId);
        }

        overriddenUniversalIds = new int[overridden.size()];
        int i = 0;
        for (int id : overridden) overriddenUniversalIds[i++] = id;

        universalKeys = new int[universalFlags.size()];
        universalValues = new Object[universalFlags.size()];
        i = 0;
        for (Map.Entry<Integer, V> entry : universalFlags.entrySet()) {
            universalKeys[i] = entry.getKey();
            universalValues[i++] = entry.getValue();
        }

        paramKeys = new long[paramFlags.size()];
        paramValues = new Object[paramFlags.size()];
        i = 0;
        for (Map.Entry<Long, V> entry : paramFlags.entrySet()) {
            paramKeys[i] = entry.getKey();
            paramValues[i++] = entry.getValue();
        }
    }

    // Only the canonical decimal form matched the old String.valueOf comparison, so anything else is dropped.
    private static Integer parseId(String text) {
        try {
            int id = Integer.parseInt(text);
            return String.valueOf(id).equals(text) ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int paramId(long mobileConfigSpecifier) {
        long shifted = mobileConfigSpecifier >>> 16;
        boolean wide = ((mobileConfigSpecifier >>> 62) & 1L) == 1L;
        return (int) (wide ? (shifted & 0xffff) : (shifted & 0xfff));
    }

    static long packedKey(int universalId, int paramId) {
        return ((long) universalId << 32) | (paramId & 0xffffffffL);
    }

    boolean isEmpty() {
        return overriddenUniversalIds.length == 0;
    }

    boolean hasOverride(int universalId) {
        return Arrays.binarySearch(overriddenUniversalIds, universalId) >= 0;
    }

    // A universal-level entry wins over the parameter entry, as with the string maps.
    @SuppressWarnings("unchecked")
    V get(int universalId, int paramId) {
        if (!hasOverride(universalId)) return null;
        int i = Arrays.binarySearch(universalKeys, universalId);
        if (i >= 0) return (V) universalValues[i];
        i = Arrays.binarySearch(paramKeys, packedKey(universalId, paramId));
        return i >= 0 ? (V) paramValues[i] : null;
    }

    /**
     * Direct-mapped memo of the universal id per {@code (int) (spec >>> 32)}, the only bits the id depends on.
     * Key and id share one long per slot, so a colliding or racing write only causes a recompute.
     */
    static final class UniversalIdCache {
        private final long[] slots;
        private final int shift;

        UniversalIdCache(int indexBits) {
            slots = new long[1 << indexBits];
            shift = 32 - indexBits;
        }

        int get(long mobileConfigSpecifier, LongToIntFunction universalIdOf) {
            int key = (int) (mobileConfigSpecifier >>> 32);
            int index = (key * 0x9E3779B9) >>> shift;
            long slot = slots[index];
            // An all-zero slot reads as empty, so key 0 with id 0 is recomputed each time.
            if (slot != 0 && (int) (slot >>> 32) == key) return (int) slot;
            int universalId = universalIdOf.applyAsInt(mobileConfigSpecifier);
            slots[index] = ((long) key << 32) | (universalId & 0xffffffffL);
            return universalId;
        }
    }
}
