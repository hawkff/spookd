/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.utils;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.view.View;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.instagram.settings.BooleanSetting;
import app.morphe.extension.instagram.settings.StringSetting;

import app.morphe.extension.instagram.settings.Settings;
import static app.morphe.extension.instagram.settings.RestartPolicy.NOT_REQUIRED;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.patches.focusLock.FocusLock;
import app.morphe.extension.instagram.patches.download.ReelDownload;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.shared.MarkChatAsReadScope;

@SuppressWarnings("unused")
public class Pref {
    // Instagram picks the variant closest to the target plus 5%, so a target this large always
    // selects the widest; half of MAX_VALUE keeps that 5% from overflowing.
    private static final int WIDEST_VARIANT_TARGET = Integer.MAX_VALUE / 2;
    // Instagram's full-screen views cap the width they request an image variant for at this.
    private static final int FULL_SCREEN_WIDTH_CAP = 1080;
    // Instagram sizes image variants by the User-Agent's shorter screen side. At 2560 posts get
    // their originals and grid tiles 750 px variants; from 2880 grid tiles get 1080 px.
    private static final int USER_AGENT_SCREEN_SIZE = 2560;

    private static String removeLineBreaks(String value) {
        return value.replace("\r", "").replace("\n", "");
    }

    public static boolean clearAllPreferences() {
        // Resetting settings would silently drop an active Focus Lock.
        if (FocusLock.isActive()) return false;
        return SharedPref.clearAll();
    }
    
    public static boolean spookdDebug() {
        return SharedPref.getBooleanPref(Settings.SPOOKD_DEBUG);
    }

    public static boolean firstTimeSpookd() {
        return SharedPref.getBooleanPref(Settings.FIRST_TIME_SPOOKD);
    }
    public static boolean setFirstTimeSpookd(boolean bool) {
        return SharedPref.setBooleanPref(Settings.FIRST_TIME_SPOOKD.key,bool);
    }

    public static boolean disableAds() {
        return SharedPref.getBooleanPref(Settings.DISABLE_ADS);
    }

    public static boolean hideSuggestedContent() {
        return SharedPref.getBooleanPref(Settings.HIDE_SUGGESTED_CONTENT);
    }

    public static boolean hideMetaAi() {
        return SharedPref.getBooleanPref(Settings.HIDE_META_AI);
    }

    public static boolean saveDeletedMessages() {
        return SharedPref.getBooleanPref(Settings.SAVE_DELETED_MESSAGES);
    }

    public static boolean dmMediaVault() {
        return SharedPref.getBooleanPref(Settings.DM_MEDIA_VAULT) && SettingsStatus.dmMediaVault && saveDeletedMessages();
    }

    public static boolean openLinksExternally() {
        return SharedPref.getBooleanPref(Settings.OPEN_LINKS_EXTERNALLY);
    }

    public static boolean sanitizeShareLinks() {
        return SharedPref.getBooleanPref(Settings.SANITIZE_SHARE_LINKS);
    }

    public static String customSharingDomain() {
        return removeLineBreaks(SharedPref.getStringPref(Settings.CUSTOM_SHARING_DOMAIN));
    }

    public static boolean detectLinksInText() {
        return SettingsStatus.detectLinksInText && SharedPref.getBooleanPref(Settings.DETECT_LINKS_IN_TEXT);
    }

