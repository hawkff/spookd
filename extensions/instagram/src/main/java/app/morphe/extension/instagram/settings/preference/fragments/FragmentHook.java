/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/

package app.morphe.extension.instagram.settings.preference.fragments;


import android.content.Context;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.settings.ActivityHook;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.settings.preference.RecommendedFlagsScreen;

@SuppressWarnings("deprecation")
public class FragmentHook {

    public static void startFragment(String key){
        String actionBarTitleKey = null;

        if(key.equals(Constants.SPOOKD_FRAGMENT_ADS)){
            actionBarTitleKey = "spookd_category_ads";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_GHOST)){
            actionBarTitleKey = "spookd_category_ghost";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_STORY)){
            actionBarTitleKey = "spookd_category_story";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_LINKS)){
            actionBarTitleKey = "spookd_category_links";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_DISTRACTION_FREE)){
            actionBarTitleKey = "spookd_category_distraction_free";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_FOCUS_LOCK)){
            actionBarTitleKey = "spookd_focus_lock";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_MISC)){
            actionBarTitleKey = "spookd_category_misc";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_DOWNLOAD_MEDIA)){
            actionBarTitleKey = "spookd_category_downloads";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_NAV_BTNS)){
            actionBarTitleKey = "spookd_category_navigation_tabs";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_DEV_OPTIONS)){
            actionBarTitleKey = "spookd_category_dev_options";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_ABOUT)){
            actionBarTitleKey = "spookd_category_about";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_ACTION_BAR)){
            actionBarTitleKey = "spookd_category_action_bar";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_DM)){
            actionBarTitleKey = "spookd_category_dm";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_FILTER_CONTENT)){
            actionBarTitleKey = "spookd_category_filter_content";
        }else if(RecommendedFlagsScreen.isFlagsScreen(key)){
            actionBarTitleKey = RecommendedFlagsScreen.titleKey(key);
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_INSTANTS)){
            actionBarTitleKey = "spookd_instants_title";
        }else if(key.equals(Constants.SPOOKD_FRAGMENT_FONT)){
            actionBarTitleKey = "spookd_category_font";
        }

        if(actionBarTitleKey!=null){
            ActivityHook.startSpookdActivity(key,actionBarTitleKey);
        }
    }

    public static void startSettings(){
        ActivityHook.startSpookdActivity(Constants.SPOOKD_FRAGMENT_SETTINGS,"spookd_title_settings");
    }

}
