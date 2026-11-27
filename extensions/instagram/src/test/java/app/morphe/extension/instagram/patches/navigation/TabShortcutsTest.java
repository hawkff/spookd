package app.morphe.extension.instagram.patches.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch.Tab;
import app.morphe.extension.instagram.settings.Settings;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class TabShortcutsTest {
    @Test
    public void builtTabsUseTheRowOfTheirTab() {
        assertEquals(TabShortcuts.setting(Tab.REELS), TabShortcuts.builtTabSetting("CLIPS"));
        assertEquals(TabShortcuts.setting(Tab.MESSAGES), TabShortcuts.builtTabSetting("DIRECT"));
        assertEquals(TabShortcuts.setting(Tab.SEARCH), TabShortcuts.builtTabSetting("SEARCH"));
        assertEquals(TabShortcuts.setting(Tab.NOTIFICATIONS), TabShortcuts.builtTabSetting("NEWS"));
        assertEquals(TabShortcuts.setting(Tab.CREATE), TabShortcuts.builtTabSetting("SHARE"));
        assertEquals(TabShortcuts.setting(Tab.CREATE), TabShortcuts.builtTabSetting("CREATION"));
    }

    @Test
    public void homeAndProfileAreLeftToTheirOwnHooks() {
        // Home keeps Instagram's listener for Developer options; Profile's hold is a gesture.
        assertNull(TabShortcuts.builtTabSetting("FEED"));
        assertNull(TabShortcuts.builtTabSetting("PROFILE"));
        assertNull(TabShortcuts.builtTabSetting("FEED_SWITCHER"));
        assertNull(TabShortcuts.builtTabSetting("DYNAMIC_TAB"));
    }

    @Test
    public void everyTabHasItsOwnSettingAndExistingUsersKeepTheirHolds() {
        Set<String> keys = new HashSet<>();
        for (Tab tab : Tab.values()) keys.add(TabShortcuts.setting(tab).key);
        assertEquals(Tab.values().length, keys.size());
        assertEquals(TabShortcuts.MENU, Settings.TAB_SHORTCUT_HOME.defaultValue);
        assertEquals(TabShortcuts.DEFAULT, Settings.TAB_SHORTCUT_PROFILE.defaultValue);
        assertEquals(TabShortcuts.DEFAULT, Settings.TAB_SHORTCUT_SEARCH.defaultValue);
    }
}
