/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/

package app.morphe.extension.instagram.settings;

import java.util.TreeMap;

import app.morphe.extension.instagram.patches.location.FakePlaces;
import static app.morphe.extension.instagram.utils.IgStr.str;

public class SettingsStatus {
    public static TreeMap<String,Boolean> FLAGS = new TreeMap();

    public static boolean theme = false;
    public static void theme() { theme = true; }
    public static boolean addSettings = false;
    public static void addSettings() { addSettings = true; }
    public static boolean homeLongPressSettings = false;
    public static void homeLongPressSettings() { homeLongPressSettings = true; }

    //Ads section.
    public static boolean disableAds = false;
    public static void disableAds() {
        disableAds = true;
    }

    public static boolean hideSuggestedContent = false;
    public static void hideSuggestedContent() {
        hideSuggestedContent = true;
    }
    public static boolean hideMetaAi = false;
    public static void hideMetaAi() { hideMetaAi = true; }
    public static boolean adsSection() {
        return (disableAds || hideSuggestedContent || hideMetaAi);
    }

    //Filter content
    public static boolean storyFilters = false;
    public static void storyFilters(){storyFilters = true;}
    public static boolean filterContentSection(){return storyFilters; }

    //Ghost section.
    public static boolean viewStoriesAnonymously = false;
    public static void viewStoriesAnonymously() {
        viewStoriesAnonymously = true;
    }
    public static boolean markStorySeenOnInteraction = false;
    public static void markStorySeenOnInteraction() {
        markStorySeenOnInteraction = true;
    }
    public static boolean markEarlierStoriesSeen = false;
    public static void markEarlierStoriesSeen() { markEarlierStoriesSeen = true; }
    public static boolean advanceStory = false;
    public static void advanceStory() { advanceStory = true; }
    public static boolean viewLiveAnonymously = false;
    public static void viewLiveAnonymously() {
        viewLiveAnonymously = true;
    }
    public static boolean disableScreenshotDetection = false;
    public static void disableScreenshotDetection() {
        disableScreenshotDetection = true;
    }
    public static boolean disableTypingStatus = false;
    public static void disableTypingStatus() {
        disableTypingStatus = true;
    }
    public static boolean viewDmAnonymously = false;
    public static void viewDmAnonymously() {
        viewDmAnonymously = true;
    }
    public static boolean markAsReadAfterReply = false;
    public static void markAsReadAfterReply() {
        markAsReadAfterReply = true;
    }
    public static boolean dmGhostChats = false;
    public static void dmGhostChats() { dmGhostChats = true; }
    public static boolean saveDeletedMessages = false;
    public static void saveDeletedMessages() {
        saveDeletedMessages = true;
    }
    public static boolean dmMediaVault = false;
    public static void dmMediaVault() { dmMediaVault = true; }
    public static boolean fakeLocation = false;
    public static void fakeLocation() {
        fakeLocation = true;
        FakePlaces.preload();
    }
    public static boolean hiddenAccountPrivacyOptions = false;
    public static void hiddenAccountPrivacyOptions() { hiddenAccountPrivacyOptions = true; }
    public static boolean ghostSection() {
        return (viewStoriesAnonymously || viewLiveAnonymously || disableScreenshotDetection || disableTypingStatus || viewDmAnonymously || markAsReadAfterReply || fakeLocation || hiddenAccountPrivacyOptions);
    }

