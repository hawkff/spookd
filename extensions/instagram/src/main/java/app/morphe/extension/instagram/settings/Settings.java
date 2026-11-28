/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/
package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.settings.RestartPolicy.REQUIRED;
import static app.morphe.extension.instagram.settings.RestartPolicy.NOT_REQUIRED;
import app.morphe.extension.instagram.patches.download.DownloadFileNameFormatter;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Settings {
    // Legacy stored key, kept so existing settings and exports still load.
    public static final BooleanSetting SPOOKD_DEBUG = new BooleanSetting("piko_debug", false, REQUIRED);
    public static final BooleanSetting MATERIAL_YOU_THEME = new BooleanSetting("material_you_theme", false, NOT_REQUIRED);
    public static final BooleanSetting AMOLED_THEME = new BooleanSetting("amoled_theme", false, NOT_REQUIRED);
    public static final StringSetting INSTAGRAM_THEME_MODE =
            new StringSetting("instagram_theme_mode", "-1", NOT_REQUIRED);
    public static final BooleanSetting INSTAGRAM_THEME_MODE_SYNCHRONIZED =
            new BooleanSetting("instagram_theme_mode_synchronized", false, NOT_REQUIRED);
    // It should be true always. It will be handled upon opening the spookd settings for the first time.
    // Legacy stored key, kept so existing settings and exports still load.
    public static final BooleanSetting FIRST_TIME_SPOOKD = new BooleanSetting("first_time_piko", true, NOT_REQUIRED);

    public static final BooleanSetting DISABLE_ADS = new BooleanSetting("disable_ads", true, REQUIRED);
    public static final BooleanSetting OPEN_LINKS_EXTERNALLY = new BooleanSetting("open_links_externally", true, NOT_REQUIRED);
    public static final BooleanSetting SANITIZE_SHARE_LINKS = new BooleanSetting("sanitize_share_links", true, NOT_REQUIRED);
    public static final StringSetting CUSTOM_SHARING_DOMAIN = new StringSetting("custom_sharing_domain", "", NOT_REQUIRED);
    public static final BooleanSetting DETECT_LINKS_IN_TEXT = new BooleanSetting("detect_links_in_text", false, REQUIRED);
    public static final BooleanSetting HIDE_SUGGESTED_CONTENT = new BooleanSetting("hide_suggested_content", true, REQUIRED);
    public static final BooleanSetting HIDE_META_AI = new BooleanSetting("hide_meta_ai", true, REQUIRED);
    public static final BooleanSetting DEVELOPER_OPTIONS = new BooleanSetting("enable_developer_options", false, NOT_REQUIRED);
    public static final BooleanSetting DIRECTLY_OPEN_METACONFIG = new BooleanSetting("directly_open_metaconfig",true, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_DISCOVER_PEOPLE = new BooleanSetting("disable_discover_people", true, REQUIRED);
    public static final BooleanSetting LIMIT_FOLLOWING_FEED = new BooleanSetting("limit_following_feed", false, REQUIRED);
    public static final BooleanSetting HIDE_FEED_POSTS = new BooleanSetting("hide_feed_posts", false, REQUIRED);
    public static final BooleanSetting TURN_ON_ALL_GHOST_MODES = new BooleanSetting("turn_on_all_ghost_modes", false, NOT_REQUIRED);
    public static final BooleanSetting VIEW_STORIES_ANONYMOUSLY = new BooleanSetting("view_stories_anonymously", false, NOT_REQUIRED);
    public static final StringSetting STORY_GHOST_MODE = new StringSetting("story_ghost_mode", "everyone", NOT_REQUIRED);
    public static final StringSetting STORY_GHOST_USERS = new StringSetting("story_ghost_users", "{}", NOT_REQUIRED);
    public static final BooleanSetting MARK_STORY_SEEN_ON_INTERACTION = new BooleanSetting("mark_story_seen_on_interaction", false, NOT_REQUIRED);
    public static final BooleanSetting MARK_EARLIER_STORIES_SEEN = new BooleanSetting("mark_earlier_stories_seen", false, NOT_REQUIRED);
    public static final BooleanSetting ADVANCE_STORY_ON_MARK_SEEN = new BooleanSetting("advance_story_on_mark_seen", false, NOT_REQUIRED);
    public static final BooleanSetting ADVANCE_STORY_ON_LIKE = new BooleanSetting("advance_story_on_like", false, NOT_REQUIRED);
    public static final BooleanSetting ADVANCE_STORY_ON_REPLY = new BooleanSetting("advance_story_on_reply", false, NOT_REQUIRED);
    public static final BooleanSetting VIEW_LIVE_ANONYMOUSLY = new BooleanSetting("view_live_anonymously", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_SCREENSHOT_DETECTION = new BooleanSetting("disable_screenshot_detection", false, NOT_REQUIRED);
    public static final BooleanSetting VIEW_DM_ANONYMOUSLY = new BooleanSetting("view_dm_anonymously", false, NOT_REQUIRED);
    // Pre-selection key; migrated once to MARK_SEEN_ON in Pref.
    public static final BooleanSetting MARK_AS_READ_AFTER_REPLY = new BooleanSetting("mark_as_read_after_reply", false, NOT_REQUIRED);
    public static final StringSetting MARK_SEEN_ON = new StringSetting("dm_mark_seen_on", "", NOT_REQUIRED);
    public static final BooleanSetting KEEP_SEEN_LOCALLY = new BooleanSetting("dm_keep_seen_locally", false, NOT_REQUIRED);
    public static final StringSetting DM_GHOST_MODE = new StringSetting("dm_ghost_mode", "everyone", NOT_REQUIRED);
    public static final StringSetting DM_GHOST_CHATS = new StringSetting("dm_ghost_chats", "{}", NOT_REQUIRED);
    public static final BooleanSetting INSTANTS_DOWNLOAD = new BooleanSetting("instants_download", false, NOT_REQUIRED);
    public static final BooleanSetting INSTANTS_DONT_MARK_SEEN = new BooleanSetting("instants_dont_mark_seen", false, NOT_REQUIRED);
    public static final StringSetting INSTANTS_ALWAYS_SEEN_USERS = new StringSetting("instants_always_seen_users", "{}", NOT_REQUIRED);
    public static final StringSetting INSTANTS_NEVER_SEEN_USERS = new StringSetting("instants_never_seen_users", "{}", NOT_REQUIRED);
    public static final BooleanSetting DISABLE_INSTANTS_CREATION = new BooleanSetting("disable_instants_creation", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_INSTANT_VIDEO = new BooleanSetting("confirm_instant_video", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_INSTANT_REACTION = new BooleanSetting("confirm_instant_reaction", false, NOT_REQUIRED);
    public static final BooleanSetting SEND_MEDIA_AS_INSTANTS = new BooleanSetting("send_media_as_instants", false, NOT_REQUIRED);
    // Instagram sets up its location collection and listeners at startup.
    public static final BooleanSetting FAKE_LOCATION = new BooleanSetting("fake_location", false, REQUIRED);
    public static final BooleanSetting SAVE_DELETED_MESSAGES = new BooleanSetting("save_deleted_messages", false, NOT_REQUIRED);
    public static final BooleanSetting DM_MEDIA_VAULT = new BooleanSetting("dm_media_vault", false, NOT_REQUIRED);
    public static final BooleanSetting DM_MEDIA_VAULT_VIEW_ONCE = new BooleanSetting("dm_media_vault_view_once", true, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_STORIES = new BooleanSetting("disable_stories", false, REQUIRED);
    public static final BooleanSetting DISABLE_HIGHLIGHTS = new BooleanSetting("disable_highlights", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_STORIES_TRAY = new BooleanSetting("hide_stories_tray", false, REQUIRED);
    public static final BooleanSetting HIDE_HIDDEN_REQUESTS = new BooleanSetting("hide_hidden_requests", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_EXPLORE = new BooleanSetting("disable_explore", false, REQUIRED);
    public static final BooleanSetting DISABLE_COMMENTS = new BooleanSetting("disable_comments", false, REQUIRED);
    public static final BooleanSetting FOLLOW_BACK_INDICATOR = new BooleanSetting("follow_back_indicator", true, NOT_REQUIRED);
    public static final BooleanSetting FOLLOW_BACK_COLOR_INDICATOR = new BooleanSetting("follow_back_color_indicator", true, NOT_REQUIRED);
    public static final BooleanSetting FOLLOW_LIST_NON_FOLLOWER_BADGE = new BooleanSetting("follow_list_non_follower_badge", true, NOT_REQUIRED);
    public static final BooleanSetting EXACT_PROFILE_COUNTS = new BooleanSetting("exact_profile_counts", true, NOT_REQUIRED);
    public static final BooleanSetting SQUARE_PROFILE_GRID = new BooleanSetting("square_profile_grid", true, NOT_REQUIRED);
    public static final BooleanSetting PROFILE_SAVED_TAB = new BooleanSetting("profile_saved_tab", true, REQUIRED);
    public static final BooleanSetting HIDE_PROFILE_GRID_REELS = new BooleanSetting("hide_profile_grid_reels", false, NOT_REQUIRED);
    // "default", "keep" or "remove"; read each time a profile's Reels tab works out its sort menu.
    public static final StringSetting PROFILE_REELS_SORT = new StringSetting("profile_reels_sort", "default", NOT_REQUIRED);
    public static final BooleanSetting REORDER_HIGHLIGHTS = new BooleanSetting("reorder_highlights", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_LIKE = new BooleanSetting("confirm_like", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_FOLLOW = new BooleanSetting("confirm_follow", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_REPOST = new BooleanSetting("confirm_repost", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_COMMENT = new BooleanSetting("confirm_comment", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_UNFOLLOW = new BooleanSetting("confirm_unfollow", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_STORY_LIKE = new BooleanSetting("confirm_story_like", false, REQUIRED);
    public static final BooleanSetting CONFIRM_CALL = new BooleanSetting("confirm_call", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_FOLLOW_REQUEST = new BooleanSetting("confirm_follow_request", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_REFRESH = new BooleanSetting("confirm_refresh", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_COMMENT_LIKE = new BooleanSetting("confirm_comment_like", false, NOT_REQUIRED);
    public static final BooleanSetting CONFIRM_DM_ACTIONS = new BooleanSetting("confirm_dm_actions", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_HOME_TAB_REFRESH = new BooleanSetting("disable_home_tab_refresh", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_HOME_BACKGROUND_REFRESH = new BooleanSetting("disable_home_background_refresh", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_REELS_TAB_REFRESH = new BooleanSetting("disable_reels_tab_refresh", false, NOT_REQUIRED);
    public static final BooleanSetting VIEW_STORY_MENTIONS = new BooleanSetting("view_story_mentions", true, REQUIRED);
    public static final BooleanSetting DISABLE_STORY_FLIPPING = new BooleanSetting("disable_story_flipping", false, NOT_REQUIRED);
    public static final BooleanSetting LOOP_STORY = new BooleanSetting("loop_story", false, NOT_REQUIRED);
    public static final BooleanSetting MUTE_STORIES = new BooleanSetting("mute_stories", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_STORY_FOLLOW_BUTTON = new BooleanSetting("hide_story_follow_button", false, NOT_REQUIRED);
    public static final StringSetting CUSTOMISE_STORY_TIMESTAMP = new StringSetting("customise_story_timestamp", "default", REQUIRED);
    public static final StringSetting STORY_AUTO_SAVE = new StringSetting("story_auto_save", "off", NOT_REQUIRED);
    public static final StringSetting STORY_AUTO_SAVE_USERS = new StringSetting("story_auto_save_users", "{}", NOT_REQUIRED);
    public static final BooleanSetting SEARCH_STORY_VIEWERS = new BooleanSetting("search_story_viewers", false, NOT_REQUIRED);
    public static final BooleanSetting COPY_STORY_LINK = new BooleanSetting("copy_story_link", false, NOT_REQUIRED);
    public static final BooleanSetting UNLIMITED_REPLAYS = new BooleanSetting("unlimited_replays", false, REQUIRED);
    public static final BooleanSetting DISABLE_DISAPPEARING_MESSAGES_GESTURE = new BooleanSetting("disable_disappearing_messages_gesture", false, REQUIRED);
    public static final BooleanSetting SEND_KEYBOARD_GIFS = new BooleanSetting("send_keyboard_gifs", true, NOT_REQUIRED);
    public static final BooleanSetting SEND_AUDIO_AS_VOICE = new BooleanSetting("send_audio_as_voice", false, NOT_REQUIRED);
    public static final BooleanSetting EPHEMERAL_MEDIA_CAPTION = new BooleanSetting("ephemeral_media_caption", false, NOT_REQUIRED);
    public static final BooleanSetting STOP_VISUAL_MESSAGE_AUTO_ADVANCE = new BooleanSetting("stop_visual_message_auto_advance", false, NOT_REQUIRED);
    public static final BooleanSetting MARK_VISUAL_MESSAGES_SEEN_MANUALLY = new BooleanSetting("mark_visual_messages_seen_manually", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_LIKES = new BooleanSetting("hide_likes", false, REQUIRED);
    public static final BooleanSetting HIDE_SHARE_BUTTON = new BooleanSetting("hide_share_button", false, NOT_REQUIRED);
    public static final StringSetting HIDE_SAVE_BUTTON_SURFACES = new StringSetting("hide_save_button_surfaces", "", NOT_REQUIRED);
    // Pre-per-surface key; migrated once to the surfaces selection in Pref.
    public static final BooleanSetting HIDE_SAVE_BUTTONS_LEGACY = new BooleanSetting("hide_save_buttons", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_RESHARE_BUTTON = new BooleanSetting("hide_reshare_button", false, NOT_REQUIRED);
    public static final BooleanSetting INBOX_LOCK = new BooleanSetting("inbox_lock", false, REQUIRED);
    public static final BooleanSetting INBOX_LOCK_NOTIFICATIONS = new BooleanSetting("inbox_lock_notifications", false, NOT_REQUIRED);
    public static final BooleanSetting HIDDEN_CHATS = new BooleanSetting("hidden_chats", false, REQUIRED);
    public static final StringSetting HIDDEN_CHATS_TAPS = new StringSetting("hidden_chats_taps", "3", NOT_REQUIRED);
    public static final BooleanSetting HIDDEN_CHATS_MUTE = new BooleanSetting("hidden_chats_mute", true, NOT_REQUIRED);
    public static final BooleanSetting PIN_RECIPIENTS = new BooleanSetting("pin_recipients", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_REELS_FOLLOW_BUTTON = new BooleanSetting("hide_reels_follow_button", false, NOT_REQUIRED);
    public static final BooleanSetting IMPROVE_IMAGE_VIEWING = new BooleanSetting("improve_image_viewing", false, REQUIRED);
    public static final BooleanSetting RESUME_REELS_AFTER_SCROLLING = new BooleanSetting("resume_reels_after_scrolling", false, NOT_REQUIRED);
    public static final BooleanSetting PLAYBACK_SPEED_CONTROLS = new BooleanSetting("playback_speed_controls", true, REQUIRED);
    public static final StringSetting DEFAULT_PLAYBACK_SPEED = new StringSetting("default_playback_speed", "1", NOT_REQUIRED);
    public static final BooleanSetting REELS_START_MUTED = new BooleanSetting("reels_start_muted", false, NOT_REQUIRED);
    public static final BooleanSetting REELS_STOP_LOOPING = new BooleanSetting("reels_stop_looping", false, NOT_REQUIRED);
    public static final BooleanSetting REELS_TAP_TO_PAUSE = new BooleanSetting("reels_tap_to_pause", false, NOT_REQUIRED);
    public static final BooleanSetting REELS_HOLD_TO_PAUSE = new BooleanSetting("reels_hold_to_pause", false, REQUIRED);
    public static final BooleanSetting EXPANDED_FEED_VIDEOS_MUTED = new BooleanSetting("expanded_feed_videos_muted", false, NOT_REQUIRED);
    // "default", "on" or "off"; read once at startup into Instagram's flags.
    public static final StringSetting REELS_AUTO_SCROLL = new StringSetting("reels_auto_scroll", "default", REQUIRED);
    // Each Reels viewer sets up picture-in-picture when it is created; read once per process.
    public static final BooleanSetting PIP_FOR_VIDEOS = new BooleanSetting("pip_for_videos", false, REQUIRED);
    public static final BooleanSetting KEEP_OTHER_AUDIO = new BooleanSetting("keep_other_audio", true, NOT_REQUIRED);
    public static final BooleanSetting COMMENT_COPY_BUTTON = new BooleanSetting("comment_copy_button", true, NOT_REQUIRED);
    public static final BooleanSetting COMMENT_SAVE_MEDIA_BUTTON = new BooleanSetting("comment_save_media_button", true, NOT_REQUIRED);
    public static final BooleanSetting HIDE_GROUP_CREATION_BUTTON_ON_SHARESHEET = new BooleanSetting("hide_group_creation_button_on_sharesheet", true, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_REELS_SCROLLING = new BooleanSetting("disable_reels_scrolling", false, REQUIRED);
    public static final BooleanSetting DISABLE_SWIPE_TO_CREATE = new BooleanSetting("disable_swipe_to_create", false, NOT_REQUIRED);
    public static final StringSetting FOCUS_LOCK_DURATION_MINUTES = new StringSetting("focus_lock_duration_minutes", "10080", NOT_REQUIRED);
    // Epoch millis. "0" means not locked.
    public static final StringSetting FOCUS_LOCK_UNTIL = new StringSetting("focus_lock_until", "0", NOT_REQUIRED);
    // Layout the stored lock was written with, so an older one can be retired. See FocusLock.
    public static final StringSetting FOCUS_LOCK_FORMAT = new StringSetting("focus_lock_format", "", NOT_REQUIRED);
    // Epoch millis of the pending unlock request. "0" means none.
    public static final StringSetting FOCUS_LOCK_UNLOCK_REQUESTED_AT = new StringSetting("focus_lock_unlock_requested_at", "0", NOT_REQUIRED);
    public static final BooleanSetting REMOVE_EMPTY_BOTTOM_SPACE = new BooleanSetting("remove_empty_bottom_space", true, REQUIRED);
    public static final BooleanSetting DISABLE_TYPING_STATUS = new BooleanSetting("disable_typing_status", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NOTES_TRAY = new BooleanSetting("hide_notes_tray", false, REQUIRED);
    public static final BooleanSetting DISABLE_VIDEO_AUTOPLAY = new BooleanSetting("disable_video_autoplay", false, NOT_REQUIRED);
    public static final StringSetting CHANGE_LIKE_ANIMATION = new StringSetting("change_like_animation", "ARES_LIKE_ACTIVATION", REQUIRED);
    public static final StringSetting CUSTOMISE_STORY_RING_SIZE = new StringSetting("customise_story_ring_size", "100", REQUIRED);
    public static final BooleanSetting ENABLE_MORE_OPTIONS_ON_POST = new BooleanSetting("enable_more_option_on_post", true, NOT_REQUIRED);
    public static final BooleanSetting USE_SYSTEM_FONT = new BooleanSetting("use_system_font", false, REQUIRED);

    public static final BooleanSetting DISABLE_DOUBLE_TAP_LIKE_POST = new BooleanSetting("disable_double_tap_like_post", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_DOUBLE_TAP_LIKE_REEL = new BooleanSetting("disable_double_tap_like_reel", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_DOUBLE_TAP_LIKE_COMMENT = new BooleanSetting("disable_double_tap_like_comment", false, NOT_REQUIRED);
    public static final BooleanSetting DISABLE_DOUBLE_TAP_LIKE_MESSAGE = new BooleanSetting("disable_double_tap_like_message", false, NOT_REQUIRED);

    public static final BooleanSetting ENABLE_DOWNLOAD = new BooleanSetting("enable_download", true, REQUIRED);
    public static final BooleanSetting FEED_DOWNLOAD_BUTTON = new BooleanSetting("feed_download_button", true, NOT_REQUIRED);
    public static final BooleanSetting REEL_DOWNLOAD_BUTTON = new BooleanSetting("reel_download_button", true, NOT_REQUIRED);
    public static final BooleanSetting STORY_DOWNLOAD_BUTTON = new BooleanSetting("story_download_button", true, NOT_REQUIRED);
    public static final BooleanSetting AUDIO_PAGE_DOWNLOAD_BUTTON = new BooleanSetting("audio_page_download_button", true, NOT_REQUIRED);
    public static final BooleanSetting ENABLE_DIRECT_DOWNLOAD = new BooleanSetting("enable_direct_download", false, REQUIRED);
    public static final StringSetting DOWNLOAD_CONFLICT_POLICY = new StringSetting("download_conflict_policy", "rename", NOT_REQUIRED);
    public static final BooleanSetting DOWNLOAD_USERNAME_FOLDER = new BooleanSetting("download_username_folder", false, NOT_REQUIRED);
    public static final BooleanSetting EMBED_DOWNLOAD_METADATA = new BooleanSetting("embed_download_metadata", false, NOT_REQUIRED);
    public static final BooleanSetting PREFER_DASH_FORMAT = new BooleanSetting("prefer_dash_format", true, NOT_REQUIRED);
    public static final BooleanSetting DASH_AUDIO_AAC_LC = new BooleanSetting("dash_audio_aac_lc", false, NOT_REQUIRED);
    public static final StringSetting DOWNLOAD_FILE_NAME_TEMPLATE =
            new StringSetting("download_file_name_template", DownloadFileNameFormatter.DEFAULT_TEMPLATE, NOT_REQUIRED);
    // Should be kept empty by default as its handled in `StorageUtils.java`
    public static final StringSetting CUSTOM_DOWNLOAD_PATH = new StringSetting("custom_download_path", "", NOT_REQUIRED);
    public static final StringSetting EXTERNAL_DOWNLOADER_PACKAGE_NAME = new StringSetting("external_downloader_package_name", "", NOT_REQUIRED);
    public static final BooleanSetting DOWNLOAD_WITH_EXTERNAL_DOWNLOADER = new BooleanSetting("download_with_external_downloader", true, NOT_REQUIRED);
    public static final BooleanSetting SAVE_DOWNLOADS_TO_PHONE_STORAGE = new BooleanSetting("save_downloads_to_phone_storage", false, NOT_REQUIRED);
    // Opt-in private gallery cleanup; "0" turns each limit off.
    public static final StringSetting GALLERY_MAX_AGE_DAYS = new StringSetting("gallery_max_age_days", "0", NOT_REQUIRED);
    public static final StringSetting GALLERY_MAX_SIZE_GB = new StringSetting("gallery_max_size_gb", "0", NOT_REQUIRED);
    public static final BooleanSetting HOME_LONG_PRESS_GALLERY = new BooleanSetting("home_long_press_gallery", false, NOT_REQUIRED);
    public static final BooleanSetting GALLERY_PICKER = new BooleanSetting("gallery_picker", false, NOT_REQUIRED);

    public static final BooleanSetting HIDE_NAVIGATION_FEED = new BooleanSetting("hide_navigation_feed", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NAVIGATION_REELS = new BooleanSetting("hide_navigation_reels", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NAVIGATION_DIRECT = new BooleanSetting("hide_navigation_direct", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NAVIGATION_SEARCH = new BooleanSetting("hide_navigation_search", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_NAVIGATION_CREATE = new BooleanSetting("hide_navigation_create", false, NOT_REQUIRED);
    public static final StringSetting NAVIGATION_TABS = new StringSetting("navigation_tabs", "", REQUIRED);
    public static final BooleanSetting FLOATING_TAB_BAR = new BooleanSetting("floating_tab_bar", true, REQUIRED);
    public static final BooleanSetting FLOATING_TAB_BAR_HIDE_ON_SCROLL = new BooleanSetting("floating_tab_bar_hide_on_scroll", true, NOT_REQUIRED);
    public static final BooleanSetting FLOATING_TAB_BAR_GLASS = new BooleanSetting("floating_tab_bar_glass", false, NOT_REQUIRED);
    // "default", "on" or "off"; read once at startup into Instagram's flags.
    public static final StringSetting SWIPE_BETWEEN_TABS = new StringSetting("swipe_between_tabs", "default", REQUIRED);
    // What holding each tab opens (TabShortcuts values), read on every hold.
    public static final StringSetting TAB_SHORTCUT_HOME = new StringSetting("tab_shortcut_home", "menu", NOT_REQUIRED);
    public static final StringSetting TAB_SHORTCUT_REELS = new StringSetting("tab_shortcut_reels", "default", NOT_REQUIRED);
    public static final StringSetting TAB_SHORTCUT_MESSAGES = new StringSetting("tab_shortcut_messages", "default", NOT_REQUIRED);
    public static final StringSetting TAB_SHORTCUT_SEARCH = new StringSetting("tab_shortcut_search", "default", NOT_REQUIRED);
    public static final StringSetting TAB_SHORTCUT_PROFILE = new StringSetting("tab_shortcut_profile", "default", NOT_REQUIRED);
    public static final StringSetting TAB_SHORTCUT_NOTIFICATIONS = new StringSetting("tab_shortcut_notifications", "default", NOT_REQUIRED);
    public static final StringSetting TAB_SHORTCUT_CREATE = new StringSetting("tab_shortcut_create", "default", NOT_REQUIRED);
    public static final BooleanSetting HIDE_HOME_CREATE_BUTTON = new BooleanSetting("hide_home_create_button", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_HOME_NOTIFICATIONS_BUTTON = new BooleanSetting("hide_home_notifications_button", false, NOT_REQUIRED);

    public static final StringSetting ACTION_BAR_MAIN_FEED = new StringSetting("action_bar_main_feed", "", REQUIRED);
    public static final StringSetting ACTION_BAR_USER_PROFILE = new StringSetting("action_bar_user_profile", "", REQUIRED);
    public static final StringSetting ACTION_BAR_CHAT = new StringSetting("action_bar_chat_buttons", "", REQUIRED);
    public static final StringSetting ACTION_BAR_INBOX = new StringSetting("action_bar_inbox", "", REQUIRED);

    public static final BooleanSetting ENABLE_MARK_CHAT_AS_READ = new BooleanSetting("enable_mark_chat_as_read", true, NOT_REQUIRED);

    public static final StringSetting FILTER_STORY_BY_TYPE = new StringSetting("filter_story_by_type", "", NOT_REQUIRED);
    public static final StringSetting FILTER_STORY_BY_USER_TYPE = new StringSetting("filter_story_by_user_type", "", NOT_REQUIRED);

    public static final StringSetting FILTER_STORY_MIN_STORY_ITEMS = new StringSetting("filter_story_min_story_items", "1", NOT_REQUIRED);
    public static final StringSetting FILTER_STORY_MAX_STORY_ITEMS = new StringSetting("filter_story_max_story_items", "9999", NOT_REQUIRED);

    public static final BooleanSetting HIDE_COMMENT_EMOJI_BAR = new BooleanSetting("hide_comment_emoji_bar", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_REELS_VISUAL_SEARCH = new BooleanSetting("hide_reels_visual_search", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_PROFILE_NOTES_BUBBLE = new BooleanSetting("hide_profile_notes_bubble", false, REQUIRED);
    public static final BooleanSetting HIDE_THREADS_BUTTON = new BooleanSetting("hide_threads_button", false, REQUIRED);
    public static final BooleanSetting HIDE_FRIENDS_MAP = new BooleanSetting("hide_friends_map", false, REQUIRED);
    public static final BooleanSetting HIDE_RECENT_SEARCHES = new BooleanSetting("hide_recent_searches", false, NOT_REQUIRED);
    public static final BooleanSetting SQUARE_EXPLORE_GRID = new BooleanSetting("square_explore_grid", false, REQUIRED);
    public static final BooleanSetting HIDE_REELS_COMMENT_BAR = new BooleanSetting("hide_reels_comment_bar", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_CHAT_BLEND_BUTTON = new BooleanSetting("hide_chat_blend_button", false, REQUIRED);
    public static final BooleanSetting HIDE_INBOX_INSTANTS = new BooleanSetting("hide_inbox_instants", false, NOT_REQUIRED);
    public static final BooleanSetting HIDE_REELS_TAB_HEADER = new BooleanSetting("hide_reels_tab_header", false, REQUIRED);
    public static final BooleanSetting RESTORE_ACCOUNT_SEARCH = new BooleanSetting("restore_account_search", false, REQUIRED);
    public static final BooleanSetting HIDE_MUSE = new BooleanSetting("hide_muse", false, REQUIRED);
    public static final BooleanSetting DISABLE_APP_HAPTICS = new BooleanSetting("disable_app_haptics", false, NOT_REQUIRED);
    public static final BooleanSetting TRACK_VISITED_PROFILES = new BooleanSetting("track_visited_profiles", false, NOT_REQUIRED);
    // Days between automatic Profile analyzer scans; "0" turns them off.
    public static final StringSetting PROFILE_ANALYZER_AUTO_REFRESH_DAYS = new StringSetting("profile_analyzer_auto_refresh_days", "0", NOT_REQUIRED);
    public static final BooleanSetting EXACT_LAST_ACTIVE = new BooleanSetting("exact_last_active", false, NOT_REQUIRED);
    public static final BooleanSetting ACTIVITY_ALERT_ONLINE = new BooleanSetting("activity_alert_online", false, NOT_REQUIRED);
    public static final BooleanSetting ACTIVITY_ALERT_OFFLINE = new BooleanSetting("activity_alert_offline", false, NOT_REQUIRED);
    public static final BooleanSetting ACTIVITY_ALERT_TYPING = new BooleanSetting("activity_alert_typing", false, NOT_REQUIRED);
    public static final BooleanSetting ACTIVITY_ALERT_READ = new BooleanSetting("activity_alert_read", false, NOT_REQUIRED);
    public static final StringSetting ACTIVITY_ALERT_USERS = new StringSetting("activity_alert_users", "{}", NOT_REQUIRED);
    // Minutes between Instagram's active status refreshes while the app is open, or "default".
    public static final StringSetting PRESENCE_REFRESH_MINUTES = new StringSetting("presence_refresh_minutes", "default", NOT_REQUIRED);
    public static final BooleanSetting HOLD_SEND_COPY_LINK = new BooleanSetting("hold_send_copy_link", false, REQUIRED);
    public static final BooleanSetting OPEN_SETTINGS_ON_LAUNCH = new BooleanSetting("open_settings_on_launch", false, NOT_REQUIRED);
    public static final BooleanSetting SETTINGS_LOCK = new BooleanSetting("settings_lock", false, NOT_REQUIRED);
    // Megabytes; "0" turns it off.
    public static final StringSetting AUTO_CLEAR_CACHE_MB = new StringSetting("auto_clear_cache_mb", "0", NOT_REQUIRED);
    public static final BooleanSetting SHOW_NEW_LABELS = new BooleanSetting("show_new_labels", true, NOT_REQUIRED);

    // Keys of the rows labeled NEW in settings; reset at each release.
    public static final Set<String> NEW_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            PIP_FOR_VIDEOS.key,
            GALLERY_PICKER.key,
            MARK_SEEN_ON.key,
            KEEP_SEEN_LOCALLY.key,
            TAB_SHORTCUT_HOME.key,
            TAB_SHORTCUT_REELS.key,
            TAB_SHORTCUT_MESSAGES.key,
            TAB_SHORTCUT_SEARCH.key,
            TAB_SHORTCUT_PROFILE.key,
            TAB_SHORTCUT_NOTIFICATIONS.key,
            TAB_SHORTCUT_CREATE.key,
            KEEP_OTHER_AUDIO.key,
            SHOW_NEW_LABELS.key,
            PROFILE_ANALYZER_AUTO_REFRESH_DAYS.key,
            RESTORE_ACCOUNT_SEARCH.key,
            REELS_HOLD_TO_PAUSE.key,
            HIDE_MUSE.key,
            SETTINGS_LOCK.key,
            "spookd_report_bug",
            "spookd_request_feature",
            "spookd_gallery_backup"
    )));
}
