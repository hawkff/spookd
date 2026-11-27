package app.morphe.extension.instagram.settings.preference.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch.Tab;
import app.morphe.extension.instagram.patches.navigation.TabShortcuts;
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
    public void withHomeLongPressAndHomeHiddenTheProfileButtonCountsWithoutAnyGear() {
        assertTrue(access(EnumSet.of(Tab.PROFILE), NO_GEAR, true));
        assertTrue(access(EnumSet.of(Tab.PROFILE, Tab.REELS), NO_GEAR, true));
        assertFalse(access(EnumSet.of(Tab.PROFILE), EnumSet.of(ActionBar.MAIN_FEED), true));
        assertFalse(access(EnumSet.of(Tab.PROFILE), EnumSet.of(ActionBar.INBOX), true));
        assertTrue(access(EnumSet.of(Tab.PROFILE), EnumSet.of(ActionBar.PROFILE), true));
        assertFalse(access(EnumSet.of(Tab.REELS, Tab.SEARCH), NO_GEAR, true));
    }

    @Test
    public void withHomeLongPressAndHomeVisibleHomeCountsWhateverTheGears() {
        assertTrue(access(EnumSet.of(Tab.HOME, Tab.PROFILE), NO_GEAR, true));
        assertTrue(access(EnumSet.of(Tab.HOME), EnumSet.of(ActionBar.INBOX), true));
    }

    @Test
    public void noHomeShortcutLeavesSettingsUnreachable() {
        Tab[] tabs = Tab.values();
        ActionBar[] bars = ActionBar.values();
        for (int tabMask = 0; tabMask < 1 << tabs.length; tabMask++) {
            Set<Tab> visible = EnumSet.of(Tab.HOME);
            for (int i = 0; i < tabs.length; i++) {
                if ((tabMask & 1 << i) != 0) visible.add(tabs[i]);
            }
            for (int barMask = 0; barMask < 1 << bars.length; barMask++) {
                Set<ActionBar> gears = EnumSet.noneOf(ActionBar.class);
                for (int i = 0; i < bars.length; i++) {
                    if ((barMask & 1 << i) != 0) gears.add(bars[i]);
                }
                boolean elsewhere = access(visible, gears, false);
                for (String[] choice : TabShortcuts.CHOICES) {
                    String held = TabShortcuts.homeChoice(choice[0], elsewhere);
                    assertTrue(visible + " " + gears + " " + choice[0],
                            TabShortcuts.opensSettings(held) || elsewhere);
                }
                // So the tab editor may keep counting a visible Home whatever its shortcut.
                assertTrue(access(visible, gears, true));
            }
        }
    }

    @Test
    public void aHomeShortcutIsKeptWhileAnotherWayInIsLeft() {
        // With Profile visible and no gear, the profile page shows the settings button.
        assertEquals(TabShortcuts.SAVED, TabShortcuts.homeChoice(TabShortcuts.SAVED,
                access(EnumSet.of(Tab.HOME, Tab.PROFILE), NO_GEAR, false)));
        assertEquals(TabShortcuts.DEFAULT, TabShortcuts.homeChoice(TabShortcuts.DEFAULT,
                access(EnumSet.of(Tab.HOME), EnumSet.of(ActionBar.MAIN_FEED), false)));
        assertEquals(TabShortcuts.MENU, TabShortcuts.homeChoice(TabShortcuts.SAVED,
                access(EnumSet.of(Tab.HOME, Tab.REELS), NO_GEAR, false)));
        assertEquals(TabShortcuts.MENU, TabShortcuts.homeChoice(TabShortcuts.DEFAULT,
                access(EnumSet.of(Tab.HOME), EnumSet.of(ActionBar.INBOX), false)));
        assertEquals(TabShortcuts.SETTINGS, TabShortcuts.homeChoice(TabShortcuts.SETTINGS, false));
    }

    @Test
    public void nullInputsHaveNoAccess() {
        assertFalse(access(null, NO_GEAR, true));
        assertFalse(access(EnumSet.of(Tab.HOME), null, true));
    }
}