    //DM section
    public static boolean disableDisappearingMessagesGesture = false;
    public static void disableDisappearingMessagesGesture() { disableDisappearingMessagesGesture = true; }
    public static boolean unlimitedReplaysOnEphemeralMedia = false;
    public static void unlimitedReplaysOnEphemeralMedia() {unlimitedReplaysOnEphemeralMedia = true;}
    public static boolean markVisualMessagesSeen = false;
    public static void markVisualMessagesSeen() { markVisualMessagesSeen = true; }
    public static boolean markChatAsRead = false;
    public static void markChatAsRead() { markChatAsRead = true; }
    public static boolean inboxLock = false;
    public static void inboxLock() { inboxLock = true; }
    public static boolean hiddenChats = false;
    public static void hiddenChats() { hiddenChats = true; }
    public static boolean pinRecipients = false;
    public static void pinRecipients() { pinRecipients = true; }
    public static boolean sendKeyboardGifs = false;
    public static void sendKeyboardGifs() { sendKeyboardGifs = true; }
    public static boolean stopVisualMessageAutoAdvance = false;
    public static void stopVisualMessageAutoAdvance() { stopVisualMessageAutoAdvance = true; }
    public static boolean exactLastActive = false;
    public static void exactLastActive() { exactLastActive = true; }
    public static boolean activityAlerts = false;
    public static void activityAlerts() { activityAlerts = true; }
    public static boolean dmSection(){ return markChatAsRead || unlimitedReplaysOnEphemeralMedia || saveDeletedMessages || dmMediaVault || inboxLock || hiddenChats || pinRecipients || disableDisappearingMessagesGesture || sendKeyboardGifs || stopVisualMessageAutoAdvance || exactLastActive || activityAlerts ;}

    //Story section
    public static boolean customiseStoryTimestamp = false;
    public static void customiseStoryTimestamp() {
        customiseStoryTimestamp = true;
    }
    public static boolean viewStoryMentions = false;
    public static void viewStoryMentions() {
        viewStoryMentions = true;
    }
    public static boolean disableStoryFlipping = false;
    public static void disableStoryFlipping() {
        disableStoryFlipping = true;
    }
    public static boolean loopStory = false;
    public static void loopStory() {
        loopStory = true;
    }
    public static boolean customiseStoryRingSize = false;
    public static void customiseStoryRingSize() { customiseStoryRingSize = true; }
    public static boolean hideStoryFollowButton = false;
    public static void hideStoryFollowButton() {
        hideStoryFollowButton = true;
    }
    public static boolean storyAutoSave = false;
    public static void storyAutoSave() { storyAutoSave = true; }
    public static boolean storyPlayback = false;
    public static void storyPlayback() { storyPlayback = true; }
    public static boolean storyMute = false;
    public static void storyMute() { storyMute = true; }
    public static boolean searchStoryViewers = false;
    public static void searchStoryViewers() { searchStoryViewers = true; }
    public static boolean storySection() {
        return (customiseStoryTimestamp || viewStoryMentions || disableStoryFlipping || loopStory || customiseStoryRingSize || hideStoryFollowButton || storyAutoSave || storyPlayback || storyMute || searchStoryViewers);
    }

    //Links section.
    public static boolean openLinksExternally = false;
    public static void openLinksExternally() {
        openLinksExternally = true;
    }
    public static boolean sanitizeShareLinks = false;
    public static void sanitizeShareLinks() {sanitizeShareLinks = true;}
    public static boolean customSharingDomain = false;
    public static void customSharingDomain() {customSharingDomain = true;}
    public static boolean detectLinksInText = false;
    public static void detectLinksInText() { detectLinksInText = true; }
    public static boolean holdSendCopyLink = false;
    public static void holdSendCopyLink() { holdSendCopyLink = true; }
    public static boolean linksSection() {
        return (openLinksExternally || sanitizeShareLinks || customSharingDomain || detectLinksInText || holdSendCopyLink);
    }

