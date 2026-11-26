package app.morphe.extension.instagram.patches.navigation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RefreshControlTest {
    private enum ScrollReason { LOGO_PRESS, HOME_PRESS, BACK_BUTTON_PRESS, BACK_BUTTON_PRESS_AT_TOP }

    @Test
    public void onlyTheHomeTabTapCountsAsATabTap() {
        assertTrue(RefreshControl.isHomeTabTap(ScrollReason.HOME_PRESS));
        assertFalse(RefreshControl.isHomeTabTap(ScrollReason.LOGO_PRESS));
        assertFalse(RefreshControl.isHomeTabTap(ScrollReason.BACK_BUTTON_PRESS));
        assertFalse(RefreshControl.isHomeTabTap(ScrollReason.BACK_BUTTON_PRESS_AT_TOP));
        assertFalse(RefreshControl.isHomeTabTap("HOME_PRESS"));
        assertFalse(RefreshControl.isHomeTabTap(null));
    }

    @Test
    public void tabTapSkipsTheNewPostsPillOnlyWhenTheSwitchIsOn() {
        Object pill = new Object();
        assertNull(RefreshControl.newPostsPill(pill, ScrollReason.HOME_PRESS, true));
        assertSame(pill, RefreshControl.newPostsPill(pill, ScrollReason.HOME_PRESS, false));
        assertSame(pill, RefreshControl.newPostsPill(pill, ScrollReason.BACK_BUTTON_PRESS_AT_TOP, true));
        assertSame(pill, RefreshControl.newPostsPill(pill, ScrollReason.LOGO_PRESS, true));
        assertNull(RefreshControl.newPostsPill(null, ScrollReason.HOME_PRESS, false));
    }
}
