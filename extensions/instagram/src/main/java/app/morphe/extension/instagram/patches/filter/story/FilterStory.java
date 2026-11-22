/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.filter.story;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.entity.ReelResponseItem;
import app.morphe.extension.instagram.entity.UserData;

import app.morphe.extension.crimera.PikoUtils;

@SuppressWarnings("unused")
public class FilterStory {

    public static List<Object> filter(List<Object> items) {
        if (items == null || items.isEmpty()) return items;

        Set<String> reelTypes = Pref.filterStoryByType();
        Set<String> userTypes = Pref.filterStoryByUserType();
        int minStoryItems = Pref.filterStoryByMinStoryItems();
        int maxStoryItems = Pref.filterStoryByMaxStoryItems();
        List<Object> filtered = new ArrayList<>(items.size());
        for (Object item : items) {
            try {
                ReelResponseItem reelResponseItem = new ReelResponseItem(item);
                if (!reelTypes.isEmpty() && reelTypes.contains(reelResponseItem.getReelType())) continue;

                if (!userTypes.isEmpty()) {
                    UserData userData = reelResponseItem.getUserData();
                    if (userTypes.contains("verified") && userData.isVerified()) continue;
                    if (userTypes.contains("unverified") && !userData.isVerified()) continue;
                }

                int mediaCount = reelResponseItem.getMediaCount();
                if (outsideCountRange(mediaCount, minStoryItems, maxStoryItems)) continue;
            } catch (Exception e) {
                PikoUtils.logger(e.toString());
            }
            filtered.add(item);
        }
        return filtered;
    }

    static boolean outsideCountRange(int mediaCount, int minStoryItems, int maxStoryItems) {
        return mediaCount < minStoryItems || mediaCount > maxStoryItems;
    }
}
