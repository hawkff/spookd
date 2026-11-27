package app.morphe.extension.instagram.patches.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class TabShortcutsHoldTest {
    private enum IgTab { FEED, PROFILE, CLIPS }

    private final AtomicInteger instagramHolds = new AtomicInteger();
    private final View.OnLongClickListener instagram = view -> {
        instagramHolds.incrementAndGet();
        return true;
    };

    @Test
    public void defaultRunsInstagramsOwnLongPress() {
        View view = new View(RuntimeEnvironment.getApplication());
        assertTrue(TabShortcuts.onHold(view, instagram, () -> TabShortcuts.DEFAULT));
        assertEquals(1, instagramHolds.get());
    }

    @Test
    public void aFailingShortcutFallsBackToInstagramsOwnLongPress() {
        View view = new View(RuntimeEnvironment.getApplication());
        assertTrue(TabShortcuts.onHold(view, instagram, () -> {
            throw new IllegalStateException("settings not ready");
        }));
        assertEquals(1, instagramHolds.get());
    }

    @Test
    public void aTabWithoutInstagramsLongPressDoesNothingOnDefault() {
        View view = new View(RuntimeEnvironment.getApplication());
        assertFalse(TabShortcuts.onHold(view, null, () -> TabShortcuts.DEFAULT));
    }

    @Test
    public void homeAndProfileKeepInstagramsListenerObject() {
        assertSame(instagram, TabShortcuts.wrapLongClick(instagram, IgTab.FEED));
        assertSame(instagram, TabShortcuts.wrapLongClick(instagram, IgTab.PROFILE));
        assertNotSame(instagram, TabShortcuts.wrapLongClick(instagram, IgTab.CLIPS));
    }
}
