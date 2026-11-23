/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.constants;

import static app.morphe.extension.instagram.utils.IgStr.str;

import app.morphe.extension.crimera.constants.ExtensionStrings;
import app.morphe.extension.crimera.legacydownloader.StorageUtils;
import app.morphe.extension.instagram.settings.ActivityHook;

public class Constants {
    public static final String SPOOKD = "spookd";
    // Legacy stored name: the live settings file of existing installs.
    public static final String SPOOKD_SETTINGS = "piko_settings";
    public static final String SHARED_PREF_NAME = SPOOKD_SETTINGS;
    public static final String DEFAULT_SPOOKD_FOLDER = "spookd-Instagram";
    public static final String DEFAULT_AUDIO_FOLDER = "Audio";
    public static final String DEFAULT_DM_FOLDER = "DM";
    public static final String DEFAULT_GIF_FOLDER = "Gif";
    public static final String INSTAGRAM_SHARE_LINK = "https://www.instagram.com/%s/%s/";
    // https://www.instagram.com/p/<short code>/
    // https://www.instagram.com/reel/<short code>/
    // https://www.instagram.com/stories/<user name>/<post id>
    // Legacy stored name: the flag overrides file and the downloaded catalog file are named after it.
    public static final String REC_FLAGS = "piko_recommended_flags_v3";
    public static final String SPOOKD_MAPPINGS_PATH = "https://github.com/crimera/piko/raw/refs/heads/dev/docs/mappings/";

    public static final String AB_SETTINGS_ICON = "SETTINGS_ICON";
    public static final String AB_AUDIO_CALL_ICON = "AUDIO_CALL_ICON";
    public static final String AB_VIDEO_CALL_ICON = "VIDEO_CALL_ICON";
    public static final String AB_CREATE = "CREATE";
    public static final String AB_NOTIFICATIONS = "NOTIFICATIONS";
    public static final String AB_GHOST_MODE_ICON = "GHOST_MODE_ICON";
    public static final String AB_JUMP_OLDEST_ICON = "JUMP_OLDEST_ICON";
    public static final String AB_PROFILE_INFO_ICON = "PROFILE_INFO_ICON";

    public static final String SPOOKD_FRAGMENT_TITLE = "spookd_frag_title";
    public static final String SPOOKD_FRAGMENT_NAME = "spookd_frag_name";
    public static final String SPOOKD_SEARCH_TARGET_PREFERENCE_KEY = "spookd_search_target_preference_key";
    public static final String SPOOKD_APP_VERSION_PREFERENCE = "spookd_app_version_preference";
    public static final String SPOOKD_PATCH_VERSION_PREFERENCE = "spookd_patch_version_preference";
    public static final String SPOOKD_PATCH_INFO_PREFERENCE_CATEGORY = "spookd_patch_info_preference_category";

    public static final String SPOOKD_FRAGMENT_SETTINGS = "spookd_frag_settings";
    public static final String SPOOKD_FRAGMENT_ADS = "spookd_frag_ads";
    public static final String SPOOKD_FRAGMENT_GHOST = "spookd_frag_ghost";
    public static final String SPOOKD_FRAGMENT_STORY = "spookd_frag_story";
    public static final String SPOOKD_FRAGMENT_LINKS = "spookd_frag_links";
    public static final String SPOOKD_FRAGMENT_DISTRACTION_FREE = "spookd_frag_distraction_free";
    public static final String SPOOKD_FRAGMENT_FOCUS_LOCK = "spookd_frag_focus_lock";
    public static final String SPOOKD_FRAGMENT_MISC = "spookd_frag_misc";
    public static final String SPOOKD_FRAGMENT_DOWNLOAD_MEDIA = "spookd_frag_download_media";
    public static final String SPOOKD_FRAGMENT_NAV_BTNS = "spookd_frag_nav_btns";
    public static final String SPOOKD_FRAGMENT_DEV_OPTIONS = "spookd_frag_dev_options";
    public static final String SPOOKD_FRAGMENT_ABOUT = "spookd_frag_about";
    public static final String SPOOKD_FRAGMENT_ACTION_BAR = "spookd_frag_action_bar";
    public static final String SPOOKD_FRAGMENT_DM = "spookd_frag_dm";
    public static final String SPOOKD_FRAGMENT_FILTER_CONTENT = "spookd_frag_filter_content";
    public static final String SPOOKD_FRAGMENT_REC_FLAGS = "spookd_frag_rec_flags";
    public static final String SPOOKD_FRAGMENT_INSTANTS = "spookd_frag_instants";
    public static final String SPOOKD_FRAGMENT_FONT = "spookd_frag_font";

    public static void load() {
        StorageUtils.setStorageAccessLauncher(ctx -> ActivityHook.launchFragment(ctx, "spookd_download_set_path"));
        ExtensionStrings.setDefaultPikoFolder(Constants.DEFAULT_SPOOKD_FOLDER);
        ExtensionStrings.setDownloadOngoing(str("spookd_downloading_media"));
        ExtensionStrings.setDownloadCompleted(str("spookd_downloaded_media"));
        ExtensionStrings.DOWNLOAD_MEDIA_EXISTS = str("spookd_download_media_exists");
        ExtensionStrings.DOWNLOAD_MEDIA_IN_PROGRESS = str("spookd_download_media_in_progress");
        // The downloader appends the failure reason; older translations still end with a colon.
        ExtensionStrings.setDownloadError(str("spookd_download_failed_media").replaceAll("[\\s:\\uFF1A]+$", "") + ": ");
        ExtensionStrings.setDownloadSetPathFailed(str("spookd_download_set_path_failed"));
        ExtensionStrings.setDownloadSetPathSuccess(str("spookd_download_set_path_success"));
        ExtensionStrings.setDownloadGrantPermission(str("spookd_download_choose_folder"));
        ExtensionStrings.setDownloadGrantPermissionFailed(str("spookd_download_choose_folder_failed"));
    }
}
