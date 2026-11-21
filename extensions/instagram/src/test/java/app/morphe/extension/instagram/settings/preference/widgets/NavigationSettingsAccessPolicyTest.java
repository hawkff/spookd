package app.morphe.extension.instagram.settings.preference.widgets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch.Tab;
import app.morphe.extension.instagram.settings.preference.widgets.NavigationSettingsAccessPolicy.ActionBar;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;

public class NavigationSettingsAccessPolicyTest {
    private static final Set<ActionBar> NO_GEAR = EnumSet.noneOf(ActionBar.class);

    private static boolean access(Set<Tab> visible, Set<ActionBar> gears, boolean homeLongPress) {
        return NavigationSettingsAccessPolicy.hasDirectAccess(visible, gears, homeLongPress);
    }

    @Test
    public void withoutHomeLongPressTheProfileButtonCountsOnlyWithoutAnyGear() {
        assertTrue(access(EnumSet.of(Tab.PROFILE), NO_GEAR, false));
        assertFalse(access(EnumSet.of(Tab.PROFILE), EnumSet.of(ActionBar.INBOX), false));
        assertTrue(access(EnumSet.of(Tab.PROFILE), EnumSet.of(ActionBar.PROFILE), false));
    }

    @Test
    public void withoutHomeLongPressHomeNeedsTheMainFeedGear() {
        assertFalse(access(EnumSet.of(Tab.HOME), NO_GEAR, false));
        assertTrue(access(EnumSet.of(Tab.HOME), EnumSet.of(ActionBar.MAIN_FEED), false));
        // An empty visible set falls back to Home.
        assertFalse(access(EnumSet.noneOf(Tab.class), NO_GEAR, false));
        assertTrue(access(EnumSet.noneOf(Tab.class), EnumSet.of(ActionBar.MAIN_FEED), false));
    }

    @Test
    public void messagesNeedAnInboxOrChatGearInBothModes() {
        for (boolean homeLongPress : new boolean[]{false, true}) {
            assertFalse(access(EnumSet.of(Tab.MESSAGES), NO_GEAR, homeLongPress));
            assertTrue(access(EnumSet.of(Tab.MESSAGES), EnumSet.of(ActionBar.INBOX), homeLongPress));
            assertTrue(access(EnumSet.of(Tab.MESSAGES), EnumSet.of(ActionBar.CHAT), homeLongPress));
            assertFalse(access(EnumSet.of(Tab.REELS, Tab.SEARCH), EnumSet.allOf(ActionBar.class), homeLongPress));
        }
    }

    @Test
    public void withHomeLongPressAVisibleHomeTabIsAWayIn() {
        assertTrue(access(EnumSet.of(Tab.HOME), NO_GEAR, true));
        assertTrue(access(EnumSet.noneOf(Tab.class), NO_GEAR, true));
    }

    @Test
    public void withHomeLongPressProfileNeedsTheProfileGear() {
        assertFalse(access(EnumSet.of(Tab.PROFILE), NO_GEAR, true));
        assertFalse(access(EnumSet.of(Tab.PROFILE), EnumSet.of(ActionBar.MAIN_FEED), true));
        assertTrue(access(EnumSet.of(Tab.PROFILE), EnumSet.of(ActionBar.PROFILE), true));
    }

    @Test
    public void nullInputsHaveNoAccess() {
        assertFalse(access(null, NO_GEAR, true));
        assertFalse(access(EnumSet.of(Tab.HOME), null, true));
    }
}