    //Distraction-Free section.
    public static boolean disableStories = false;
    public static void disableStories() {
        disableStories = true;
    }
    public static boolean disableHighlights = false;
    public static void disableHighlights() {
        disableHighlights = true;
    }
    public static boolean disableExplore = false;
    public static void disableExplore() {
        disableExplore = true;
    }
    public static boolean disableComments = false;
    public static void disableComments() {
        disableComments = true;
    }
    public static boolean hideStoriesTray = false;
    public static void hideStoriesTray() {
        hideStoriesTray = true;
    }
    public static boolean hideHiddenRequests = false;
    public static void hideHiddenRequests() {
        hideHiddenRequests = true;
    }
    public static boolean hideNotesTray = false;
    public static void hideNotesTray() {
        hideNotesTray = true;
    }
    public static boolean limitFollowingFeed = false;
    public static void limitFollowingFeed() {
        limitFollowingFeed = true;
    }
    public static boolean hideFeedPosts = false;
    public static void hideFeedPosts() {
        hideFeedPosts = true;
    }
    public static boolean hideGroupCreationOnSharesheet = false;
    public static void hideGroupCreationOnSharesheet() {
        hideGroupCreationOnSharesheet = true;
    }
    public static boolean disableReelsScrolling = false;
    public static void disableReelsScrolling() {
        disableReelsScrolling = true;
    }
    public static boolean disableSwipeToCreate = false;
    public static void disableSwipeToCreate() {
        disableSwipeToCreate = true;
    }
    public static boolean focusLock = false;
    public static void focusLock() {
        focusLock = true;
    }
    public static boolean disableVideoAutoplay = false;
    public static void disableVideoAutoplay() { disableVideoAutoplay = true;
    }
    public static boolean disableDoubleTapLike = false;
    public static void disableDoubleTapLike() {
        disableDoubleTapLike = true;
    }
    public static boolean hideReelsFollowButton = false;
    public static void hideReelsFollowButton() {
        hideReelsFollowButton = true;
    }
    public static boolean disableDiscoverPeople = false;
    public static void disableDiscoverPeople() {
        disableDiscoverPeople = true;
    }
    public static boolean hideLikes = false;
    public static void hideLikes() {
        hideLikes = true;
    }
    public static boolean hideShareButton = false;
    public static void hideShareButton() {
        hideShareButton = true;
    }
    public static boolean hideSaveButtons = false;
    public static void hideSaveButtons() {
        hideSaveButtons = true;
    }
    public static boolean hideReshareButton = false;
    public static void hideReshareButton() {
        hideReshareButton = true;
    }
    public static boolean hideCommentEmojiBar = false;
    public static void hideCommentEmojiBar() { hideCommentEmojiBar = true; }
    public static boolean hideReelsVisualSearch = false;
    public static void hideReelsVisualSearch() { hideReelsVisualSearch = true; }
    public static boolean hideProfileNotesBubble = false;
    public static void hideProfileNotesBubble() { hideProfileNotesBubble = true; }
    public static boolean hideThreadsButton = false;
    public static void hideThreadsButton() { hideThreadsButton = true; }
    public static boolean hideFriendsMap = false;
    public static void hideFriendsMap() { hideFriendsMap = true; }
    public static boolean hideRecentSearches = false;
    public static void hideRecentSearches() { hideRecentSearches = true; }
    public static boolean squareExploreGrid = false;
    public static void squareExploreGrid() { squareExploreGrid = true; }
    public static boolean hideReelsCommentBar = false;
    public static void hideReelsCommentBar() { hideReelsCommentBar = true; }
    public static boolean hideChatBlendButton = false;
    public static void hideChatBlendButton() { hideChatBlendButton = true; }
    public static boolean hideInboxInstants = false;
    public static void hideInboxInstants() { hideInboxInstants = true; }
    public static boolean hideReelsTabHeader = false;
    public static void hideReelsTabHeader() { hideReelsTabHeader = true; }
    public static boolean distractionFreeSection() {
        return (hideReelsFollowButton || disableDoubleTapLike || hideHiddenRequests || hideNotesTray || disableHighlights || disableStories || disableExplore || disableComments || hideStoriesTray || limitFollowingFeed || hideFeedPosts || disableVideoAutoplay || disableDiscoverPeople || hideGroupCreationOnSharesheet || hideReshareButton || hideSaveButtons || hideLikes || hideShareButton || disableReelsScrolling || disableSwipeToCreate || focusLock
                || hideCommentEmojiBar || hideReelsVisualSearch || hideProfileNotesBubble || hideThreadsButton || hideFriendsMap
                || hideRecentSearches || squareExploreGrid || hideReelsCommentBar || hideChatBlendButton || hideInboxInstants || hideReelsTabHeader);
    }

