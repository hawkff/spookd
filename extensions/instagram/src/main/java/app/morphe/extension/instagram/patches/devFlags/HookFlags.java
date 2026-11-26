/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.devFlags;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.function.LongToIntFunction;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.entity.DeveloperOptions;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.entity.DeveloperOptionsItem;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.settings.SettingsStatus;

public class HookFlags {
    private static Map<String, Boolean> BOOL_FLAGS = new HashMap<>();
    private static Map<String, Long> LONG_FLAGS = new HashMap<>();
    private static DeveloperOptions developerOptions = new DeveloperOptions();
    // Overrides indexed by numeric id; null until load() publishes them, so earlier checks still read the maps.
    private static volatile FlagOverrides<Boolean> boolOverrides = null;
    private static volatile FlagOverrides<Long> longOverrides = null;
    // 16384 slots (128 KB) for about 6,000 configs in this app version, so hot configs rarely share a slot.
    private static final FlagOverrides.UniversalIdCache universalIdCache = new FlagOverrides.UniversalIdCache(14);
    private static final LongToIntFunction universalIdLookup = DeveloperOptionsItem::getUniversalIdValue;

    private static void simpleOverflowMenuFlags() {
        BOOL_FLAGS.put("104772", false); //ig_ini
        BOOL_FLAGS.put("117613::0", true); //ig_overflow_menu_icon::use_more_lines_icon
        BOOL_FLAGS.put("100002", true); //ig_igds_android_prism_overflow_sheet
    }

    private static void adsFlags() {
//        BOOL_FLAGS.put("58206::0", false); //is_acp_enabled
//        BOOL_FLAGS.put("72396::0", false); //is_mae_exclusion_feed_enabled
//        BOOL_FLAGS.put("78046::0", false); //is_mae_exclusion_feed_enabled
//        BOOL_FLAGS.put("78046::9", false); //enable_no_invalidation_reason_for_mae_exclusion
//        BOOL_FLAGS.put("79181::0", false); //ig_reels_ads_1x2_explore_halc_android::is_enabled
        BOOL_FLAGS.put("110800::0", false); //ig_android_controller_migration::use_v2_controller Removed in version 435.0.0.0.2
        BOOL_FLAGS.put("114983", false); //ig_stories_restyle_midcard
        BOOL_FLAGS.put("95150", false); //ig_stories_music_midcard
        BOOL_FLAGS.put("84366::12", false); //ig_stories_ayt_midcard::enable_add_yours
        BOOL_FLAGS.put("120110", false); //ig_android_scroll_break
        BOOL_FLAGS.put("105778", false); //ig_android_restyle_post_cap_promo_dialog
    }

    // Thanks to @brosssh
    private static void suggestedContentFlags() {
        BOOL_FLAGS.put("111509::3", false); //ig_search_ta_nullstate_suggestions::is_android_enabled
        BOOL_FLAGS.put("82771::0", false); //igx_foundation_litho_stories_tray::is_litho_stories_tray_enabled
//        BOOL_FLAGS.put("109730", false); //ig_android_ai_discovery_menu
//        BOOL_FLAGS.put("80654", false); //ig_meta_ai_cdd_reels_viewer
    }

    private static void profileActionBarFlags() {
        Set<String> pref = Pref.userProfileActionBarButtons();
        if (!pref.equals(Set.of(Constants.AB_CREATE))) {
            BOOL_FLAGS.put("81826::0", true); //igx_action_bar_service_replacement::is_profile_replaced
            BOOL_FLAGS.put("89230::0", true); //ig_android_profile_overflow_menu_redesign_launcher:enabled
        }
    }

    private static void mainFeedActionBarFlags() {
        Set<String> pref = Pref.mainFeedActionBarButtons();
        if (!pref.equals(Set.of(Constants.AB_CREATE, Constants.AB_NOTIFICATIONS))) {
            BOOL_FLAGS.put("81826::1", true); //igx_action_bar_service_replacement::is_main_feed_replaced
            BOOL_FLAGS.put("81826::4", true); //igx_action_bar_service_replacement::is_main_feed_large_screen_replaced
        }
    }

