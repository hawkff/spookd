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
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

/**
 * The eye button next to the reply box of the DM photo and video viewer. It sends the seen receipt that Make
 * ephemeral media permanent kept back for the item on screen, the same request Instagram would have queued.
 * Every call runs on the main thread, where the viewer shows its items.
 */
@SuppressWarnings("unused")
public final class VisualMessageSeen {
    private static final String BUTTON_TAG = "spookd_visual_message_seen_button";
    private static final int MAX_HELD = 32;
    // Receipts kept back, oldest first: {item, session, receipt}. Items are matched by identity, as their equality can change.
    private static final List<Object[]> held = new ArrayList<>();
    private static Object currentItem;
    private static WeakReference<Object> currentViewer = new WeakReference<>(null);

    private VisualMessageSeen() { }

    /** Start of the viewer's seen step, which it runs for each item it shows. */
    public static void onItemShown(Object item, Object viewer) {
        if (!EphemeralMediaPatch.isMakeEphemeralMediaPermanent()) return;
        try {
            currentItem = item;
            currentViewer = new WeakReference<>(viewer);
            updateButton(viewer);
        } catch (Throwable t) {
            Logger.printException(() -> "VisualMessageSeen.onItemShown failed", t);
        }
    }

    /** Right before the viewer queues the seen receipt of the item it shows; true keeps it back. */
    public static boolean holdReceipt(Object session, Object receipt) {
        if (!EphemeralMediaPatch.isMakeEphemeralMediaPermanent()) return false;
        try {
            if (Pref.markVisualMessagesSeenManually() && currentItem != null) {
                take(currentItem);
                held.add(new Object[]{currentItem, session, receipt});
                if (held.size() > MAX_HELD) held.remove(0);
                updateButton(currentViewer.get());
            }
        } catch (Throwable t) {
            Logger.printException(() -> "VisualMessageSeen.holdReceipt failed", t);
        }
        // Kept back even when it could not be stored, as Make ephemeral media permanent does.
        return true;
    }

    private static void updateButton(Object viewer) {
        if (viewer == null) return;
        Object composer = composerNative(viewer);
        if (!(composer instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) composer;
        View button = row.findViewWithTag(BUTTON_TAG);
        boolean show = find(currentItem) >= 0;
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
        try {
            button.setVisibility(View.GONE);
            Object[] receipt = take(currentItem);
            if (receipt == null) return;
            sendReceiptNative(receipt[1], receipt[2], null);
            Object viewer = currentViewer.get();
            if (viewer != null && Pref.stopVisualMessageAutoAdvance()) advanceNative(viewer, true);
        } catch (Throwable t) {
            Logger.printException(() -> "VisualMessageSeen could not send the seen receipt", t);
        }
    }

    private static int find(Object item) {
        if (item == null) return -1;
        for (int i = 0; i < held.size(); i++) {
            if (held.get(i)[0] == item) return i;
        }
        return -1;
    }

    private static Object[] take(Object item) {
        int index = find(item);
        return index < 0 ? null : held.remove(index);
    }

    // The patch replaces these bodies with reads and calls of Instagram's viewer and request queue.
    private static Object composerNative(Object viewer) {
        return null;
    }

    // The third argument is only a free register for the queue.
    private static void sendReceiptNative(Object session, Object receipt, Object queue) {
    }

    private static void advanceNative(Object viewer, boolean next) {
    }
}