    //Misc section.
    public static boolean changeLikeAnimation = false;
    public static void changeLikeAnimation() { changeLikeAnimation = true; }
    public static boolean followBackIndicator = false;
    public static void followBackIndicator() { followBackIndicator = true; }
    public static boolean followListNonFollowerBadge = false;
    public static void followListNonFollowerBadge() { followListNonFollowerBadge = true; }
    public static boolean exactProfileCounts = false;
    public static void exactProfileCounts() { exactProfileCounts = true; }
    public static boolean profileAnalyzer = false;
    public static void profileAnalyzer() { profileAnalyzer = true; }
    public static boolean squareProfileGrid = false;
    public static void squareProfileGrid() { squareProfileGrid = true; }
    public static boolean profileSavedTab = false;
    public static void profileSavedTab() { profileSavedTab = true; }
    public static boolean confirmFollow = false;
    public static void confirmFollow() { confirmFollow = true; }
    public static boolean confirmFeedLike = false;
    public static void confirmFeedLike() { confirmFeedLike = true; }
    public static boolean confirmReelLike = false;
    public static void confirmReelLike() { confirmReelLike = true; }
    public static boolean confirmReelRepost = false;
    public static void confirmReelRepost() { confirmReelRepost = true; }
    public static boolean confirmComment = false;
    public static void confirmComment() { confirmComment = true; }
    public static boolean confirmCommentSheet = false;
    public static void confirmCommentSheet() { confirmCommentSheet = true; }
    public static boolean confirmUnfollow = false;
    public static void confirmUnfollow() { confirmUnfollow = true; }
    public static boolean confirmFeedRepost = false;
    public static void confirmFeedRepost() { confirmFeedRepost = true; }
    public static boolean confirmStoryLike = false;
    public static void confirmStoryLike() { confirmStoryLike = true; }
    public static boolean confirmStoryReaction = false;
    public static void confirmStoryReaction() { confirmStoryReaction = true; }
    public static boolean confirmCall = false;
    public static void confirmCall() { confirmCall = true; }
    public static boolean confirmFollowRequest = false;
    public static void confirmFollowRequest() { confirmFollowRequest = true; }
    public static boolean confirmRefresh = false;
    public static void confirmRefresh() { confirmRefresh = true; }
    public static boolean confirmCommentLike = false;
    public static void confirmCommentLike() { confirmCommentLike = true; }
    public static boolean confirmDisappearingMessages = false;
    public static void confirmDisappearingMessages() { confirmDisappearingMessages = true; }
    public static boolean refreshControls = false;
    public static void refreshControls() { refreshControls = true; }
    public static boolean confirmationsSection() {
        return confirmFollow || confirmFeedLike || confirmReelLike || confirmReelRepost || confirmComment || confirmCommentSheet
                || confirmUnfollow || confirmFeedRepost || confirmStoryLike || confirmStoryReaction || confirmCall || confirmFollowRequest
                || confirmRefresh || confirmCommentLike || confirmDisappearingMessages;
    }
    public static boolean resumeReelsAfterScrolling = false;
    public static void resumeReelsAfterScrolling() { resumeReelsAfterScrolling = true; }
    public static boolean playbackSpeedControls = false;
    public static void playbackSpeedControls() { playbackSpeedControls = true; }
    public static boolean defaultPlaybackSpeed = false;
    public static void defaultPlaybackSpeed() { defaultPlaybackSpeed = true; }
    public static boolean reelsPlayerOptions = false;
    public static void reelsPlayerOptions() { reelsPlayerOptions = true; }
    public static boolean reelsAutoScroll = false;
    public static void reelsAutoScroll() { reelsAutoScroll = true; }
    public static boolean pipForVideos = false;
    public static void pipForVideos() { pipForVideos = true; }
    public static boolean improveImageViewing = false;
    public static void improveImageViewing() {
        improveImageViewing = true;
    }
    public static boolean copyCommentButton = false;
    public static void copyCommentButton() {
        copyCommentButton = true;
    }
    public static boolean removeEmptyBottomSpace = false;
    public static void removeEmptyBottomSpace() {
        removeEmptyBottomSpace = true;
    }
    public static boolean moreOptionsOnPost = false;
    public static void moreOptionsOnPost() { moreOptionsOnPost = true; }
    public static boolean moreOptionsOnProfile = false;
    public static void moreOptionsOnProfile() { moreOptionsOnProfile = true; }
    public static boolean disableAppHaptics = false;
    public static void disableAppHaptics() { disableAppHaptics = true; }
    public static boolean miscSection() {return ( disableAppHaptics || moreOptionsOnProfile || moreOptionsOnPost || changeLikeAnimation || removeEmptyBottomSpace || copyCommentButton || improveImageViewing || resumeReelsAfterScrolling || playbackSpeedControls || defaultPlaybackSpeed || reelsPlayerOptions || reelsAutoScroll || pipForVideos || followBackIndicator || followListNonFollowerBadge || profileAnalyzer || exactProfileCounts || squareProfileGrid || profileSavedTab || confirmationsSection() || refreshControls || customFont);}