    private static void playbackSpeedFlags() {
        if (Pref.playbackSpeedControls()) {
            BOOL_FLAGS.put("103957::2", true); //ig_feed_video_2x_speed::enable_master_gating
            BOOL_FLAGS.put("103957::1", true); //ig_feed_video_2x_speed::enable_for_organic
            BOOL_FLAGS.put("103957::3", true); //ig_feed_video_2x_speed::enable_for_video
            BOOL_FLAGS.put("103957::4", true); //ig_feed_video_2x_speed::enable_for_carousel
            BOOL_FLAGS.put("103957::6", true); //ig_feed_video_2x_speed::use_static_edge_activation_threshold
            BOOL_FLAGS.put("103957::8", true); //ig_feed_video_2x_speed::android_fix_long_press_overlay
            BOOL_FLAGS.put("109947::0", true); //ig_android_reels_overflow_menu_playback_speed::is_enabled
            BOOL_FLAGS.put("121815::0", true); //ig_reels_long_press_overflow_menu::is_enabled
            BOOL_FLAGS.put("121815::12", true); //ig_reels_long_press_overflow_menu::context_playback_menu_enabled
        }
    }

    private static void floatingTabBarFlags() {
        if (Pref.floatingTabBar()) {
            BOOL_FLAGS.put("126024::12", true); //ig_android_modern_ui_edge_to_edge::is_floating_tab_bar_enabled
            BOOL_FLAGS.put("126024::2", true); //ig_android_modern_ui_edge_to_edge::is_bottom_enabled_for_main_activity
            BOOL_FLAGS.put("126411::0", true); //ig_android_modernization::enable_active_pills_on_tab_bar
        }
    }

