/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;
import com.instagram.common.session.UserSession;

/**
 * Every seen receipt of the DM photo and video viewer passes through here on its way to Instagram's request queue.
 * While Make ephemeral media permanent is on it is kept back; with the eye button switch it is kept per account and
 * item, and the eye next to the reply box sends the one of the item on screen. Every call runs on the main thread,
 * where the viewer shows its items.
 */
@SuppressWarnings("unused")
public final class VisualMessageSeen {
    private static final String BUTTON_TAG = "spookd_visual_message_seen_button";
    static final HeldReceipts HELD = new HeldReceipts(32);
    // The item the viewer's receipt step runs for, which may be a deferred one rather than the item on screen.
    private static String producerAccount;
    private static String producerItemId;
    private static WeakReference<Object> currentViewer = new WeakReference<>(null);

    private VisualMessageSeen() { }

    /** Start of the viewer's receipt step for an item, the one on screen or a deferred one. */
    public static void onItemShown(Object model, Object viewer) {
        producerAccount = null;
        producerItemId = null;
        if (!EphemeralMediaPatch.isMakeEphemeralMediaPermanent()) return;
        try {
            producerAccount = accountOf(sessionNative(viewer));
            producerItemId = model == null ? null : (String) modelIdNative(model);
            currentViewer = new WeakReference<>(viewer);
            updateButton(viewer);
        } catch (Throwable t) {
            Logger.printException(() -> "VisualMessageSeen.onItemShown failed", t);
        }
    }

    /** Replaces the viewer's hand-over of a receipt to Instagram's request queue, for both kinds of receipt. */
    public static void queueReceipt(Object queue, Object receipt) {
        boolean keepBack;
        try {
            keepBack = keepBack(queue, receipt);
        } catch (Throwable t) {
            Logger.printException(() -> "VisualMessageSeen.queueReceipt failed", t);
            keepBack = EphemeralMediaPatch.isMakeEphemeralMediaPermanent();
        }
        // Outside the try, so a failure of Instagram's own queue reaches it as before.
        if (!keepBack) enqueueNative(queue, receipt);
    }

    private static boolean keepBack(Object queue, Object receipt) {
        if (!EphemeralMediaPatch.isMakeEphemeralMediaPermanent()) return false;
        EphemeralMediaPatch.markViewed(producerAccount, producerItemId);
        if (Pref.markVisualMessagesSeenManually()) {
            HELD.keep(HeldReceipts.key(producerAccount, producerItemId), new Object[]{queue, receipt});
            updateButton(currentViewer.get());
        }
        return true;
    }

    /** The id of the item the viewer has on screen, or null when it is unknown. */
    private static String selectedItemId(Object viewer) {
        Object selection = selectionNative(viewer);
        Object model = selection == null ? null : selectedModelNative(selection);
        return model == null ? null : (String) modelIdNative(model);
    }

    private static String accountOf(Object session) {
        return session instanceof UserSession ? ((UserSession) session).getUserId() : null;
    }

    private static void updateButton(Object viewer) {
        if (viewer == null) return;
        Object composer = composerNative(viewer);
        if (!(composer instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) composer;
        View button = row.findViewWithTag(BUTTON_TAG);
        boolean show = HELD.usable(Pref.markVisualMessagesSeenManually(),
                HeldReceipts.key(accountOf(sessionNative(viewer)), selectedItemId(viewer)));
        if (button == null) {
            if (!show) return;
            button = createButton(row);
        }
        button.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static View createButton(LinearLayout row) {
        ImageView button = new ImageView(row.getContext());
        button.setTag(BUTTON_TAG);
        UI.setThemedIcon(button, UI.DRAWABLE_EYE_ICON);
        // The viewer is dark in both themes, like the story viewer's eye button.
        button.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPaddingRelative(Dim.dp8, 0, Dim.dp8, 0);
        button.setContentDescription(str("spookd_mark_visual_message_seen"));
        button.setOnClickListener(VisualMessageSeen::onButtonClicked);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        row.addView(button, params);
        return button;
    }

    private static void onButtonClicked(View button) {
        Object viewer = currentViewer.get();
        if (viewer == null) return;
        try {
            String account = accountOf(sessionNative(viewer));
            String itemId = selectedItemId(viewer);
            String key = HeldReceipts.key(account, itemId);
            boolean sent = HELD.usable(Pref.markVisualMessagesSeenManually(), key) && HELD.send(key, held -> {
                Object[] queued = (Object[]) held;
                enqueueNative(queued[0], queued[1]);
            });
            if (sent && Pref.stopVisualMessageAutoAdvance()) advanceNative(viewer, true);
        } catch (Throwable t) {
            Logger.printException(() -> "VisualMessageSeen could not send the seen receipt", t);
        }
        try {
            // Shown again when the receipt is still kept, as after a failed send.
            updateButton(viewer);
        } catch (Throwable t) {
            Logger.printException(() -> "VisualMessageSeen.updateButton failed", t);
        }
    }

    // The patch replaces these bodies with reads and calls of Instagram's viewer and request queue.
    private static Object composerNative(Object viewer) {
        return null;
    }

    private static Object sessionNative(Object viewer) {
        return null;
    }

    private static Object selectionNative(Object viewer) {
        return null;
    }

    private static Object selectedModelNative(Object selection) {
        return null;
    }

    private static Object modelIdNative(Object model) {
        return null;
    }

    private static void enqueueNative(Object queue, Object receipt) {
    }

    private static void advanceNative(Object viewer, boolean next) {
    }
}