    //Font section.
    public static boolean customFont = false;
    public static void customFont() { customFont = true; }
    public static boolean fontSection(){ return customFont; }

    //Download section.
    public static boolean downloadMedia = false;
    public static void downloadMedia() {downloadMedia = true;}
     public static boolean saveMediaCommentButton = false;
    public static void saveMediaCommentButton() {
        saveMediaCommentButton = true;
    }
    public static boolean feedDownloadButton = false;
    public static void feedDownloadButton() {feedDownloadButton = true;}
    public static boolean reelDownloadButton = false;
    public static void reelDownloadButton() {reelDownloadButton = true;}
    public static boolean storyDownloadButton = false;
    public static void storyDownloadButton() {storyDownloadButton = true;}
    public static boolean audioPageDownloadButton = false;
    public static void audioPageDownloadButton() {audioPageDownloadButton = true;}
    public static boolean downloadVoiceMessage = false;
    public static void downloadVoiceMessage() { downloadVoiceMessage = true; }
    public static boolean downloadWithExternalDownloader = false;
    public static void downloadWithExternalDownloader() { downloadWithExternalDownloader = true; }
    public static boolean downloadSection(){return (SettingsStatus.downloadMedia || saveMediaCommentButton || SettingsStatus.downloadWithExternalDownloader);}

    //Instants Section
    public static boolean instantsDownload = false;
    public static void instantsDownload() {
        instantsDownload = true;
    }
    public static boolean instantsSeen = false;
    public static void instantsSeen() { instantsSeen = true; }
    public static boolean disableInstantsCreation = false;
    public static void disableInstantsCreation() { disableInstantsCreation = true; }
    public static boolean confirmInstantVideo = false;
    public static void confirmInstantVideo() { confirmInstantVideo = true; }
    public static boolean confirmInstantReaction = false;
    public static void confirmInstantReaction() { confirmInstantReaction = true; }
    public static boolean instantsSection() {
        return instantsDownload || instantsSeen || disableInstantsCreation || confirmInstantVideo || confirmInstantReaction;
    }

    //Navigation Section
    public static boolean hideNavigationButtons = false;
    public static void hideNavigationButtons() { hideNavigationButtons = true; }
    public static boolean floatingTabBar = false;
    public static void floatingTabBar() { floatingTabBar = true; }
    public static boolean swipeBetweenTabs = false;
    public static void swipeBetweenTabs() { swipeBetweenTabs = true; }

    // Developer section.
    public static boolean enableDeveloperOptions = false;
    public static void enableDeveloperOptions() {
        enableDeveloperOptions = true;
    }
    public static boolean recommendedFlags = false;
    public static void recommendedFlags() {recommendedFlags = true;}
    public static boolean developerOptionsSection() {
        return (enableDeveloperOptions || recommendedFlags);
    }