    public static boolean getTurnOnAllGhostModes() {
        return SharedPref.getBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES);
    }

    public static boolean setTurnOnAllGhostModes(boolean bool) {
        return SharedPref.setBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES.key,bool);
    }

    public static boolean isMoreOptionsOnProfilePatched(){
        return SettingsStatus.moreOptionsOnProfile;
    }

    public static boolean viewStoriesAnonymously() {
        return (SharedPref.getBooleanPref(Settings.VIEW_STORIES_ANONYMOUSLY) && SettingsStatus.viewStoriesAnonymously) || Pref.getTurnOnAllGhostModes();
    }

    public static boolean viewLiveAnonymously() {
        return (SharedPref.getBooleanPref(Settings.VIEW_LIVE_ANONYMOUSLY) && SettingsStatus.viewLiveAnonymously) || Pref.getTurnOnAllGhostModes();
    }

    public static boolean disableScreenshotDetection() {
        return SharedPref.getBooleanPref(Settings.DISABLE_SCREENSHOT_DETECTION) || Pref.getTurnOnAllGhostModes();
    }

    public static boolean disableTypingStatus() {
        return SharedPref.getBooleanPref(Settings.DISABLE_TYPING_STATUS) || Pref.getTurnOnAllGhostModes();
    }

    public static boolean enableMarkChatAsReadOption() {
        return SharedPref.getBooleanPref(Settings.ENABLE_MARK_CHAT_AS_READ) && SettingsStatus.markChatAsRead;
    }

    public static boolean markAsReadAfterReply() {
        return SharedPref.getBooleanPref(Settings.MARK_AS_READ_AFTER_REPLY) && SettingsStatus.markAsReadAfterReply;
    }

    // Return false = call the message seen api.
    // Return true = blocks the message seen api.
    public static boolean viewDmAnonymously() {
        return shouldBlockDmSeen(
                SharedPref.getBooleanPref(Settings.VIEW_DM_ANONYMOUSLY),
                Pref.getTurnOnAllGhostModes(),
                enableMarkChatAsReadOption() || markAsReadAfterReply()
        );
    }

    static boolean shouldBlockDmSeen(
            boolean viewDmAnonymously,
            boolean allGhostModes,
            boolean explicitReadEnabled
    ) {
        if (explicitReadEnabled && MarkChatAsReadScope.isActive()) {
            return false;
        }
        return viewDmAnonymously || allGhostModes;
    }

    public static boolean resumeReelsAfterScrolling() {
        return SharedPref.getBooleanPref(Settings.RESUME_REELS_AFTER_SCROLLING);
    }

    public static boolean playbackSpeedControls() {
        return SharedPref.getBooleanPref(Settings.PLAYBACK_SPEED_CONTROLS);
    }

    public static boolean disableVideoAutoplay() {
        return SharedPref.getBooleanPref(Settings.DISABLE_VIDEO_AUTOPLAY);
    }

    public
    static boolean disableStories() {
        return SharedPref.getBooleanPref(Settings.DISABLE_STORIES);
    }

    public static boolean disableHighlights() {
        return SharedPref.getBooleanPref(Settings.DISABLE_HIGHLIGHTS);
    }

    public static boolean disableExplore() {
        return SharedPref.getBooleanPref(Settings.DISABLE_EXPLORE);
    }

    public static boolean disableComments() {
        return SharedPref.getBooleanPref(Settings.DISABLE_COMMENTS);
    }

    public static boolean hideCommentButtons() {
        return lockable(Settings.DISABLE_COMMENTS) && SettingsStatus.disableComments;
    }

    public static boolean limitFollowingFeed() {
        return SharedPref.getBooleanPref(Settings.LIMIT_FOLLOWING_FEED);
    }

    public static boolean hideFeedPosts() {
        return SharedPref.getBooleanPref(Settings.HIDE_FEED_POSTS) && SettingsStatus.hideFeedPosts;
    }

    public static boolean hideStoriesTray() {
        return lockable(Settings.HIDE_STORIES_TRAY) && SettingsStatus.hideStoriesTray;
    }

    public static boolean hideHiddenRequests() {
        return lockable(Settings.HIDE_HIDDEN_REQUESTS) && SettingsStatus.hideHiddenRequests;
    }

    public static boolean hideNotesTray() {
        return lockable(Settings.HIDE_NOTES_TRAY) && SettingsStatus.hideNotesTray;
    }

    /** The comment sheet's own "hide emoji bar" argument, also set when the switch is on. */
    public static boolean hideCommentEmojiBar(boolean hidden) {
        return hidden || (SharedPref.getBooleanPref(Settings.HIDE_COMMENT_EMOJI_BAR) && SettingsStatus.hideCommentEmojiBar);
    }

    public static boolean hideReelsVisualSearch() {
        return SharedPref.getBooleanPref(Settings.HIDE_REELS_VISUAL_SEARCH) && SettingsStatus.hideReelsVisualSearch;
    }

    public static boolean hideProfileNotesBubble() {
        return SharedPref.getBooleanPref(Settings.HIDE_PROFILE_NOTES_BUBBLE) && SettingsStatus.hideProfileNotesBubble;
    }

    public static boolean hideThreadsButton() {
        return SharedPref.getBooleanPref(Settings.HIDE_THREADS_BUTTON) && SettingsStatus.hideThreadsButton;
    }

    public static boolean hideFriendsMap() {
        return SharedPref.getBooleanPref(Settings.HIDE_FRIENDS_MAP) && SettingsStatus.hideFriendsMap;
    }

    private static final Cached<Boolean> HIDE_RECENT_SEARCHES =
            new Cached<>(() -> SharedPref.getBooleanPref(Settings.HIDE_RECENT_SEARCHES), Settings.HIDE_RECENT_SEARCHES.key);

    public static boolean hideRecentSearches() {
        return SettingsStatus.hideRecentSearches && HIDE_RECENT_SEARCHES.get();
    }

    public static boolean squareExploreGrid() {
        return SharedPref.getBooleanPref(Settings.SQUARE_EXPLORE_GRID) && SettingsStatus.squareExploreGrid;
    }

    private static final Cached<Boolean> HIDE_REELS_COMMENT_BAR =
            new Cached<>(() -> SharedPref.getBooleanPref(Settings.HIDE_REELS_COMMENT_BAR), Settings.HIDE_REELS_COMMENT_BAR.key);

    public static boolean hideReelsCommentBar() {
        return SettingsStatus.hideReelsCommentBar && HIDE_REELS_COMMENT_BAR.get();
    }

    public static boolean hideChatBlendButton() {
        return SharedPref.getBooleanPref(Settings.HIDE_CHAT_BLEND_BUTTON) && SettingsStatus.hideChatBlendButton;
    }

    private static final Cached<Boolean> HIDE_INBOX_INSTANTS =
            new Cached<>(() -> SharedPref.getBooleanPref(Settings.HIDE_INBOX_INSTANTS), Settings.HIDE_INBOX_INSTANTS.key);

    public static boolean hideInboxInstants() {
        return SettingsStatus.hideInboxInstants && HIDE_INBOX_INSTANTS.get();
    }

    public static boolean hideReelsTabHeader() {
        return SharedPref.getBooleanPref(Settings.HIDE_REELS_TAB_HEADER) && SettingsStatus.hideReelsTabHeader;
    }

    public static boolean disableReelsScrolling() {
        return lockable(Settings.DISABLE_REELS_SCROLLING) && SettingsStatus.disableReelsScrolling;
    }

    public static boolean disableSwipeToCreate() {
        return lockable(Settings.DISABLE_SWIPE_TO_CREATE) && SettingsStatus.disableSwipeToCreate;
    }

    /**
     * A switch that Focus Lock can hold on: the stored value, or true while the lock forces it.
     *
     * Read per call rather than cached, so a lock that expires releases the setting without
     * needing a restart.
     */
    private static boolean lockable(BooleanSetting setting) {
        return SharedPref.getBooleanPref(setting) || FocusLock.isForced(setting);
    }

    /** Whether the target behind {@code key} is one of the things Focus Lock holds on. */
    public static boolean focusLockSelected(String key) {
        return SharedPref.getBooleanPref(focusLockSelection(key));
    }

    private static final ConcurrentHashMap<String, BooleanSetting> FOCUS_LOCK_SELECTIONS = new ConcurrentHashMap<>();

    public static BooleanSetting focusLockSelection(String key) {
        BooleanSetting setting = FOCUS_LOCK_SELECTIONS.get(key);
        if (setting != null) return setting;
        return FOCUS_LOCK_SELECTIONS.computeIfAbsent(key,
                k -> new BooleanSetting(FocusLock.SELECTION_PREFIX + k, false, NOT_REQUIRED));
    }

    public static String focusLockDurationMinutes() {
        return SharedPref.getStringPref(Settings.FOCUS_LOCK_DURATION_MINUTES);
    }

    public static boolean setFocusLockDurationMinutes(String value) {
        return SharedPref.setStringPref(Settings.FOCUS_LOCK_DURATION_MINUTES.key, value);
    }

    /** Keys of the first released Focus Lock, read so a lock it wrote still counts. */
    public static boolean legacyFocusLockBlockReels() {
        return SharedPref.getBooleanPref(new BooleanSetting("focus_lock_block_reels", false, NOT_REQUIRED));
    }

    public static boolean legacyFocusLockBlockExplore() {
        return SharedPref.getBooleanPref(new BooleanSetting("focus_lock_block_explore", false, NOT_REQUIRED));
    }

    public static String focusLockFormat() {
        return SharedPref.getStringPref(Settings.FOCUS_LOCK_FORMAT);
    }

    public static boolean setFocusLockFormat(String value) {
        return SharedPref.setStringPref(Settings.FOCUS_LOCK_FORMAT.key, value);
    }

    public static String focusLockUntil() {
        return SharedPref.getStringPref(Settings.FOCUS_LOCK_UNTIL);
    }

    public static boolean setFocusLockUntil(String value) {
        return SharedPref.setStringPref(Settings.FOCUS_LOCK_UNTIL.key, value);
    }

    public static String focusLockUnlockRequestedAt() {
        return SharedPref.getStringPref(Settings.FOCUS_LOCK_UNLOCK_REQUESTED_AT);
    }

    public static boolean setFocusLockUnlockRequestedAt(String value) {
        return SharedPref.setStringPref(Settings.FOCUS_LOCK_UNLOCK_REQUESTED_AT.key, value);
    }

    public static boolean ephemeralMediaCaption() {
        return SharedPref.getBooleanPref(Settings.EPHEMERAL_MEDIA_CAPTION);
    }

    public static boolean disableDisappearingMessagesGesture() {
        return SharedPref.getBooleanPref(Settings.DISABLE_DISAPPEARING_MESSAGES_GESTURE) && SettingsStatus.disableDisappearingMessagesGesture;
    }

    public static boolean sendKeyboardGifs() {
        return SharedPref.getBooleanPref(Settings.SEND_KEYBOARD_GIFS) && SettingsStatus.sendKeyboardGifs;
    }

    public static boolean makeEphemeralMediaPermanent() {
        return SharedPref.getBooleanPref(Settings.UNLIMITED_REPLAYS) && SettingsStatus.unlimitedReplaysOnEphemeralMedia;
    }

    public static boolean hideLikes() {
        return SharedPref.getBooleanPref(Settings.HIDE_LIKES) && SettingsStatus.hideLikes;
    }

    public static boolean showLikeButton(boolean visible) {
        return visible && !hideLikes();
    }

    public static void hideLikeView(View view) {
        if (hideLikes()) view.setVisibility(View.GONE);
    }

    public static boolean hideShareButton() {
        return SharedPref.getBooleanPref(Settings.HIDE_SHARE_BUTTON) && SettingsStatus.hideShareButton;
    }

    private static final Cached<Set<String>> HIDE_SAVE_BUTTON_SURFACES = new Cached<>(
            Pref::loadHideSaveButtonSurfaces,
            Settings.HIDE_SAVE_BUTTON_SURFACES.key, Settings.HIDE_SAVE_BUTTONS_LEGACY.key);

    /** The surfaces the save button is hidden on; "posts" / "reels" (hide_save_button_surfaces). */
    public static Set<String> hideSaveButtonSurfaces() {
        return HIDE_SAVE_BUTTON_SURFACES.get();
    }

    private static Set<String> loadHideSaveButtonSurfaces() {
        Set<String> surfaces = SharedPref.getSetPref(Settings.HIDE_SAVE_BUTTON_SURFACES);
        Context context = Utils.getContext();
        var preferences = context == null ? null
                : context.getSharedPreferences(Constants.SHARED_PREF_NAME, Context.MODE_PRIVATE);
        if (preferences == null || preferences.contains(Settings.HIDE_SAVE_BUTTON_SURFACES.key)) return surfaces;
        if (SharedPref.getBooleanPref(Settings.HIDE_SAVE_BUTTONS_LEGACY)) {
            surfaces = new HashSet<>(surfaces);
            surfaces.add("posts");
            surfaces.add("reels");
            SharedPref.setSetPref(Settings.HIDE_SAVE_BUTTON_SURFACES.key, surfaces);
        }
        return surfaces;
    }

    public static boolean hideSaveButtons() {
        return hideSaveButtonSurfaces().contains("posts") && SettingsStatus.hideSaveButtons;
    }

    public static boolean hideSaveButtonReels() {
        return hideSaveButtonSurfaces().contains("reels") && SettingsStatus.hideSaveButtons;
    }

    public static boolean showSaveButton(boolean original) {
        return original && !hideSaveButtons();
    }

    // Reels need the native save state to build the download component even when save is hidden.
    public static boolean showReelSaveButton(boolean original) {
        return original && (!hideSaveButtonReels() || ReelDownload.isEnabled());
    }

    public static boolean showReelSaveIcon(boolean original) {
        return original && !hideSaveButtonReels();
    }

    public static boolean hideReshareButton() {
        return SharedPref.getBooleanPref(Settings.HIDE_RESHARE_BUTTON) && SettingsStatus.hideReshareButton;
    }

    public static boolean hideGroupCreationOnSharesheet() {
        return lockable(Settings.HIDE_GROUP_CREATION_BUTTON_ON_SHARESHEET);
    }

       public static boolean showReelsFollowButton(boolean original) {
        boolean hide = lockable(Settings.HIDE_REELS_FOLLOW_BUTTON) && SettingsStatus.hideReelsFollowButton;
        return original && !hide;
    }

    public static boolean enableDevOptions() {
        return SharedPref.getBooleanPref(Settings.DEVELOPER_OPTIONS);
    }
    public static boolean directlyOpenMetaConfig() {
        return SharedPref.getBooleanPref(Settings.DIRECTLY_OPEN_METACONFIG);
    }
    public static boolean disableDiscoverPeople() {
        return SharedPref.getBooleanPref(Settings.DISABLE_DISCOVER_PEOPLE);
    }

    public static boolean followBackIndicator() {
        return SharedPref.getBooleanPref(Settings.FOLLOW_BACK_INDICATOR);
    }
    public static boolean followBackColorIndicator() {
        return SharedPref.getBooleanPref(Settings.FOLLOW_BACK_COLOR_INDICATOR);
    }

    public static boolean followListNonFollowerBadge() {
        return SharedPref.getBooleanPref(Settings.FOLLOW_LIST_NON_FOLLOWER_BADGE);
    }

    public static boolean exactProfileCounts() {
        return SharedPref.getBooleanPref(Settings.EXACT_PROFILE_COUNTS) && SettingsStatus.exactProfileCounts;
    }

    public static boolean squareProfileGrid() {
        return SharedPref.getBooleanPref(Settings.SQUARE_PROFILE_GRID) && SettingsStatus.squareProfileGrid;
    }

    public static boolean profileSavedTab() {
        return SharedPref.getBooleanPref(Settings.PROFILE_SAVED_TAB) && SettingsStatus.profileSavedTab;
    }

    public static boolean confirmLike() {
        return SharedPref.getBooleanPref(Settings.CONFIRM_LIKE);
    }

    public static boolean confirmFollow() {
        return SharedPref.getBooleanPref(Settings.CONFIRM_FOLLOW);
    }

    public static boolean confirmRepost() {
        return SharedPref.getBooleanPref(Settings.CONFIRM_REPOST);
    }

    public static boolean confirmComment() {
        return SharedPref.getBooleanPref(Settings.CONFIRM_COMMENT);
    }

    public static boolean confirmUnfollow() {
        return SharedPref.getBooleanPref(Settings.CONFIRM_UNFOLLOW);
    }

    public static boolean confirmStoryLike() {
        return SharedPref.getBooleanPref(Settings.CONFIRM_STORY_LIKE);
    }

    public static boolean confirmCall() {
        return SharedPref.getBooleanPref(Settings.CONFIRM_CALL);
    }

    public static boolean confirmFollowRequest() {
        return SharedPref.getBooleanPref(Settings.CONFIRM_FOLLOW_REQUEST);
    }

    public static boolean disableStoryFlipping() {
        return SharedPref.getBooleanPref(Settings.DISABLE_STORY_FLIPPING);
    }

    public static boolean loopStory() {
        return SharedPref.getBooleanPref(Settings.LOOP_STORY);
    }

    public static int storyFollowButtonState(int original) {
        return SharedPref.getBooleanPref(Settings.HIDE_STORY_FOLLOW_BUTTON) ? 0 : original;
    }

    public static boolean viewStoryMentions() {
        return SharedPref.getBooleanPref(Settings.VIEW_STORY_MENTIONS);
    }

    public static String customiseStoryTimestamp() {
        return SharedPref.getStringPref(Settings.CUSTOMISE_STORY_TIMESTAMP);
    }

    // Takes the width Instagram picks an image variant for. Requests at least as wide as a
    // full-screen view get the widest variant; narrower ones, such as grid tiles, stay unchanged.
    public static int imageVariantTargetWidth(int original) {
        if (!SharedPref.getBooleanPref(Settings.IMPROVE_IMAGE_VIEWING)) return original;
        Activity activity = Utils.getActivity();
        Resources resources = activity != null ? activity.getResources() : Resources.getSystem();
        int fullScreenWidth = Math.min(resources.getDisplayMetrics().widthPixels, FULL_SCREEN_WIDTH_CAP);
        return original >= fullScreenWidth ? WIDEST_VARIANT_TARGET : original;
    }

    public static Integer userAgentScreenSize(Integer original) {
        return SharedPref.getBooleanPref(Settings.IMPROVE_IMAGE_VIEWING) ? USER_AGENT_SCREEN_SIZE : original;
    }

    public static boolean enableDownload() {
        return SharedPref.getBooleanPref(Settings.ENABLE_DOWNLOAD) && SettingsStatus.downloadMedia;
    }

    public static boolean enableDirectDownload() {
        return SharedPref.getBooleanPref(Settings.ENABLE_DIRECT_DOWNLOAD);
    }

    public static boolean downloadUsernameFolder() {
        return SharedPref.getBooleanPref(Settings.DOWNLOAD_USERNAME_FOLDER);
    }

    public static boolean embedDownloadMetadata() {
        return SharedPref.getBooleanPref(Settings.EMBED_DOWNLOAD_METADATA);
    }

    public static boolean preferDashFormat() {
        return SharedPref.getBooleanPref(Settings.PREFER_DASH_FORMAT);
    }

    public static boolean dashAudioAacLc() {
        return SharedPref.getBooleanPref(Settings.DASH_AUDIO_AAC_LC);
    }

    public static String downloadFileNameTemplate() {
        return removeLineBreaks(SharedPref.getStringPref(Settings.DOWNLOAD_FILE_NAME_TEMPLATE));
    }

    public static boolean saveDownloadsToPhoneStorage() {
        return SharedPref.getBooleanPref(Settings.SAVE_DOWNLOADS_TO_PHONE_STORAGE);
    }

    /** 0 when off or not a number. */
    public static int galleryMaxAgeDays() {
        try {
            return Math.max(0, Integer.parseInt(SharedPref.getStringPref(Settings.GALLERY_MAX_AGE_DAYS).trim()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** In bytes, 0 when off or not a number. */
    public static long galleryMaxBytes() {
        try {
            long gb = Long.parseLong(SharedPref.getStringPref(Settings.GALLERY_MAX_SIZE_GB).trim());
            return Math.max(0, Math.min(gb, 1_000_000L)) * 1_000_000_000L;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static boolean hideNavigationFeed() {
        return SharedPref.getBooleanPref(Settings.HIDE_NAVIGATION_FEED);
    }

    public static boolean getHideHomeCreateButton() {
        return !mainFeedActionBarButtons().contains(Constants.AB_CREATE);
    }

    public static boolean getHideHomeNotificationsButton() {
        return !mainFeedActionBarButtons().contains(Constants.AB_NOTIFICATIONS);
    }

    public static boolean hideNavigationReels() {
        return SharedPref.getBooleanPref(Settings.HIDE_NAVIGATION_REELS);
    }

    public static boolean hideNavigationDirect() {
        return SharedPref.getBooleanPref(Settings.HIDE_NAVIGATION_DIRECT);
    }

    public static boolean hideNavigationSearch() {
        return SharedPref.getBooleanPref(Settings.HIDE_NAVIGATION_SEARCH);
    }

    public static boolean hideNavigationCreate() {
        return SharedPref.getBooleanPref(Settings.HIDE_NAVIGATION_CREATE);
    }

    public static boolean hasLegacyNavigationSettings() {
        return SharedPref.hasKey(Settings.HIDE_NAVIGATION_FEED.key)
                || SharedPref.hasKey(Settings.HIDE_NAVIGATION_REELS.key)
                || SharedPref.hasKey(Settings.HIDE_NAVIGATION_DIRECT.key)
                || SharedPref.hasKey(Settings.HIDE_NAVIGATION_SEARCH.key)
                || SharedPref.hasKey(Settings.HIDE_NAVIGATION_CREATE.key);
    }

    public static String navigationTabs() {
        return SharedPref.getStringPref(Settings.NAVIGATION_TABS);
    }

    public static boolean setNavigationTabs(String value) {
        return SharedPref.setStringPref(Settings.NAVIGATION_TABS.key, value);
    }

    public static boolean floatingTabBar() {
        return SharedPref.getBooleanPref(Settings.FLOATING_TAB_BAR);
    }

    public static boolean floatingTabBarHideOnScroll() {
        return floatingTabBar() && SharedPref.getBooleanPref(Settings.FLOATING_TAB_BAR_HIDE_ON_SCROLL);
    }

    public static boolean floatingTabBarGlass() {
        return floatingTabBar() && SharedPref.getBooleanPref(Settings.FLOATING_TAB_BAR_GLASS);
    }

    public static boolean removeEmptyBottomSpace() {
        return SharedPref.getBooleanPref(Settings.REMOVE_EMPTY_BOTTOM_SPACE);
    }

    public static boolean commentCopyButton() {
        return SharedPref.getBooleanPref(Settings.COMMENT_COPY_BUTTON) && SettingsStatus.copyCommentButton;
    }

    public static boolean commentSaveMediaButton() {
        return SharedPref.getBooleanPref(Settings.COMMENT_SAVE_MEDIA_BUTTON) && SettingsStatus.saveMediaCommentButton;
    }

    public static String changeLikeAnimation() {
        return SharedPref.getStringPref(Settings.CHANGE_LIKE_ANIMATION);
    }

    private static final Cached<Float> STORY_RING_SIZE =
            new Cached<>(Pref::loadStoryRingSize, Settings.CUSTOMISE_STORY_RING_SIZE.key);

    public static float customiseStoryRingSize() {
        return STORY_RING_SIZE.get();
    }

    private static float loadStoryRingSize() {
        try {
            return Float.parseFloat(SharedPref.getStringPref(Settings.CUSTOMISE_STORY_RING_SIZE));
        } catch (Exception ex) {
            return 100.0f;
        }
    }

    public static boolean disableDoubleTapPost() {
        return lockable(Settings.DISABLE_DOUBLE_TAP_LIKE_POST);
    }
    public static boolean disableDoubleTapReel() {
        return lockable(Settings.DISABLE_DOUBLE_TAP_LIKE_REEL);
    }
    public static boolean disableDoubleTapComment() {
        return lockable(Settings.DISABLE_DOUBLE_TAP_LIKE_COMMENT);
    }
    public static boolean disableDoubleTapMessage() {
        return lockable(Settings.DISABLE_DOUBLE_TAP_LIKE_MESSAGE);
    }
    public static boolean moreOptionsOnPost() {
        return SharedPref.getBooleanPref(Settings.ENABLE_MORE_OPTIONS_ON_POST) && SettingsStatus.moreOptionsOnPost;
    }
    public static boolean downloadWithExternalDownloader() {
        return SharedPref.getBooleanPref(Settings.DOWNLOAD_WITH_EXTERNAL_DOWNLOADER) && SettingsStatus.downloadWithExternalDownloader;
    }

    public static String externalDownloaderPackageName() {
        return removeLineBreaks(SharedPref.getStringPref(Settings.EXTERNAL_DOWNLOADER_PACKAGE_NAME));
    }

    private static final String ACTION_BAR_MIGRATED_SUFFIX = "_visibility_migrated";
    private static final Cached<Set<String>> MAIN_FEED_ACTION_BAR_BUTTONS = new Cached<>(
            () -> loadAndMigrateActionBarButtons(Settings.ACTION_BAR_MAIN_FEED, true),
            Settings.ACTION_BAR_MAIN_FEED.key, Settings.ACTION_BAR_MAIN_FEED.key + ACTION_BAR_MIGRATED_SUFFIX);
    private static final Cached<Set<String>> USER_PROFILE_ACTION_BAR_BUTTONS = new Cached<>(
            () -> loadAndMigrateActionBarButtons(Settings.ACTION_BAR_USER_PROFILE, false),
            Settings.ACTION_BAR_USER_PROFILE.key, Settings.ACTION_BAR_USER_PROFILE.key + ACTION_BAR_MIGRATED_SUFFIX);

    public static Set<String> mainFeedActionBarButtons() {
        return MAIN_FEED_ACTION_BAR_BUTTONS.get();
    }

    public static Set<String> userProfileActionBarButtons() {
        return USER_PROFILE_ACTION_BAR_BUTTONS.get();
    }

    private static Set<String> loadAndMigrateActionBarButtons(StringSetting setting, boolean home) {
        Set<String> buttons = SharedPref.getSetPref(setting);
        Context context = Utils.getContext();
        var preferences = context == null ? null
                : context.getSharedPreferences(Constants.SHARED_PREF_NAME, Context.MODE_PRIVATE);
        String migratedKey = setting.key + ACTION_BAR_MIGRATED_SUFFIX;
        if (preferences != null && preferences.getBoolean(migratedKey, false)) return buttons;

        String createKey = Settings.HIDE_HOME_CREATE_BUTTON.key;
        String notificationsKey = Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.key;
        buttons = new HashSet<>(buttons);
        boolean hideCreate = buttons.remove("HIDE_CREATE");
        if (home && preferences != null) hideCreate |= preferences.getBoolean(createKey, false);
        if (!hideCreate) buttons.add(Constants.AB_CREATE);
        if (home) {
            boolean hideNotifications = buttons.remove("HIDE_NOTIFICATIONS");
            if (preferences != null) hideNotifications |= preferences.getBoolean(notificationsKey, false);
            if (!hideNotifications) buttons.add(Constants.AB_NOTIFICATIONS);
        }
        if (preferences != null) {
            var editor = preferences.edit().putStringSet(setting.key, buttons).putBoolean(migratedKey, true);
            if (home) editor.remove(createKey).remove(notificationsKey);
            editor.apply();
        }
        return buttons;
    }

    public static Set<String> chatActionBarButtons() {
        return SharedPref.getSetPref(Settings.ACTION_BAR_CHAT);
    }

    private static final String CHAT_CALL_ICONS_INITIALIZED_KEY = "action_bar_chat_call_icons_initialized";
    private static final Cached<Set<String>> CHAT_ACTIONS_WITH_CALL_DEFAULTS = new Cached<>(
            Pref::loadChatActionsWithCallDefaults,
            Settings.ACTION_BAR_CHAT.key, CHAT_CALL_ICONS_INITIALIZED_KEY);

    public static Set<String> chatActionsWithCallDefaults() {
        return CHAT_ACTIONS_WITH_CALL_DEFAULTS.get();
    }

    private static synchronized Set<String> loadChatActionsWithCallDefaults() {
        Set<String> actions = chatActionBarButtons();
        Context context = Utils.getContext();
        var preferences = context == null ? null
                : context.getSharedPreferences(Constants.SHARED_PREF_NAME, Context.MODE_PRIVATE);
        String initializedKey = CHAT_CALL_ICONS_INITIALIZED_KEY;
        if (preferences != null && preferences.getBoolean(initializedKey, false)) return actions;

        // Without this marker, an empty call selection could mean either first use or both icons hidden.
        actions = new HashSet<>(actions);
        actions.add(Constants.AB_AUDIO_CALL_ICON);
        actions.add(Constants.AB_VIDEO_CALL_ICON);
        if (preferences != null) {
            preferences.edit().putStringSet(Settings.ACTION_BAR_CHAT.key, actions)
                    .putBoolean(initializedKey, true).apply();
        }
        return actions;
    }

    public static boolean showChatAudioCallButton(boolean available) {
        return available && chatActionsWithCallDefaults().contains(Constants.AB_AUDIO_CALL_ICON);
    }

    public static boolean showChatVideoCallButton(boolean available) {
        return available && chatActionsWithCallDefaults().contains(Constants.AB_VIDEO_CALL_ICON);
    }

    public static Set<String> inboxActionBarButtons() {
        return SharedPref.getSetPref(Settings.ACTION_BAR_INBOX);
    }

    public static Set<String> filterStoryByType() {
        return SharedPref.getSetPref(Settings.FILTER_STORY_BY_TYPE);
    }

    public static Set<String> filterStoryByUserType() {
        return SharedPref.getSetPref(Settings.FILTER_STORY_BY_USER_TYPE);
    }

    public static Integer filterStoryByMinStoryItems() {
        try {
            return Integer.valueOf(SharedPref.getStringPref(Settings.FILTER_STORY_MIN_STORY_ITEMS));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public static Integer filterStoryByMaxStoryItems() {
        try {
            return Integer.valueOf(SharedPref.getStringPref(Settings.FILTER_STORY_MAX_STORY_ITEMS));
        } catch (NumberFormatException e) {
            return 9999;
        }
    }

    public static boolean inboxLock() {
        return SharedPref.getBooleanPref(Settings.INBOX_LOCK);
    }

    public static boolean inboxLockNotifications() {
        return SharedPref.getBooleanPref(Settings.INBOX_LOCK_NOTIFICATIONS);
    }

    public static boolean hiddenChats() {
        return SharedPref.getBooleanPref(Settings.HIDDEN_CHATS);
    }

    public static boolean hiddenChatsMute() {
        return SharedPref.getBooleanPref(Settings.HIDDEN_CHATS_MUTE);
    }

    public static int hiddenChatsTaps() {
        try {
            return Integer.parseInt(SharedPref.getStringPref(Settings.HIDDEN_CHATS_TAPS));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** Drops every cached setting; for writes off the main thread, whose change callbacks arrive later. */
    public static void invalidateCaches() {
        Cached.invalidateAll();
        FOCUS_LOCK_SELECTIONS.clear();
    }

    /**
     * A value computed from piko_settings and kept until one of {@code keys} changes, so hooks that
     * run on every render skip the reads. Until the change listener is registered it reads each time.
     */
    public static final class Cached<T> {
        private static final List<Cached<?>> ALL = new CopyOnWriteArrayList<>();
        private final Supplier<T> loader;
        private final Predicate<SharedPreferences.OnSharedPreferenceChangeListener> registrar;
        private final String[] keys;
        // SharedPreferences holds listeners weakly, so the cache keeps its own alive.
        private final SharedPreferences.OnSharedPreferenceChangeListener listener =
                (preferences, key) -> onChanged(key);
        private volatile T value;
        private boolean listening;

        public Cached(Supplier<T> loader, String... keys) {
            this(loader, Cached::listenToSettings, keys);
        }

        Cached(Supplier<T> loader,
               Predicate<SharedPreferences.OnSharedPreferenceChangeListener> registrar,
               String... keys) {
            this.loader = loader;
            this.registrar = registrar;
            this.keys = keys;
            ALL.add(this);
        }

        static void invalidateAll() {
            for (Cached<?> cached : ALL) {
                cached.onChanged(null);
            }
        }

        public T get() {
            T current = value;
            if (current != null) return current;
            synchronized (this) {
                if (value != null) return value;
                // Listening before the read, so a change made during it still clears the result.
                if (!listening) listening = register();
                T loaded = loader.get();
                if (listening) value = loaded;
                return loaded;
            }
        }

        synchronized void onChanged(String key) {
            // A null key means the whole file was cleared.
            if (key == null) {
                value = null;
                return;
            }
            for (String watched : keys) {
                if (watched.equals(key)) {
                    value = null;
                    return;
                }
            }
        }

        private boolean register() {
            try {
                return registrar.test(listener);
            } catch (RuntimeException e) {
                Logger.printException(() -> "Failed to listen for settings changes", e);
                return false;
            }
        }

        private static boolean listenToSettings(SharedPreferences.OnSharedPreferenceChangeListener listener) {
            return SharedPref.registerOnSharedPreferenceChangeListener(listener);
        }
    }
}
