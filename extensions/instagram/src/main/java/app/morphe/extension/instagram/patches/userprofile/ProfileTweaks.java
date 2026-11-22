/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import java.text.NumberFormat;
import java.util.List;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class ProfileTweaks {
    private static final String SAVED_TAB = "profile_saved";

    private ProfileTweaks() {
    }

    /** Replaces a profile header count Instagram abbreviated, such as 12.3K, with the full number. */
    public static String exactProfileCount(String formatted, Integer count) {
        try {
            return Pref.exactProfileCounts() ? exactCount(formatted, count) : formatted;
        } catch (Throwable t) {
            Logger.printException(() -> "exactProfileCount failure", t);
            return formatted;
        }
    }

    static String exactCount(String formatted, Integer count) {
        return count == null ? formatted : NumberFormat.getIntegerInstance().format(count);
    }

    /** Takes the grid row's 3:4 switch; false gives square cells. */
    public static boolean squareProfileGrid(boolean threeByFour) {
        try {
            return threeByFour && !Pref.squareProfileGrid();
        } catch (Throwable t) {
            Logger.printException(() -> "squareProfileGrid failure", t);
            return threeByFour;
        }
    }

    public static void addProfileSavedTab(List<Object> tabs, boolean ownProfile) {
        try {
            if (ownProfile && Pref.profileSavedTab() && !tabs.contains(SAVED_TAB)) tabs.add(SAVED_TAB);
        } catch (Throwable t) {
            Logger.printException(() -> "addProfileSavedTab failure", t);
        }
    }
}
