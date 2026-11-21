/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.view.View;

import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.settings.preference.fragments.FragmentHook;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class HomeLongPress {
    // Set while the Developer options choice replays the long-press into Instagram's own handler.
    private static boolean replayingLongPress;

    /**
     * Called on the main thread at the start of the Home tab's onLongClick.
     *
     * @return true to consume the long-press, false to run Instagram's original handler.
     */
    public static boolean onLongClick(View view) {
        if (replayingLongPress) {
            replayingLongPress = false;
            return false;
        }
        try {
            if (Pref.enableDevOptions()) {
                showChoiceDialog(view);
            } else {
                FragmentHook.startSettings();
            }
            return true;
        } catch (Exception e) {
            Logger.printException(() -> "Home long-press failed", e);
            return false;
        }
    }

    private static void showChoiceDialog(View view) {
        InstagramDialogBox dialog = new InstagramDialogBox(activityOf(view.getContext()));

        CharSequence[] items = {str("piko_title_settings"), str("piko_category_dev_options")};
        dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                try {
                    if (which == 0) {
                        FragmentHook.startSettings();
                    } else {
                        replayingLongPress = true;
                        try {
                            view.performLongClick();
                        } finally {
                            replayingLongPress = false;
                        }
                    }
                } catch (Exception e) {
                    Logger.printException(() -> "Home long-press choice failed", e);
                }
            }
        });

        dialog.getDialog().show();
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        throw new IllegalStateException("No activity behind the Home tab");
    }
}
