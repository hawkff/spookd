package app.morphe.extension.instagram.patches.devFlags;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongToIntFunction;

public class FlagOverridesTest {

    private static long spec(int key, long low) {
        return ((long) key << 32) | low;
    }

    @Test
    public void paramIdUsesSixteenBitsWhenBit62IsSet() {
        long spec = (1L << 62) | (0xABCDL << 16) | 0x1234L;
        assertEquals(0xABCD, FlagOverrides.paramId(spec));
    }

    @Test
    public void paramIdUsesTwelveBitsWhenBit62IsClear() {
        long spec = (1L << 63) | (0xABCDL << 16) | 0x1234L;
        assertEquals(0xBCD, FlagOverrides.paramId(spec));
    }

    @Test
    public void packedKeyPutsUniversalHighAndParamLow() {
        assertEquals(0x0001_E240_0000_002AL, FlagOverrides.packedKey(123456, 42));
        assertEquals(0x0000_0001_0000_FFFFL, FlagOverrides.packedKey(1, 0xffff));
    }

    @Test
    public void universalEntryWinsOverParamEntry() {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("104772", false);
        flags.put("104772::3", true);
        flags.put("117613::0", true);
        FlagOverrides<Boolean> overrides = new FlagOverrides<>(flags);

        assertEquals(Boolean.FALSE, overrides.get(104772, 3));
        assertEquals(Boolean.FALSE, overrides.get(104772, 9));
        assertEquals(Boolean.TRUE, overrides.get(117613, 0));
        assertNull(overrides.get(117613, 1));
    }

    @Test
    public void longValuesAreReturnedAsStored() {
        Map<String, Long> flags = new HashMap<>();
        flags.put("59918::2", 7L);
        FlagOverrides<Long> overrides = new FlagOverrides<>(flags);

        assertEquals(Long.valueOf(7L), overrides.get(59918, 2));
        assertNull(overrides.get(59918, 3));
    }

    @Test
    public void idWithoutOverrideReturnsNullFromTheSnapshot() {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("104772", false);
        flags.put("117613::0", true);
        FlagOverrides<Boolean> overrides = new FlagOverrides<>(flags);
        flags.put("555", true);

        assertFalse(overrides.isEmpty());
        assertFalse(overrides.hasOverride(555));
        assertNull(overrides.get(555, 0));
        assertFalse(overrides.hasOverride(-1));
        assertNull(overrides.get(-1, 0));
        assertTrue(overrides.hasOverride(104772));
        assertTrue(overrides.hasOverride(117613));
    }

    @Test
    public void nonCanonicalAndNullEntriesNeverMatch() {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("0123", true);
        flags.put("77::05", true);
        flags.put("88::x", true);
        flags.put("99", null);
        FlagOverrides<Boolean> overrides = new FlagOverrides<>(flags);

        assertTrue(overrides.isEmpty());
        assertNull(overrides.get(123, 0));
        assertNull(overrides.get(77, 5));
        assertNull(overrides.get(99, 0));
    }

    private static final class CountingLookup implements LongToIntFunction {
        int calls;

        @Override
        public int applyAsInt(long spec) {
            calls++;
            return (int) (spec >>> 32) * 3 + 1;
        }
    }

    @Test
    public void cacheHitSkipsLookupForSameUpperBits() {
        FlagOverrides.UniversalIdCache cache = new FlagOverrides.UniversalIdCache(12);
        CountingLookup lookup = new CountingLookup();

        assertEquals(31, cache.get(spec(10, 0x1111), lookup));
        assertEquals(31, cache.get(spec(10, 0xFFFF_FFFFL), lookup));
        assertEquals(1, lookup.calls);
    }

    @Test
    public void collidingKeysRecomputeAndNeverReturnAnotherKeysId() {
        FlagOverrides.UniversalIdCache cache = new FlagOverrides.UniversalIdCache(1);
        CountingLookup lookup = new CountingLookup();

        for (int round = 0; round < 2; round++) {
            for (int key = 1; key <= 3; key++) {
                assertEquals(key * 3 + 1, cache.get(spec(key, 0), lookup));
            }
        }
        // Three keys in two slots: at least one second-round read had to recompute.
        assertTrue(lookup.calls > 3);
    }

    @Test
    public void zeroKeyWithZeroIdIsRecomputedCorrectly() {
        FlagOverrides.UniversalIdCache cache = new FlagOverrides.UniversalIdCache(12);
        int[] calls = {0};
        LongToIntFunction zero = spec -> {
            calls[0]++;
            return 0;
        };

        assertEquals(0, cache.get(5L, zero));
        assertEquals(0, cache.get(5L, zero));
        assertEquals(2, calls[0]);
    }

    @Test
    public void negativeIdIsCached() {
        FlagOverrides.UniversalIdCache cache = new FlagOverrides.UniversalIdCache(12);
        int[] calls = {0};
        LongToIntFunction outOfRange = spec -> {
            calls[0]++;
            return -1;
        };

        assertEquals(-1, cache.get(spec(-7, 0), outOfRange));
        assertEquals(-1, cache.get(spec(-7, 0), outOfRange));
        assertEquals(1, calls[0]);
    }
}