        public static void loadStatusMap(){
        FLAGS.put(str("spookd_patch_theme"), SettingsStatus.theme);
        FLAGS.put(str("spookd_patch_settings"), SettingsStatus.addSettings);
        FLAGS.put(str("spookd_patch_home_long_press_settings"), SettingsStatus.homeLongPressSettings);
        FLAGS.put(str("spookd_disable_reels_scrolling"), SettingsStatus.disableReelsScrolling);
        FLAGS.put(str("spookd_disable_ads"),SettingsStatus.disableAds);
        FLAGS.put(str("spookd_hide_suggested_content"),SettingsStatus.hideSuggestedContent);
        FLAGS.put(str("spookd_hide_meta_ai"), SettingsStatus.hideMetaAi);

        FLAGS.put(str("spookd_category_hide_navigation_buttons"),SettingsStatus.hideNavigationButtons);
        FLAGS.put(str("spookd_floating_tab_bar"), SettingsStatus.floatingTabBar);
        FLAGS.put(str("spookd_swipe_between_tabs"), SettingsStatus.swipeBetweenTabs);

        FLAGS.put(str("spookd_category_downloads"),SettingsStatus.downloadMedia);
        FLAGS.put(str("spookd_download_voice_media"),SettingsStatus.downloadVoiceMessage);
        FLAGS.put(str("spookd_download_with_external_downloader"),SettingsStatus.downloadWithExternalDownloader);
        FLAGS.put(str("spookd_more_profile_options"),SettingsStatus.moreOptionsOnProfile);
        FLAGS.put(str("spookd_enable_more_options_on_post"),SettingsStatus.moreOptionsOnPost);
        FLAGS.put(str("spookd_disable_video_autoplay"),SettingsStatus.disableVideoAutoplay);
        FLAGS.put(str("spookd_remove_empty_bottom_space"),SettingsStatus.removeEmptyBottomSpace);
        FLAGS.put(str("spookd_save_media_comment"),SettingsStatus.saveMediaCommentButton);
        FLAGS.put(str("spookd_copy_comment"),SettingsStatus.copyCommentButton);
        FLAGS.put(str("spookd_hide_reshare_button"),SettingsStatus.hideReshareButton);
        FLAGS.put(str("spookd_hide_save_buttons"), SettingsStatus.hideSaveButtons);
        FLAGS.put(str("spookd_hide_likes"), SettingsStatus.hideLikes);
        FLAGS.put(str("spookd_hide_share_button"), SettingsStatus.hideShareButton);
        FLAGS.put(str("spookd_improve_image_viewing"),SettingsStatus.improveImageViewing);
        FLAGS.put(str("spookd_resume_reels_after_scrolling"), SettingsStatus.resumeReelsAfterScrolling);
        FLAGS.put(str("spookd_playback_speed_controls"), SettingsStatus.playbackSpeedControls);
        FLAGS.put(str("spookd_default_playback_speed"), SettingsStatus.defaultPlaybackSpeed);
        FLAGS.put(str("spookd_reels_player_options"), SettingsStatus.reelsPlayerOptions);
        FLAGS.put(str("spookd_reels_auto_scroll"), SettingsStatus.reelsAutoScroll);
        FLAGS.put(str("spookd_pip_for_videos"), SettingsStatus.pipForVideos);
        FLAGS.put(str("spookd_unlimited_replays"),SettingsStatus.unlimitedReplaysOnEphemeralMedia);
        FLAGS.put(str("spookd_disable_disappearing_messages_gesture"), SettingsStatus.disableDisappearingMessagesGesture);
        FLAGS.put(str("spookd_send_keyboard_gifs"), SettingsStatus.sendKeyboardGifs);
        FLAGS.put(str("spookd_stop_visual_message_auto_advance"), SettingsStatus.stopVisualMessageAutoAdvance);
        FLAGS.put(str("spookd_mark_visual_messages_seen_manually"), SettingsStatus.markVisualMessagesSeen);
        FLAGS.put(str("spookd_exact_last_active"), SettingsStatus.exactLastActive);
        FLAGS.put(str("spookd_activity_alerts"), SettingsStatus.activityAlerts);

        FLAGS.put(str("spookd_customise_story_timestamp"),SettingsStatus.customiseStoryTimestamp);
        FLAGS.put(str("spookd_disable_story_flipping"),SettingsStatus.disableStoryFlipping);
        FLAGS.put(str("spookd_loop_story"),SettingsStatus.loopStory);
        FLAGS.put(str("spookd_hide_story_follow_button"),SettingsStatus.hideStoryFollowButton);
        FLAGS.put(str("spookd_view_story_mentions"),SettingsStatus.viewStoryMentions);
        FLAGS.put(str("spookd_story_auto_save"), SettingsStatus.storyAutoSave);
        FLAGS.put(str("spookd_story_playback_options"), SettingsStatus.storyPlayback);
        FLAGS.put(str("spookd_mute_stories"), SettingsStatus.storyMute);
        FLAGS.put(str("spookd_search_story_viewers"), SettingsStatus.searchStoryViewers);
        FLAGS.put(str("spookd_follow_back_indicator"),SettingsStatus.followBackIndicator);
        FLAGS.put(str("spookd_exact_profile_counts"), SettingsStatus.exactProfileCounts);
        FLAGS.put(str("spookd_profile_analyzer"), SettingsStatus.profileAnalyzer);
        FLAGS.put(str("spookd_square_profile_grid"), SettingsStatus.squareProfileGrid);
        FLAGS.put(str("spookd_profile_saved_tab"), SettingsStatus.profileSavedTab);
        FLAGS.put(str("spookd_confirm_follow"), SettingsStatus.confirmFollow);
        FLAGS.put(str("spookd_patch_confirm_feed_like"), SettingsStatus.confirmFeedLike);
        FLAGS.put(str("spookd_patch_confirm_reel_like"), SettingsStatus.confirmReelLike);
        FLAGS.put(str("spookd_patch_confirm_reel_repost"), SettingsStatus.confirmReelRepost);
        FLAGS.put(str("spookd_patch_confirm_comment_box"), SettingsStatus.confirmComment);
        FLAGS.put(str("spookd_patch_confirm_comment_sheet"), SettingsStatus.confirmCommentSheet);
        FLAGS.put(str("spookd_confirm_unfollow"), SettingsStatus.confirmUnfollow);
        FLAGS.put(str("spookd_patch_confirm_feed_repost"), SettingsStatus.confirmFeedRepost);
        FLAGS.put(str("spookd_patch_confirm_story_like"), SettingsStatus.confirmStoryLike);
        FLAGS.put(str("spookd_patch_confirm_story_reaction"), SettingsStatus.confirmStoryReaction);
        FLAGS.put(str("spookd_confirm_call"), SettingsStatus.confirmCall);
        FLAGS.put(str("spookd_confirm_follow_request"), SettingsStatus.confirmFollowRequest);
        FLAGS.put(str("spookd_confirm_refresh"), SettingsStatus.confirmRefresh);
        FLAGS.put(str("spookd_confirm_comment_like"), SettingsStatus.confirmCommentLike);
        FLAGS.put(str("spookd_patch_confirm_disappearing"), SettingsStatus.confirmDisappearingMessages);
        FLAGS.put(str("spookd_patch_refresh_controls"), SettingsStatus.refreshControls);
        FLAGS.put(str("spookd_disable_discover_people"),SettingsStatus.disableDiscoverPeople);
        FLAGS.put(str("spookd_customise_story_ring_size"),SettingsStatus.customiseStoryRingSize);

        FLAGS.put(str("spookd_change_like_animation"),SettingsStatus.changeLikeAnimation);
        FLAGS.put(str("spookd_inbox_lock"),SettingsStatus.inboxLock);
        FLAGS.put(str("spookd_hidden_chats"),SettingsStatus.hiddenChats);
        FLAGS.put(str("spookd_pin_recipients"), SettingsStatus.pinRecipients);

        FLAGS.put(str("spookd_disable_double_tap_to_like"),SettingsStatus.disableDoubleTapLike);
        FLAGS.put(str("spookd_hide_group_creation_button_on_sharesheet"),SettingsStatus.hideGroupCreationOnSharesheet);
        FLAGS.put(str("spookd_hide_reels_follow_button"),SettingsStatus.hideReelsFollowButton);
        FLAGS.put(str("spookd_limit_following_feed"),SettingsStatus.limitFollowingFeed);
        FLAGS.put(str("spookd_hide_feed_posts"),SettingsStatus.hideFeedPosts);
        FLAGS.put(str("spookd_hide_notes_tray"),SettingsStatus.hideNotesTray);
        FLAGS.put(str("spookd_hide_stories_tray"),SettingsStatus.hideStoriesTray);
        FLAGS.put(str("spookd_hide_hidden_requests"),SettingsStatus.hideHiddenRequests);
        FLAGS.put(str("spookd_disable_comments"),SettingsStatus.disableComments);
        FLAGS.put(str("spookd_disable_explore"),SettingsStatus.disableExplore);
        FLAGS.put(str("spookd_disable_highlights"),SettingsStatus.disableHighlights);
        FLAGS.put(str("spookd_disable_stories"),SettingsStatus.disableStories);
        FLAGS.put(str("spookd_disable_swipe_to_create"), SettingsStatus.disableSwipeToCreate);
        FLAGS.put(str("spookd_focus_lock"), SettingsStatus.focusLock);
        FLAGS.put(str("spookd_hide_comment_emoji_bar"), SettingsStatus.hideCommentEmojiBar);
        FLAGS.put(str("spookd_hide_reels_visual_search"), SettingsStatus.hideReelsVisualSearch);
        FLAGS.put(str("spookd_hide_profile_notes_bubble"), SettingsStatus.hideProfileNotesBubble);
        FLAGS.put(str("spookd_hide_threads_button"), SettingsStatus.hideThreadsButton);
        FLAGS.put(str("spookd_hide_friends_map"), SettingsStatus.hideFriendsMap);
        FLAGS.put(str("spookd_hide_recent_searches"), SettingsStatus.hideRecentSearches);
        FLAGS.put(str("spookd_square_explore_grid"), SettingsStatus.squareExploreGrid);
        FLAGS.put(str("spookd_hide_reels_comment_bar"), SettingsStatus.hideReelsCommentBar);
        FLAGS.put(str("spookd_hide_chat_blend_button"), SettingsStatus.hideChatBlendButton);
        FLAGS.put(str("spookd_hide_inbox_instants"), SettingsStatus.hideInboxInstants);
        FLAGS.put(str("spookd_hide_reels_tab_header"), SettingsStatus.hideReelsTabHeader);
        FLAGS.put(str("spookd_disable_app_haptics"), SettingsStatus.disableAppHaptics);

        FLAGS.put(str("spookd_view_dm_anonymously"),SettingsStatus.viewDmAnonymously);
        FLAGS.put(str("spookd_mark_seen_on"), SettingsStatus.markAsReadAfterReply);
        FLAGS.put(str("spookd_dm_ghost_mode"), SettingsStatus.dmGhostChats);
        FLAGS.put(str("spookd_save_deleted_messages"),SettingsStatus.saveDeletedMessages);
        FLAGS.put(str("spookd_dm_media_vault"),SettingsStatus.dmMediaVault);
        FLAGS.put(str("spookd_disable_screenshot_detection"),SettingsStatus.disableScreenshotDetection);
        FLAGS.put(str("spookd_disable_typing_status"),SettingsStatus.disableTypingStatus);
        FLAGS.put(str("spookd_view_live_anonymously"),SettingsStatus.viewLiveAnonymously);
        FLAGS.put(str("spookd_view_stories_anonymously"),SettingsStatus.viewStoriesAnonymously);
        FLAGS.put(str("spookd_fake_location"), SettingsStatus.fakeLocation);
        FLAGS.put(str("spookd_hidden_account_privacy_options"), SettingsStatus.hiddenAccountPrivacyOptions);
        FLAGS.put(str("spookd_instants_title"),SettingsStatus.instantsDownload);
        FLAGS.put(str("spookd_patch_instants_seen"), SettingsStatus.instantsSeen);
        FLAGS.put(str("spookd_disable_instants_creation"), SettingsStatus.disableInstantsCreation);
        FLAGS.put(str("spookd_confirm_instant_video"), SettingsStatus.confirmInstantVideo);
        FLAGS.put(str("spookd_confirm_instant_reaction"), SettingsStatus.confirmInstantReaction);

        FLAGS.put(str("spookd_sanitize_share_links"),SettingsStatus.sanitizeShareLinks);
        FLAGS.put(str("spookd_custom_sharing_domain"),SettingsStatus.customSharingDomain);
        FLAGS.put(str("spookd_open_links_externally"),SettingsStatus.openLinksExternally);
        FLAGS.put(str("spookd_detect_links_in_text"), SettingsStatus.detectLinksInText);
        FLAGS.put(str("spookd_hold_send_copy_link"), SettingsStatus.holdSendCopyLink);
        FLAGS.put(str("spookd_enable_dev_options"),SettingsStatus.enableDeveloperOptions);

        FLAGS.put(str("spookd_enable_mark_chat_as_read"),SettingsStatus.markChatAsRead);
        FLAGS.put(str("spookd_category_filter_content"),SettingsStatus.storyFilters);
        FLAGS.put(str("spookd_category_rec_flags"),SettingsStatus.recommendedFlags);
        FLAGS.put(str("spookd_custom_font"),SettingsStatus.customFont);

    }

    public static void load() {
        loadStatusMap();
    }
}
