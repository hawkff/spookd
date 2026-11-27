/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import java.util.HashSet;
import java.util.ArrayList;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.entity.MediaData;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.crimera.PikoUtils;

import com.instagram.igds.components.peoplecell.IgdsPeopleCell;
import com.instagram.common.typedurl.ImageUrl;

public class ViewStoryMentionsPatch {

    public static void viewMentions(Context context, Object mediaObject,
                                    DialogInterface.OnDismissListener onDismiss){
        try {
            HashSet<UserData> mentionSet = new MediaData(mediaObject).getMentionSet();

            ArrayList<IgdsPeopleCell> peopleCells = new ArrayList<>();
            if(mentionSet!=null) {
                mentionSet.forEach(userData -> {
                    try {
                        String fullName = userData.getFullName();
                        String username = userData.getUsername();
                        ImageUrl lowResDP = userData.getLowResProfilePicture();
                        boolean isVerified = userData.isVerified();

                        IgdsPeopleCell cell = new IgdsPeopleCell(context);
                        View.OnClickListener openProfile = v ->
                                PikoUtils.openUrl("instagram://user?username=" + username, true);

                        setPrimaryText(cell, fullName, isVerified);
                        setSupportingText(cell, username);
                        setProfileImage(cell, lowResDP, openProfile);
                        cell.setOnClickListener(openProfile);
                        if (username != null && !username.isEmpty()) {
                            View.OnLongClickListener copy = v -> copyUsername(username);
                            cell.setOnLongClickListener(copy);
                            // The avatar has its own tap listener, so it takes its own touches.
                            Object avatar = cell.getImageView();
                            if (avatar instanceof View) ((View) avatar).setOnLongClickListener(copy);
                        }

                        peopleCells.add(cell);
                    } catch (Exception ex){
                            Logger.printException(() -> "Failed story mention user extraction", ex);
                            PikoUtils.logger(ex);
                    }
                });
            }
            PeopleCellDialogBox.showPeopleDialog(context, peopleCells, onDismiss);

        } catch (Exception ex){
            Logger.printException(() -> "Failed viewMentions", ex);
            PikoUtils.logger(ex);
            if (onDismiss != null) onDismiss.onDismiss(null);
        }
    }

    /** Holding a row of the mentions dialog, on the main thread. */
    private static boolean copyUsername(String username) {
        try {
            Utils.setClipboard("@" + username);
            Utils.showToastShort(str("spookd_username_copied", username));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to copy a mentioned username", ex);
            return false;
        }
    }

    // The patch injects calls to the native methods resolved from the target apk.
    private static void setPrimaryText(IgdsPeopleCell cell, CharSequence text, boolean verified) {}

    private static void setSupportingText(IgdsPeopleCell cell, CharSequence text) {}

    private static void setProfileImage(IgdsPeopleCell cell, ImageUrl image, View.OnClickListener listener) {}
}