    private static void hideMetaAiFlags() {
        if (Pref.hideMetaAi()) {
            BOOL_FLAGS.put("59918::2", false); //Show Meta AI entry points and prompts
            BOOL_FLAGS.put("59918::39", false);
            BOOL_FLAGS.put("59918::46", false);
            BOOL_FLAGS.put("59918::59", false);
            BOOL_FLAGS.put("59918::61", false);
            BOOL_FLAGS.put("62449::45", false);
            BOOL_FLAGS.put("62485::0", false);
            BOOL_FLAGS.put("62485::4", false);
            BOOL_FLAGS.put("62485::5", false);
            BOOL_FLAGS.put("62485::6", false); //igd_android_gen_ai_search_xstack::enable_search_bar_ui_improvements (DM search hint and Meta AI ring)
            BOOL_FLAGS.put("62485::11", false);
            BOOL_FLAGS.put("70108::0", false);
            BOOL_FLAGS.put("71155::6", false);
            BOOL_FLAGS.put("71628::0", false);
            BOOL_FLAGS.put("72519::10", false);
            BOOL_FLAGS.put("72519::11", false);
            BOOL_FLAGS.put("72519::18", false);
            BOOL_FLAGS.put("73643::0", false);
            BOOL_FLAGS.put("73643::1", false);
            BOOL_FLAGS.put("74933::17", false);
            BOOL_FLAGS.put("74933::36", false);
            BOOL_FLAGS.put("75278::0", false);
            BOOL_FLAGS.put("75278::9", false);
            BOOL_FLAGS.put("76479::0", false);
            BOOL_FLAGS.put("77059::11", false);
            BOOL_FLAGS.put("77059::14", false);
            BOOL_FLAGS.put("78149::0", false);
            BOOL_FLAGS.put("78149::1", false);
            BOOL_FLAGS.put("78149::2", false);
            BOOL_FLAGS.put("78970::1", false);
            BOOL_FLAGS.put("78970::37", false);
            BOOL_FLAGS.put("78970::38", false);
            BOOL_FLAGS.put("80654::1", false);
            BOOL_FLAGS.put("80654::37", false);
            BOOL_FLAGS.put("91489::6", false);
            BOOL_FLAGS.put("93548::1", false);
            BOOL_FLAGS.put("93786::0", false);
            BOOL_FLAGS.put("94598::84", false);
            BOOL_FLAGS.put("96235::1", false);
            BOOL_FLAGS.put("96543::0", false);
            BOOL_FLAGS.put("105426::21", false);
            BOOL_FLAGS.put("105426::27", false);
            BOOL_FLAGS.put("105426::41", false);
            BOOL_FLAGS.put("108911::19", false);
            BOOL_FLAGS.put("109730::0", false);
            BOOL_FLAGS.put("110316::0", false);
            BOOL_FLAGS.put("110316::1", false);
            BOOL_FLAGS.put("110316::11", false);
            BOOL_FLAGS.put("119736::6", false);
            BOOL_FLAGS.put("121836::0", false);
            BOOL_FLAGS.put("122162::2", false);
            BOOL_FLAGS.put("122162::3", false);
            BOOL_FLAGS.put("128853::13", false);
            BOOL_FLAGS.put("145349::1", false);
            BOOL_FLAGS.put("145349::3", false);
            BOOL_FLAGS.put("75278::4", true); //Hide additional Meta AI prompts and entry points
            BOOL_FLAGS.put("75278::5", true);
            BOOL_FLAGS.put("92518::1", true);
            BOOL_FLAGS.put("92518::2", true);
            BOOL_FLAGS.put("92518::6", true);
            BOOL_FLAGS.put("94598::56", true);
            BOOL_FLAGS.put("94598::117", true);
            BOOL_FLAGS.put("122162::1", true);
            BOOL_FLAGS.put("109730::0", false); //Add Meta AI in reels overflow menu
            BOOL_FLAGS.put("109730::5", false);
            BOOL_FLAGS.put("109730::6", false);
            BOOL_FLAGS.put("109730::7", false);
            BOOL_FLAGS.put("109730::9", false);
            BOOL_FLAGS.put("109730::14", false);
            BOOL_FLAGS.put("109730::15", false);
            BOOL_FLAGS.put("109730::17", false);
            BOOL_FLAGS.put("109730::20", false);
            BOOL_FLAGS.put("109730::21", false);
            BOOL_FLAGS.put("109730::22", false);
            BOOL_FLAGS.put("109730::23", false);
            BOOL_FLAGS.put("109730::25", false);
            BOOL_FLAGS.put("109730::26", false);
            BOOL_FLAGS.put("109730::27", false);
            BOOL_FLAGS.put("109730::29", false);
            BOOL_FLAGS.put("109730::30", false);
            BOOL_FLAGS.put("109730::31", false);
            BOOL_FLAGS.put("109730::32", false);
            BOOL_FLAGS.put("109730::34", false);
            BOOL_FLAGS.put("109730::35", false);
            BOOL_FLAGS.put("80654::0", false); //Add Meta AI reels description pills
            BOOL_FLAGS.put("80654::1", false);
            BOOL_FLAGS.put("80654::22", false);
            BOOL_FLAGS.put("80654::23", false);
            BOOL_FLAGS.put("80654::24", false);
            BOOL_FLAGS.put("80654::25", false);
            BOOL_FLAGS.put("80654::26", false);
            BOOL_FLAGS.put("80654::30", false);
            BOOL_FLAGS.put("80654::31", false);
            BOOL_FLAGS.put("80654::32", false);
            BOOL_FLAGS.put("80654::33", false);
            BOOL_FLAGS.put("80654::34", false);
            BOOL_FLAGS.put("80654::36", false);
            BOOL_FLAGS.put("80654::37", false);
            BOOL_FLAGS.put("80654::39", false);
            BOOL_FLAGS.put("111509::3", false); //Meta AI suggestions in search
        }
    }

