package app.morphe.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

public class AccountSettingsTest {
    private static final String OWNER = AccountSettings.OWNER_KEY;

    @BeforeClass
    public static void registerPolicies() {
        SettingRegistry.register("disable_ads", RestartPolicy.REQUIRED);
        SettingRegistry.register("disable_typing_status", RestartPolicy.NOT_REQUIRED);
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }

    @Test
    public void firstAccountAdoptsTheLiveFile() {
        assertEquals(AccountSettings.Action.ADOPT, AccountSettings.action(null, "1", false));
        assertEquals(AccountSettings.Action.ADOPT, AccountSettings.action("2", "1", true));
        assertEquals(AccountSettings.Action.ADOPT, AccountSettings.action("1", "1", true));
    }

    @Test
    public void sameOwnerChangesNothing() {
        assertEquals(AccountSettings.Action.NONE, AccountSettings.action("1", "1", false));
    }

    @Test
    public void switchBetweenKnownAccountsSwapsPerAccountKeys() {
        assertEquals(AccountSettings.Action.SWAP, AccountSettings.action("1", "2", false));
        Map<String, Object> live = map(OWNER, "1", "disable_ads", false, "disable_typing_status", true,
                "amoled_theme", true);
        Map<String, Object> incoming = map(OWNER, "2", "disable_ads", true);

        Map<String, Object> outgoing = AccountSettings.snapshot(live, "1");
        assertEquals(map(OWNER, "1", "disable_ads", false, "disable_typing_status", true), outgoing);
        assertEquals(Collections.singleton("disable_typing_status"),
                AccountSettings.keysToRemove(outgoing, incoming));
        assertEquals(map(OWNER, "2", "disable_ads", true), AccountSettings.keysToPut(incoming, "2"));
    }

    @Test
    public void newAccountStartsWithACopy() {
        Map<String, Object> outgoing = AccountSettings.snapshot(map(OWNER, "1", "disable_ads", false), "1");
        Map<String, Object> incoming = Collections.emptyMap();

        assertTrue(AccountSettings.keysToRemove(outgoing, incoming).isEmpty());
        assertEquals(map(OWNER, "3"), AccountSettings.keysToPut(incoming, "3"));
        assertFalse(AccountSettings.restartRequired(outgoing, incoming));
    }

    @Test
    public void snapshotIsNeverEmpty() {
        assertEquals(map(OWNER, "1"), AccountSettings.snapshot(map("amoled_theme", true), "1"));
    }

    @Test
    public void globalKeysAreLeftAlone() {
        Set<String> storyKeys = new HashSet<>(Collections.singleton("x"));
        Map<String, Object> live = map(OWNER, "1", "inbox_lock", true, "hidden_chats", true,
                "focus_lock_until", "99", "focus_lock_sel_disable_explore", true, "piko_debug", true,
                "marked_story_keys_v2", storyKeys, "save_deleted_messages", true);
        Map<String, Object> incoming = map(OWNER, "2", "inbox_lock", false, "piko_debug", false);

        Map<String, Object> outgoing = AccountSettings.snapshot(live, "1");
        assertEquals(map(OWNER, "1", "save_deleted_messages", true), outgoing);
        assertEquals(Collections.singleton("save_deleted_messages"),
                AccountSettings.keysToRemove(outgoing, incoming));
        assertEquals(map(OWNER, "2"), AccountSettings.keysToPut(incoming, "2"));
    }

    @Test
    public void keysTheIncomingAccountLacksAreRemoved() {
        Map<String, Object> outgoing = map(OWNER, "1", "disable_ads", false, "hide_meta_ai", true,
                "floating_tab_bar", true);
        Map<String, Object> incoming = map(OWNER, "2", "hide_meta_ai", false);

        assertEquals(new HashSet<>(Arrays.asList("disable_ads", "floating_tab_bar")),
                AccountSettings.keysToRemove(outgoing, incoming));
    }

    @Test
    public void restartRequiredOnlyWhenARequiredKeyDiffers() {
        Map<String, Object> outgoing = map(OWNER, "1", "disable_ads", false, "disable_typing_status", true,
                "unregistered_marker", true);

        assertFalse(AccountSettings.restartRequired(outgoing,
                map(OWNER, "2", "disable_ads", false, "unregistered_marker", false)));
        assertTrue(AccountSettings.restartRequired(outgoing,
                map(OWNER, "2", "disable_ads", true, "disable_typing_status", true)));
        assertTrue(AccountSettings.restartRequired(outgoing, map(OWNER, "2")));
    }
}
