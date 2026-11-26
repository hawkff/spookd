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
import app.morphe.extension.instagram.settings.ActivityHook;
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
            boolean devOptions = Pref.enableDevOptions();
            boolean gallery = Pref.homeLongPressGallery();
            if (devOptions || gallery) {
                showChoiceDialog(view, devOptions, gallery);
            } else {
                FragmentHook.startSettings();
            }
            return true;
        } catch (Throwable t) {
            logFailure("Home long-press failed", t);
            return false;
        }
    }

    private static void showChoiceDialog(View view, boolean devOptions, boolean gallery) {
        InstagramDialogBox dialog = new InstagramDialogBox(activityOf(view.getContext()));

        CharSequence[] items = gallery && devOptions
                ? new CharSequence[]{str("spookd_title_settings"), str("spookd_private_gallery"), str("spookd_category_dev_options")}
                : new CharSequence[]{str("spookd_title_settings"), str(gallery ? "spookd_private_gallery" : "spookd_category_dev_options")};
        dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                try {
                    if (which == 0) {
                        FragmentHook.startSettings();
                    } else if (gallery && which == 1) {
                        // The gallery screen asks for the screen lock itself before showing anything.
                        ActivityHook.startPrivateGalleryActivity(view.getContext());
                    } else {
                        replayingLongPress = true;
                        try {
                            view.performLongClick();
                        } finally {
                            replayingLongPress = false;
                        }
                    }
                } catch (Throwable t) {
                    logFailure("Home long-press choice failed", t);
                }
            }
        });

        dialog.getDialog().show();
    }

    private static void logFailure(String message, Throwable t) {
        try {
            Logger.printException(() -> message, t);
        } catch (Throwable ignored) {
            // Logging must never throw into Instagram's long-click handling.
        }
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
