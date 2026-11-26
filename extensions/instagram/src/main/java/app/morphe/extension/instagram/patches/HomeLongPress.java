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

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.patches.instants.InstantsMenu;
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
            boolean instants = InstantsMenu.available();
            if (devOptions || gallery || instants) {
                showChoiceDialog(view, devOptions, gallery, instants);
            } else {
                FragmentHook.startSettings();
            }
            return true;
        } catch (Throwable t) {
            logFailure("Home long-press failed", t);
            return false;
        }
    }

    private static void showChoiceDialog(View view, boolean devOptions, boolean gallery, boolean instants) {
        Activity activity = activityOf(view.getContext());
        InstagramDialogBox dialog = new InstagramDialogBox(activity);

        List<String> keys = new ArrayList<>();
        keys.add("spookd_title_settings");
        if (gallery) keys.add("spookd_private_gallery");
        if (instants) keys.add("spookd_instants_title");
        if (devOptions) keys.add("spookd_category_dev_options");
        CharSequence[] items = new CharSequence[keys.size()];
        for (int i = 0; i < items.length; i++) items[i] = str(keys.get(i));
        dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                try {
                    String key = keys.get(which);
                    if (key.equals("spookd_title_settings")) {
                        FragmentHook.startSettings();
                    } else if (key.equals("spookd_private_gallery")) {
                        // The gallery screen asks for the screen lock itself before showing anything.
                        ActivityHook.startPrivateGalleryActivity(view.getContext());
                    } else if (key.equals("spookd_instants_title")) {
                        InstantsMenu.show(activity);
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
