package app.morphe.extension.instagram.patches.instants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class InstantsSeenTest {
    private static final Map<String, String> NONE = Collections.emptyMap();

    @Test
    public void theSwitchDecidesForUnlistedAndUnknownSenders() {
        assertTrue(InstantsSeen.marksSeen(false, "1", NONE, NONE));
        assertTrue(InstantsSeen.marksSeen(false, null, NONE, NONE));
        assertFalse(InstantsSeen.marksSeen(true, "1", NONE, NONE));
        assertFalse(InstantsSeen.marksSeen(true, null, NONE, NONE));
    }

    @Test
    public void listedSendersOverrideTheSwitch() {
        Map<String, String> listed = Collections.singletonMap("1", "first");
        assertTrue(InstantsSeen.marksSeen(true, "1", listed, NONE));
        assertFalse(InstantsSeen.marksSeen(false, "1", NONE, listed));
        // A sender on both lists is never marked seen.
        assertFalse(InstantsSeen.marksSeen(true, "1", listed, listed));
    }

    @Test
    public void pksDropTheOwnerSuffix() {
        assertEquals("123", InstantsSeen.pkOf("123_456"));
        assertEquals("123", InstantsSeen.pkOf("123"));
        assertNull(InstantsSeen.pkOf(""));
        assertNull(InstantsSeen.pkOf(null));
        assertNull(InstantsSeen.pkOf("_456"));
    }

    @Test
    public void onlyReportedIdsAreKept() {
        Map<String, String> owners = new HashMap<>();
        owners.put("10", "1");
        owners.put("20", "2");
        Map<String, String> always = Collections.singletonMap("1", "first");
        List<String> ids = Arrays.asList("10", "20_2", "30");

        assertEquals(Collections.singletonList("10"), InstantsSeen.kept(ids, true, owners::get, always, NONE));
        assertEquals(Arrays.asList("10", "30"),
                InstantsSeen.kept(ids, false, owners::get, NONE, Collections.singletonMap("2", "second")));
    }

    @Test
    public void allReportedKeepsTheListAndNoneReportedReportsNothing() {
        List<String> ids = Arrays.asList("10", "20");
        assertSame(ids, InstantsSeen.kept(ids, false, pk -> null, NONE, NONE));
        assertNull(InstantsSeen.kept(ids, true, pk -> null, NONE, NONE));
        List<String> empty = Collections.emptyList();
        assertSame(empty, InstantsSeen.kept(empty, true, pk -> null, NONE, NONE));
    }
}