    private static void hideProfileNotesBubbleFlags() {
        if (Pref.hideProfileNotesBubble()) {
            BOOL_FLAGS.put("135922::0", true); //ig4a_profile_unship_direct_notes::is_creation_disabled
            BOOL_FLAGS.put("135922::1", true); //ig4a_profile_unship_direct_notes::is_consumption_disabled
        }
    }

    private static void hideThreadsButtonFlags() {
        if (Pref.hideThreadsButton()) {
            BOOL_FLAGS.put("139714::0", true); //ig_profile_header_bar_cleanup::force_hide_threads_icon
        }
    }

    private static void hideFriendsMapFlags() {
        if (Pref.hideFriendsMap()) {
            BOOL_FLAGS.put("65057::0", false); //ig_android_nsx_nearby_spaces::enabled
        }
    }

    private static void squareExploreGridFlags() {
        if (Pref.squareExploreGrid()) {
            BOOL_FLAGS.put("101152::0", false); //ig_home_ai_pod_android::enable_tune_your_explore_android (3:4 Explore grid)
        }
    }

    private static void hideChatBlendButtonFlags() {
        if (Pref.hideChatBlendButton()) {
            BOOL_FLAGS.put("76757::0", false); //ig_android_blends_fbid::enable_thread_header_entrypoint (Blend master switch)
        }
    }

    private static void addRecommendedFlags(){
        if(SettingsStatus.recommendedFlags) {
            Map<String, Boolean> recFlags = FlagsSharedPref.getAll();
            BOOL_FLAGS.putAll(recFlags);
            Map<String, Long> recLongFlags = FlagsSharedPref.getAllLong();
            LONG_FLAGS.putAll(recLongFlags);
        }
    }

    public static void load() {
        addRecommendedFlags();
        boolOverrides = new FlagOverrides<>(BOOL_FLAGS);
        longOverrides = new FlagOverrides<>(LONG_FLAGS);
    }

    public static Boolean handleBoolFlags(long mobileConfigSpecifier) {
        FlagOverrides<Boolean> overrides = boolOverrides;
        if (overrides != null && overrides.isEmpty()) return null;
        try {
            // Sometimes I want to block all the subflags inside a universal ID.
            // In which case I would only add the universal ID in the BOOL_MAP map.
            // If a boolean value is found then it will return else it will check for for the usual config ID
            int universalIdValue = universalIdCache.get(mobileConfigSpecifier, universalIdLookup);
            if (overrides != null) return overrides.get(universalIdValue, FlagOverrides.paramId(mobileConfigSpecifier));
            String universalId = String.valueOf(universalIdValue);
            Boolean universalFlag = BOOL_FLAGS.getOrDefault(universalId, null);
            if(universalFlag!=null) return universalFlag;

            String configId = universalId + "::" + DeveloperOptionsItem.getParamId(mobileConfigSpecifier);
            return BOOL_FLAGS.getOrDefault(configId, null);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return null;
    }

    // mobileConfig has no dedicated int type, hence the integer-valued flags (counts, limits) are stored and returned.
    // as long instead of boolean
    public static Long handleLongFlags(long mobileConfigSpecifier) {
        FlagOverrides<Long> overrides = longOverrides;
        if (overrides != null && overrides.isEmpty()) return null;
        try {
            int universalIdValue = universalIdCache.get(mobileConfigSpecifier, universalIdLookup);
            if (overrides != null) return overrides.get(universalIdValue, FlagOverrides.paramId(mobileConfigSpecifier));
            String universalId = String.valueOf(universalIdValue);
            Long universalFlag = LONG_FLAGS.getOrDefault(universalId, null);
            if(universalFlag!=null) return universalFlag;

            String configId = universalId + "::" + DeveloperOptionsItem.getParamId(mobileConfigSpecifier);
            return LONG_FLAGS.getOrDefault(configId, null);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return null;
    }

}
