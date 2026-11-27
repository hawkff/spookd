package app.morphe.extension.instagram.patches.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.View;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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
    public void aCopiedLinkThatCannotOpenFallsBackToInstagramsOwnLongPress() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        copy(activity, "@someuser");
        ContextWrapper failing = new ContextWrapper(activity) {
            @Override
            public void startActivity(Intent intent) {
                throw new ActivityNotFoundException();
            }
        };
        assertTrue(TabShortcuts.onHold(new View(failing), instagram, () -> TabShortcuts.COPIED_LINK));
        assertEquals(1, instagramHolds.get());
    }

    @Test
    public void nothingUsableCopiedKeepsTheHoldForTheToast() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        copy(activity, "https://example.com/p/AbC/");
        assertTrue(TabShortcuts.onHold(new View(activity), instagram, () -> TabShortcuts.COPIED_LINK));
        assertEquals(0, instagramHolds.get());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    private static void copy(Activity activity, String text) {
        activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("", text));
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
