/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.settings.preference;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceScreen;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import java.util.TreeMap;
import java.util.Map;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import app.morphe.extension.instagram.patches.devFlags.Flag;
import app.morphe.extension.instagram.patches.devFlags.RecommendedFlags;


import app.morphe.extension.crimera.legacydownloader.StorageUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.patches.Links;
import app.morphe.extension.instagram.patches.analyzer.AutoRefresh;
import app.morphe.extension.instagram.patches.analyzer.ProfileAnalyzer;
import app.morphe.extension.instagram.patches.customise.font.FontStorage;
import app.morphe.extension.instagram.patches.download.gallery.Gallery;
import app.morphe.extension.instagram.patches.focusLock.FocusLock;
import app.morphe.extension.instagram.patches.focusLock.FocusLockTargets;
import app.morphe.extension.instagram.patches.story.StoryAutoSave;
import app.morphe.extension.instagram.patches.story.StoryGhostList;
import app.morphe.extension.instagram.patches.dm.DmGhostChats;
import app.morphe.extension.instagram.patches.dm.presence.ActivityAlerts;
import app.morphe.extension.instagram.patches.instants.InstantsMenu;
import app.morphe.extension.instagram.patches.location.FakeLocationScreen;
import app.morphe.extension.instagram.patches.location.FakePlaces;
import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch;
import app.morphe.extension.instagram.patches.navigation.TabShortcuts;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.RestartPolicy;
import app.morphe.extension.instagram.settings.SafeStartup;
import app.morphe.extension.instagram.settings.SettingsRestart;
import app.morphe.extension.instagram.settings.StorageScreen;
import app.morphe.extension.instagram.settings.BooleanSetting;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.StringSetting;
import app.morphe.extension.instagram.settings.preference.widgets.*;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.theme.MaterialYouTheme;

public class ScreenBuilder {
    private final Context context;
    private final PreferenceScreen screen;
    private final Helper helper;

    public ScreenBuilder(Context context, PreferenceScreen screen, Helper helper) {
        this.context = context;
        this.screen = screen;
        this.helper = helper;
    }

    public void buildFragment(String fragmentName) {
        if (Constants.SPOOKD_FRAGMENT_SETTINGS.equals(fragmentName)) {
            buildSettingsPage();
        } else if (Constants.SPOOKD_FRAGMENT_ADS.equals(fragmentName)) {
            buildAdsSection();
        } else if (Constants.SPOOKD_FRAGMENT_GHOST.equals(fragmentName)) {
            ghostSection();
        } else if (Constants.SPOOKD_FRAGMENT_STORY.equals(fragmentName)) {
            storySection();
        } else if (Constants.SPOOKD_FRAGMENT_LINKS.equals(fragmentName)) {
            linksSection();
        } else if (Constants.SPOOKD_FRAGMENT_DISTRACTION_FREE.equals(fragmentName)) {
            distractionFreeSection();
        } else if (Constants.SPOOKD_FRAGMENT_MISC.equals(fragmentName)) {
            buildMiscSection();
        } else if (Constants.SPOOKD_FRAGMENT_DOWNLOAD_MEDIA.equals(fragmentName)) {
            buildDownloadSection();
        } else if (Constants.SPOOKD_FRAGMENT_NAV_BTNS.equals(fragmentName)) {
            buildNavigationSection();
        } else if (Constants.SPOOKD_FRAGMENT_DEV_OPTIONS.equals(fragmentName)) {
            buildDeveloperSection();
        } else if (Constants.SPOOKD_FRAGMENT_ABOUT.equals(fragmentName)) {
            aboutSection(SettingsStatus.FLAGS);
        } else if (Constants.SPOOKD_FRAGMENT_ACTION_BAR.equals(fragmentName)) {
            buildActionBarSection();
        } else if (Constants.SPOOKD_FRAGMENT_DM.equals(fragmentName)) {
            dmSection();
        } else if (Constants.SPOOKD_FRAGMENT_FILTER_CONTENT.equals(fragmentName)) {
            filterContentSection();
        } else if (RecommendedFlagsScreen.isFlagsScreen(fragmentName)) {
            RecommendedFlagsScreen.build(context, screen, fragmentName, false, RecommendedFlags.getFlags(false));
        } else if (Constants.SPOOKD_FRAGMENT_FOCUS_LOCK.equals(fragmentName)) {
            buildFocusLockSection();
        } else if (Constants.SPOOKD_FRAGMENT_INSTANTS.equals(fragmentName)) {
            buildInstantsSection();
        } else if (Constants.SPOOKD_FRAGMENT_FONT.equals(fragmentName)) {
            buildFontSection();
        }
    }

    public void buildFragmentForSearch(String fragmentName, List<Flag> flags) {
        if (RecommendedFlagsScreen.isFlagsScreen(fragmentName)) {
            RecommendedFlagsScreen.build(context, screen, fragmentName, true, flags);
        } else {
            buildFragment(fragmentName);
        }
    }

    private void addPreference(Preference pref) {
        addPreference(null, pref);
    }

    // Adding preference category might be used in the future
    // to segregate the preference inside the fragment
    private void addPreference(PreferenceCategory category, Preference pref) {
        if (category != null) {
            category.addPreference(pref);
        } else {
            screen.addPreference(pref);
        }
    }

    private PreferenceCategory addCategory(String title) {
        CategoryPref preferenceCategory = new CategoryPref(context);
        preferenceCategory.setFirstCategory(screen.getPreferenceCount() == 0);
        preferenceCategory.setTitle(title);
        screen.addPreference(preferenceCategory);
        return preferenceCategory;
    }

    public void buildAdsSection() {
        if (!(SettingsStatus.adsSection())) return;

        if (SettingsStatus.disableAds) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_disable_ads"),
                            "",
                            Settings.DISABLE_ADS
                    )
            );
        }
        if (SettingsStatus.hideSuggestedContent) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_hide_suggested_content"),
                            str("spookd_hide_suggested_content_desc"),
                            Settings.HIDE_SUGGESTED_CONTENT
                    )
            );
        }
        if (SettingsStatus.hideMetaAi) {
            addPreference(helper.switchPreference(
                    str("spookd_hide_meta_ai"),
                    str("spookd_hide_meta_ai_desc"),
                    Settings.HIDE_META_AI
            ));
        }
    }

    public void buildFontSection() {
        if (!(SettingsStatus.fontSection())) return;

        // Written here rather than left to the preference framework, which persists only after
        // this listener has run - FontStorage.useSystemFont() has to read the new value straight
        // away, from load() on the next start up.
        SwitchPref useSystemFont = new SwitchPref(context);
        useSystemFont.setTitle(str("spookd_use_system_font"));
        useSystemFont.setSummary(str("spookd_use_system_font_desc"));
        useSystemFont.setKey(Settings.USE_SYSTEM_FONT.key);
        useSystemFont.setPersistent(false);
        useSystemFont.setChecked(FontStorage.useSystemFont());
        useSystemFont.setOnPreferenceChangeListener((preference, newValue) -> {
            FontStorage.setUseSystemFont(Boolean.TRUE.equals(newValue));
            return true;
        });
        addPreference(useSystemFont);

        addPreference(helper.buttonPreference(
                str("spookd_pref_add_font"),
                str("spookd_pref_add_font_desc"),
                "spookd_pref_add_font"
        ));

        addPreference(helper.buttonPreference(
                str("spookd_pref_delete_font"),
                str("spookd_pref_delete_font_desc"),
                "spookd_pref_delete_font"
        ));
    }

    public void buildDeveloperSection() {
        if (!(SettingsStatus.developerOptionsSection())) return;

        // PreferenceCategory category= addCategory(str("spookd_category_dev_options"));
        if (SettingsStatus.recommendedFlags) {
            addPreference(
                    helper.buttonPreference(
                            str("spookd_category_rec_flags"),
                            str("spookd_category_rec_flags_desc"),
                            Constants.SPOOKD_FRAGMENT_REC_FLAGS
                    )
            );
        }

        if (SettingsStatus.enableDeveloperOptions) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_enable_dev_options"),
                            str("spookd_enable_dev_options_desc"),
                            Settings.DEVELOPER_OPTIONS
                    )
            );
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_directly_open_metaconfig"),
                                    str("spookd_directly_open_metaconfig_desc"),
                                    Settings.DIRECTLY_OPEN_METACONFIG
                            ),
                            () -> SharedPref.getBooleanPref(Settings.DEVELOPER_OPTIONS)
                    )
            );
            addPreference(
                    helper.buttonPreference(
                            str("spookd_export_dev_overrides"),
                            "",
                            "spookd_export_dev_overrides"
                    )
            );
            addPreference(
                    helper.buttonPreference(
                            str("spookd_import_dev_overrides"),
                            "",
                            "spookd_import_dev_overrides"
                    )
            );
            addPreference(
                    helper.buttonPreference(
                            str("spookd_import_id_mapping"),
                            "",
                            "spookd_import_id_mapping"
                    )
            );
            addPreference(
                    helper.buttonPreference(
                            str("spookd_download_id_mapping"),
                            "",
                            "spookd_download_id_mapping"
                    )
            );

            if(Pref.spookdDebug()) {
                addPreference(
                        helper.buttonPreference(
                                str("spookd_export_experiment_list"),
                                "",
                                "spookd_export_experiment_list"
                        )
                );

                addPreference(
                        helper.buttonPreference(
                                str("spookd_export_experiment_mappings"),
                                "",
                                "spookd_export_experiment_mappings"
                        )
                );
            }
        }
    }

    public void dmSection() {
        if (!(SettingsStatus.dmSection())) return;

        if (SettingsStatus.disableDisappearingMessagesGesture) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_disable_disappearing_messages_gesture"),
                            str("spookd_disable_disappearing_messages_gesture_desc"),
                            Settings.DISABLE_DISAPPEARING_MESSAGES_GESTURE
                    )
            );
        }

        if (SettingsStatus.sendKeyboardGifs) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_send_keyboard_gifs"),
                            str("spookd_send_keyboard_gifs_desc"),
                            Settings.SEND_KEYBOARD_GIFS
                    )
            );
        }

        if (SettingsStatus.unlimitedReplaysOnEphemeralMedia) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_unlimited_replays"),
                            str("spookd_unlimited_replays_desc"),
                            Settings.UNLIMITED_REPLAYS
                    )
            );
            addPreference(
                    helper.switchPreference(
                            str("spookd_ephemeral_caption"),
                            str("spookd_ephemeral_caption_desc"),
                            Settings.EPHEMERAL_MEDIA_CAPTION
                    )
            );
        }

        if (SettingsStatus.stopVisualMessageAutoAdvance) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_stop_visual_message_auto_advance"),
                            str("spookd_stop_visual_message_auto_advance_desc"),
                            Settings.STOP_VISUAL_MESSAGE_AUTO_ADVANCE
                    )
            );
        }

        if (SettingsStatus.markVisualMessagesSeen) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_mark_visual_messages_seen_manually"),
                                    str("spookd_mark_visual_messages_seen_manually_desc"),
                                    Settings.MARK_VISUAL_MESSAGES_SEEN_MANUALLY
                            ),
                            () -> SharedPref.getBooleanPref(Settings.UNLIMITED_REPLAYS)
                    )
            );
        }

        if (SettingsStatus.exactLastActive) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_exact_last_active"),
                            str("spookd_exact_last_active_desc"),
                            Settings.EXACT_LAST_ACTIVE
                    )
            );
        }

        if (SettingsStatus.markChatAsRead) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_enable_mark_chat_as_read"),
                            str("spookd_enable_mark_chat_as_read_desc"),
                            Settings.ENABLE_MARK_CHAT_AS_READ
                    )
            );
        }

        if (SettingsStatus.inboxLock) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_inbox_lock"),
                            str("spookd_inbox_lock_desc"),
                            Settings.INBOX_LOCK
                    )
            );
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_inbox_lock_notifications"),
                                    str("spookd_inbox_lock_notifications_desc"),
                                    Settings.INBOX_LOCK_NOTIFICATIONS
                            ),
                            () -> SharedPref.getBooleanPref(Settings.INBOX_LOCK)
                    )
            );
        }

        if (SettingsStatus.hiddenChats) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_hidden_chats"),
                            str("spookd_hidden_chats_desc"),
                            Settings.HIDDEN_CHATS
                    )
            );
            addPreference(
                    helper.availableWhen(
                            helper.listPreference(
                                    str("spookd_hidden_chats_taps"),
                                    str("spookd_hidden_chats_taps_desc"),
                                    Settings.HIDDEN_CHATS_TAPS
                            ),
                            () -> SharedPref.getBooleanPref(Settings.HIDDEN_CHATS)
                    )
            );
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_hidden_chats_mute"),
                                    str("spookd_hidden_chats_mute_desc"),
                                    Settings.HIDDEN_CHATS_MUTE
                            ),
                            () -> SharedPref.getBooleanPref(Settings.HIDDEN_CHATS)
                    )
            );
        }

        if (SettingsStatus.pinRecipients) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_pin_recipients"),
                            str("spookd_pin_recipients_desc"),
                            Settings.PIN_RECIPIENTS
                    )
            );
        }

        if (SettingsStatus.saveDeletedMessages) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_save_deleted_messages"),
                            str("spookd_save_deleted_messages_desc"),
                            Settings.SAVE_DELETED_MESSAGES
                    )
            );
            addPreference(
                    helper.buttonPreference(
                            str("spookd_view_deleted_messages"),
                            "",
                            "view_deleted_messages"
                    )
            );
        }

        if (SettingsStatus.dmMediaVault) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_dm_media_vault"),
                                    str("spookd_dm_media_vault_desc"),
                                    Settings.DM_MEDIA_VAULT
                            ),
                            () -> SharedPref.getBooleanPref(Settings.SAVE_DELETED_MESSAGES)
                    )
            );
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_dm_media_vault_view_once"),
                                    str("spookd_dm_media_vault_view_once_desc"),
                                    Settings.DM_MEDIA_VAULT_VIEW_ONCE
                            ),
                            () -> SharedPref.getBooleanPref(Settings.SAVE_DELETED_MESSAGES)
                                    && SharedPref.getBooleanPref(Settings.DM_MEDIA_VAULT)
                    )
            );
        }

        if (SettingsStatus.activityAlerts) {
            addPreference(helper.switchPreference(str("spookd_activity_alert_online"),
                    str("spookd_activity_alert_online_desc"), Settings.ACTIVITY_ALERT_ONLINE));
            addPreference(helper.switchPreference(str("spookd_activity_alert_offline"),
                    str("spookd_activity_alert_offline_desc"), Settings.ACTIVITY_ALERT_OFFLINE));
            addPreference(helper.switchPreference(str("spookd_activity_alert_typing"),
                    str("spookd_activity_alert_typing_desc"), Settings.ACTIVITY_ALERT_TYPING));
            addPreference(helper.switchPreference(str("spookd_activity_alert_read"),
                    str("spookd_activity_alert_read_desc"), Settings.ACTIVITY_ALERT_READ));
            Preference activityUsers = helper.buttonPreference(
                    str("spookd_activity_alert_users"),
                    str("spookd_activity_alert_users_desc"),
                    "spookd_activity_alert_users"
            );
            activityUsers.setOnPreferenceClickListener(preference -> {
                ActivityAlerts.showList(preference.getContext());
                return true;
            });
            addPreference(activityUsers);

            ListPref presenceRefresh = new ListPref(context) {
                @Override
                protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
                    super.onSetInitialValue(restoreValue, defaultValue);
                    setEntries(new CharSequence[]{str("spookd_array_default"),
                            str("spookd_presence_refresh_every", 1), str("spookd_presence_refresh_every", 5),
                            str("spookd_presence_refresh_every", 15)});
                    setEntryValues(new CharSequence[]{"default", "1", "5", "15"});
                }
            };
            presenceRefresh.setTitle(str("spookd_presence_refresh"));
            presenceRefresh.setDialogTitle(str("spookd_presence_refresh"));
            presenceRefresh.setSummary(str("spookd_presence_refresh_desc"));
            presenceRefresh.setKey(Settings.PRESENCE_REFRESH_MINUTES.key);
            presenceRefresh.setDefaultValue(Settings.PRESENCE_REFRESH_MINUTES.defaultValue);
            addPreference(presenceRefresh);
        }
    }

    public void ghostSection() {
        if (!(SettingsStatus.ghostSection())) return;

        // PreferenceCategory category= addCategory(str("spookd_category_ghost"));

        addPreference(
                helper.switchPreference(
                        str("spookd_turn_on_all_ghost_modes"),
                        str("spookd_turn_on_all_ghost_modes_desc"),
                        Settings.TURN_ON_ALL_GHOST_MODES
                )
        );

        if (SettingsStatus.viewStoriesAnonymously) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_view_stories_anonymously"),
                            "",
                            Settings.VIEW_STORIES_ANONYMOUSLY
                    )
            );
            addPreference(
                    helper.availableWhen(
                            helper.listPreference(
                                    str("spookd_story_ghost_mode"),
                                    "%s",
                                    Settings.STORY_GHOST_MODE
                            ),
                            () -> SharedPref.getBooleanPref(Settings.VIEW_STORIES_ANONYMOUSLY)
                                    || SharedPref.getBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES)
                    )
            );
            Preference storyGhostUsers = helper.buttonPreference(
                    str("spookd_story_ghost_users"),
                    str("spookd_story_ghost_users_desc"),
                    "spookd_story_ghost_users"
            );
            storyGhostUsers.setOnPreferenceClickListener(preference -> {
                StoryGhostList.showList(preference.getContext());
                return true;
            });
            addPreference(storyGhostUsers);
        }
        if (SettingsStatus.markStorySeenOnInteraction) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_mark_story_seen_on_interaction"),
                                    str("spookd_mark_story_seen_on_interaction_desc"),
                                    Settings.MARK_STORY_SEEN_ON_INTERACTION
                            ),
                            () -> SharedPref.getBooleanPref(Settings.VIEW_STORIES_ANONYMOUSLY)
                                    || SharedPref.getBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES)
                    )
            );
        }
        if (SettingsStatus.markEarlierStoriesSeen) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_mark_earlier_stories_seen"),
                                    str("spookd_mark_earlier_stories_seen_desc"),
                                    Settings.MARK_EARLIER_STORIES_SEEN
                            ),
                            () -> SharedPref.getBooleanPref(Settings.VIEW_STORIES_ANONYMOUSLY)
                                    || SharedPref.getBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES)
                    )
            );
        }
        if (SettingsStatus.advanceStory) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_advance_story_on_mark_seen"),
                                    str("spookd_advance_story_on_mark_seen_desc"),
                                    Settings.ADVANCE_STORY_ON_MARK_SEEN
                            ),
                            () -> SharedPref.getBooleanPref(Settings.VIEW_STORIES_ANONYMOUSLY)
                                    || SharedPref.getBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES)
                    )
            );
            if (SettingsStatus.markStorySeenOnInteraction) {
                addPreference(
                        helper.switchPreference(
                                str("spookd_advance_story_on_like"),
                                str("spookd_advance_story_on_like_desc"),
                                Settings.ADVANCE_STORY_ON_LIKE
                        )
                );
                addPreference(
                        helper.switchPreference(
                                str("spookd_advance_story_on_reply"),
                                str("spookd_advance_story_on_reply_desc"),
                                Settings.ADVANCE_STORY_ON_REPLY
                        )
                );
            }
        }
        if (SettingsStatus.viewLiveAnonymously) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_view_live_anonymously"),
                            "",
                            Settings.VIEW_LIVE_ANONYMOUSLY
                    )
            );
        }
        if (SettingsStatus.disableTypingStatus) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_disable_typing_status"),
                            "",
                            Settings.DISABLE_TYPING_STATUS
                    )
            );
        }
        if (SettingsStatus.disableScreenshotDetection) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_disable_screenshot_detection"),
                            "",
                            Settings.DISABLE_SCREENSHOT_DETECTION
                    )
            );
        }
        if (SettingsStatus.viewDmAnonymously) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_view_dm_anonymously"),
                            str("spookd_view_dm_anonymously_desc"),
                            Settings.VIEW_DM_ANONYMOUSLY
                    )
            );
        }
        if (SettingsStatus.dmGhostChats) {
            BooleanSupplier dmGhostOn = () -> SharedPref.getBooleanPref(Settings.VIEW_DM_ANONYMOUSLY)
                    || SharedPref.getBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES);
            addPreference(
                    helper.availableWhen(
                            helper.listPreference(
                                    str("spookd_dm_ghost_mode"),
                                    "%s",
                                    Settings.DM_GHOST_MODE
                            ),
                            dmGhostOn
                    )
            );
            Preference dmGhostChats = helper.buttonPreference(
                    str("spookd_dm_ghost_chats"),
                    str("spookd_dm_ghost_chats_desc"),
                    "spookd_dm_ghost_chats"
            );
            dmGhostChats.setOnPreferenceClickListener(preference -> {
                DmGhostChats.showList(preference.getContext());
                return true;
            });
            addPreference(helper.availableWhen(dmGhostChats, dmGhostOn));
        }
        if (SettingsStatus.keepSeenLocally) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_keep_seen_locally"),
                                    str("spookd_keep_seen_locally_desc"),
                                    Settings.KEEP_SEEN_LOCALLY
                            ),
                            () -> SharedPref.getBooleanPref(Settings.VIEW_DM_ANONYMOUSLY)
                                    || SharedPref.getBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES)
                    )
            );
        }
        if (SettingsStatus.markAsReadAfterReply) {
            // Migrates the old switch before the row reads the saved selection.
            Pref.markSeenOn();
            MultiSelectListPref markSeenOn = (MultiSelectListPref) helper.multiSelectListPref(
                    str("spookd_mark_seen_on"),
                    str("spookd_mark_seen_on_desc"),
                    Settings.MARK_SEEN_ON
            );
            markSeenOn.setEntries(new CharSequence[]{
                    str("spookd_mark_seen_on_reply"),
                    str("spookd_mark_seen_on_reaction"),
                    str("spookd_mark_seen_on_send"),
                    str("spookd_mark_seen_on_typing")
            });
            markSeenOn.setEntryValues(new CharSequence[]{"reply", "reaction", "send", "typing"});
            addPreference(
                    helper.availableWhen(
                            markSeenOn,
                            () -> SharedPref.getBooleanPref(Settings.VIEW_DM_ANONYMOUSLY)
                                    || SharedPref.getBooleanPref(Settings.TURN_ON_ALL_GHOST_MODES)
                    )
            );
        }
        if (SettingsStatus.fakeLocation) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_fake_location"),
                            str("spookd_fake_location_desc"),
                            Settings.FAKE_LOCATION
                    )
            );
            Preference places = helper.buttonPreference(
                    str("spookd_fake_location_places"),
                    FakeLocationScreen.summary(),
                    "spookd_fake_location_places"
            );
            places.setOnPreferenceClickListener(preference -> {
                FakeLocationScreen.showPlaces(preference.getContext(),
                        () -> preference.setSummary(FakeLocationScreen.summary()));
                return true;
            });
            addPreference(places);
            FakePlaces.whenLoaded(() -> places.setSummary(FakeLocationScreen.summary()));
        }

    }

    public void linksSection() {
        if (!(SettingsStatus.linksSection())) return;

        // PreferenceCategory category= addCategory(str("spookd_category_links"));
        if (SettingsStatus.openLinksExternally) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_open_links_externally"),
                            str("spookd_open_links_externally_desc"),
                            Settings.OPEN_LINKS_EXTERNALLY
                    )
            );
        }
        if (SettingsStatus.sanitizeShareLinks) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_sanitize_share_links"),
                            "",
                            Settings.SANITIZE_SHARE_LINKS
                    )
            );
        }
        if (SettingsStatus.customSharingDomain) {
            addPreference(
                    helper.editTextPreference(
                            str("spookd_custom_sharing_domain"),
                            Links.customSharingDomainSummary(Pref.customSharingDomain()),
                            Settings.CUSTOM_SHARING_DOMAIN
                    )
            );
        }
        if (SettingsStatus.detectLinksInText) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_detect_links_in_text"),
                            str("spookd_detect_links_in_text_desc"),
                            Settings.DETECT_LINKS_IN_TEXT
                    )
            );
        }
        if (SettingsStatus.holdSendCopyLink) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_hold_send_copy_link"),
                            str("spookd_hold_send_copy_link_desc"),
                            Settings.HOLD_SEND_COPY_LINK
                    )
            );
        }
    }

    public void distractionFreeSection() {
        if (!(SettingsStatus.distractionFreeSection())) return;

        // PreferenceCategory category= addCategory(str("spookd_category_distraction_free"));

        if (SettingsStatus.focusLock) {
            addPreference(
                    helper.buttonPreference(
                            str("spookd_focus_lock"),
                            FocusLock.statusSummary(),
                            Constants.SPOOKD_FRAGMENT_FOCUS_LOCK
                    )
            );
        }

        if (SettingsStatus.disableStories) {
            addLockableSwitch(
                    str("spookd_disable_stories"),
                    "",
                    Settings.DISABLE_STORIES
            );
        }
        if (SettingsStatus.hideStoriesTray) {
            addLockableSwitch(
                    str("spookd_hide_stories_tray"),
                    str("spookd_hide_stories_tray_desc"),
                    Settings.HIDE_STORIES_TRAY
            );
        }
        if (SettingsStatus.hideHiddenRequests) {
            addLockableSwitch(
                    str("spookd_hide_hidden_requests"),
                    str("spookd_hide_hidden_requests_desc"),
                    Settings.HIDE_HIDDEN_REQUESTS
            );
        }
        if (SettingsStatus.disableHighlights) {
            addLockableSwitch(
                    str("spookd_disable_highlights"),
                    "",
                    Settings.DISABLE_HIGHLIGHTS
            );
        }
        if (SettingsStatus.hideNotesTray) {
            addLockableSwitch(
                    str("spookd_hide_notes_tray"),
                    str("spookd_hide_notes_tray_desc"),
                    Settings.HIDE_NOTES_TRAY
            );
        }
        if (SettingsStatus.disableExplore) {
            addLockableSwitch(
                    str("spookd_disable_explore"),
                    "",
                    Settings.DISABLE_EXPLORE
            );
        }
        if (SettingsStatus.disableComments) {
            addLockableSwitch(
                    str("spookd_disable_comments"),
                    "",
                    Settings.DISABLE_COMMENTS
            );
        }
        if (SettingsStatus.hideFeedPosts) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_hide_feed_posts"),
                            str("spookd_hide_feed_posts_desc"),
                            Settings.HIDE_FEED_POSTS
                    )
            );
        }
        if (SettingsStatus.limitFollowingFeed) {
            addLockableSwitch(
                    str("spookd_limit_following_feed"),
                    str("spookd_limit_following_feed_desc"),
                    Settings.LIMIT_FOLLOWING_FEED
            );
        }
        if (SettingsStatus.disableDiscoverPeople) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_disable_discover_people"),
                            "",
                            Settings.DISABLE_DISCOVER_PEOPLE
                    )
            );
        }
        if (SettingsStatus.disableReelsScrolling) {
            addLockableSwitch(
                    str("spookd_disable_reels_scrolling"),
                    str("spookd_disable_reels_scrolling_desc"),
                    Settings.DISABLE_REELS_SCROLLING
            );
        }
        if (SettingsStatus.disableSwipeToCreate) {
            addLockableSwitch(
                    str("spookd_disable_swipe_to_create"),
                    str("spookd_disable_swipe_to_create_desc"),
                    Settings.DISABLE_SWIPE_TO_CREATE
            );
        }
        if (SettingsStatus.hideGroupCreationOnSharesheet) {
            addLockableSwitch(
                    str("spookd_hide_group_creation_button_on_sharesheet"),
                    "",
                    Settings.HIDE_GROUP_CREATION_BUTTON_ON_SHARESHEET
            );
        }
        if (SettingsStatus.hideReelsFollowButton) {
            addLockableSwitch(
                    str("spookd_hide_reels_follow_button"),
                    str("spookd_hide_reels_follow_button_desc"),
                    Settings.HIDE_REELS_FOLLOW_BUTTON
            );
        }
        if (SettingsStatus.hideLikes) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_hide_likes"),
                            "",
                            Settings.HIDE_LIKES
                    )
            );
        }
        if (SettingsStatus.hideShareButton) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_hide_share_button"),
                            "",
                            Settings.HIDE_SHARE_BUTTON
                    )
            );
        }
        if (SettingsStatus.hideSaveButtons) {
            addPreference(
                    helper.multiSelectListPref(
                            str("spookd_hide_save_buttons"),
                            "",
                            Settings.HIDE_SAVE_BUTTON_SURFACES
                    )
            );
        }
        if (SettingsStatus.hideReshareButton) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_hide_reshare_button"),
                            "",
                            Settings.HIDE_RESHARE_BUTTON
                    )
            );
        }
        if (SettingsStatus.disableVideoAutoplay) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_disable_video_autoplay"),
                            "",
                            Settings.DISABLE_VIDEO_AUTOPLAY
                    )
            );
        }
        if (SettingsStatus.disableDoubleTapLike) {
            addLockableSwitch(
                    str("spookd_disable_double_tap_like_post"),
                    "",
                    Settings.DISABLE_DOUBLE_TAP_LIKE_POST
            );

            addLockableSwitch(
                    str("spookd_disable_double_tap_like_reel"),
                    "",
                    Settings.DISABLE_DOUBLE_TAP_LIKE_REEL
            );

            addLockableSwitch(
                    str("spookd_disable_double_tap_like_comment"),
                    "",
                    Settings.DISABLE_DOUBLE_TAP_LIKE_COMMENT
            );

            addLockableSwitch(
                    str("spookd_disable_double_tap_like_message"),
                    "",
                    Settings.DISABLE_DOUBLE_TAP_LIKE_MESSAGE
            );
        }
        if (SettingsStatus.hideCommentEmojiBar) {
            addPreference(helper.switchPreference(str("spookd_hide_comment_emoji_bar"), str("spookd_hide_comment_emoji_bar_desc"), Settings.HIDE_COMMENT_EMOJI_BAR));
        }
        if (SettingsStatus.hideReelsVisualSearch) {
            addPreference(helper.switchPreference(str("spookd_hide_reels_visual_search"), str("spookd_hide_reels_visual_search_desc"), Settings.HIDE_REELS_VISUAL_SEARCH));
        }
        if (SettingsStatus.hideProfileNotesBubble) {
            addPreference(helper.switchPreference(str("spookd_hide_profile_notes_bubble"), str("spookd_hide_profile_notes_bubble_desc"), Settings.HIDE_PROFILE_NOTES_BUBBLE));
        }
        if (SettingsStatus.hideThreadsButton) {
            addPreference(helper.switchPreference(str("spookd_hide_threads_button"), str("spookd_hide_threads_button_desc"), Settings.HIDE_THREADS_BUTTON));
        }
        if (SettingsStatus.hideFriendsMap) {
            addPreference(helper.switchPreference(str("spookd_hide_friends_map"), str("spookd_hide_friends_map_desc"), Settings.HIDE_FRIENDS_MAP));
        }
        if (SettingsStatus.hideRecentSearches) {
            addPreference(helper.switchPreference(str("spookd_hide_recent_searches"), str("spookd_hide_recent_searches_desc"), Settings.HIDE_RECENT_SEARCHES));
        }
        if (SettingsStatus.squareExploreGrid) {
            addPreference(helper.switchPreference(str("spookd_square_explore_grid"), str("spookd_square_explore_grid_desc"), Settings.SQUARE_EXPLORE_GRID));
        }
        if (SettingsStatus.hideReelsCommentBar) {
            addPreference(helper.switchPreference(str("spookd_hide_reels_comment_bar"), str("spookd_hide_reels_comment_bar_desc"), Settings.HIDE_REELS_COMMENT_BAR));
        }
        if (SettingsStatus.hideReelsTabHeader) {
            addPreference(helper.switchPreference(str("spookd_hide_reels_tab_header"), str("spookd_hide_reels_tab_header_desc"), Settings.HIDE_REELS_TAB_HEADER));
        }
        if (SettingsStatus.hideChatBlendButton) {
            addPreference(helper.switchPreference(str("spookd_hide_chat_blend_button"), str("spookd_hide_chat_blend_button_desc"), Settings.HIDE_CHAT_BLEND_BUTTON));
        }
        if (SettingsStatus.hideInboxInstants) {
            addPreference(helper.switchPreference(str("spookd_hide_inbox_instants"), str("spookd_hide_inbox_instants_desc"), Settings.HIDE_INBOX_INSTANTS));
        }
        if (SettingsStatus.restoreAccountSearch) {
            addPreference(helper.switchPreference(str("spookd_restore_account_search"), str("spookd_restore_account_search_desc"), Settings.RESTORE_ACCOUNT_SEARCH));
        }
    }

    /** Adds a Distraction free switch, shown forced on while Focus Lock holds it. */
    private void addLockableSwitch(String title, String summary, BooleanSetting setting) {
        addPreference(
                helper.forcedSwitchPreference(
                        title,
                        summary,
                        setting,
                        FocusLock.isForced(setting),
                        str("spookd_focus_lock_enforced")
                )
        );
    }

    /**
     * The Focus Lock screen: what to hold, for how long, and the lock itself.
     *
     * The picker lists the same settings as the Distraction free screen, by the same names, so
     * it is obvious what each entry will do. Everything except the action row is disabled while
     * the lock is on, since changing it then would defeat the point.
     */
    public void buildFocusLockSection() {
        if (!SettingsStatus.focusLock) return;

        boolean locked = FocusLock.isActive();

        addPreference(
                helper.buttonPreference(
                        FocusLock.buttonTitle(),
                        FocusLock.statusSummary(),
                        "spookd_focus_lock_action"
                )
        );

        FocusLockDurationPreference duration = new FocusLockDurationPreference(context);
        duration.setTitle(str("spookd_focus_lock_duration"));
        duration.setEnabled(!locked);
        addPreference(duration);

        PreferenceCategory targets = addCategory(str("spookd_focus_lock_targets"));
        for (FocusLockTargets.Target target : FocusLockTargets.available()) {
            Preference pref = helper.switchPreference(
                    str(target.titleKey),
                    target.summaryKey == null ? "" : str(target.summaryKey),
                    Pref.focusLockSelection(target.key)
            );
            if (locked && pref instanceof SwitchPref) {
                ((SwitchPref) pref).setSwitchInteractionEnabled(false);
            }
            addPreference(targets, pref);
        }
    }

    public void buildMiscSection() {
        if (!(SettingsStatus.miscSection())) return;

        // PreferenceCategory category= addCategory(str("spookd_category_misc"));
        if (MaterialYouTheme.isAmoledAvailable()) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_amoled_title"),
                                    "",
                                    Settings.AMOLED_THEME
                            ),
                            () -> MaterialYouTheme.canEnableAmoled(context)
                    )
            );
        }
        if (MaterialYouTheme.isMaterialYouAvailable()) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_material_you_title"),
                            "",
                            Settings.MATERIAL_YOU_THEME
                    )
            );
        }
        if (SettingsStatus.fontSection()) {
            addPreference(
                    helper.buttonPreference(
                            str("spookd_category_font"),
                            "",
                            Constants.SPOOKD_FRAGMENT_FONT
                    )
            );
        }
        if (SettingsStatus.changeLikeAnimation) {
            addPreference(
                    helper.listPreference(
                            str("spookd_change_like_animation"),
                            str("spookd_change_like_animation_desc"),
                            Settings.CHANGE_LIKE_ANIMATION
                    )
            );
        }
        if (SettingsStatus.moreOptionsOnPost) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_enable_more_options_on_post"),
                            str("spookd_enable_more_options_on_post_desc"),
                            Settings.ENABLE_MORE_OPTIONS_ON_POST
                    )
            );
        }
        if (SettingsStatus.followBackIndicator) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_follow_back_indicator"),
                            str("spookd_follow_back_indicator_desc"),
                            Settings.FOLLOW_BACK_INDICATOR
                    )
            );
        }
        if (SettingsStatus.followBackIndicator || SettingsStatus.followListNonFollowerBadge) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_fbi_color_indicator"),
                                    "",
                                    Settings.FOLLOW_BACK_COLOR_INDICATOR
                            ),
                            () -> (SettingsStatus.followBackIndicator
                                    && SharedPref.getBooleanPref(Settings.FOLLOW_BACK_INDICATOR))
                                    || (SettingsStatus.followListNonFollowerBadge
                                    && SharedPref.getBooleanPref(Settings.FOLLOW_LIST_NON_FOLLOWER_BADGE))
                    )
            );
        }
        if (SettingsStatus.followListNonFollowerBadge) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_follow_list_non_follower_badge"),
                            str("spookd_follow_list_non_follower_badge_desc"),
                            Settings.FOLLOW_LIST_NON_FOLLOWER_BADGE
                    )
            );
        }
        if (SettingsStatus.profileAnalyzer) {
            addPreference(helper.buttonPreference(str("spookd_profile_analyzer"),
                    ProfileAnalyzer.rowSummary(context), "profile_analyzer"));
            addPreference(helper.switchPreference(
                    str("spookd_track_visited_profiles"),
                    str("spookd_track_visited_profiles_desc"),
                    Settings.TRACK_VISITED_PROFILES
            ));
            addPreference(AutoRefresh.preference(context));
        }
        if (SettingsStatus.exactProfileCounts) {
            addPreference(helper.switchPreference(
                    str("spookd_exact_profile_counts"),
                    str("spookd_exact_profile_counts_desc"),
                    Settings.EXACT_PROFILE_COUNTS
            ));
        }
        if (SettingsStatus.squareProfileGrid) {
            addPreference(helper.switchPreference(
                    str("spookd_square_profile_grid"),
                    str("spookd_square_profile_grid_desc"),
                    Settings.SQUARE_PROFILE_GRID
            ));
        }
        if (SettingsStatus.profileSavedTab) {
            addPreference(helper.switchPreference(
                    str("spookd_profile_saved_tab"),
                    str("spookd_profile_saved_tab_desc"),
                    Settings.PROFILE_SAVED_TAB
            ));
        }
        if (SettingsStatus.hideProfileGridReels) {
            addPreference(helper.switchPreference(
                    str("spookd_hide_profile_grid_reels"),
                    str("spookd_hide_profile_grid_reels_desc"),
                    Settings.HIDE_PROFILE_GRID_REELS
            ));
        }
        if (SettingsStatus.profileReelsSort) {
            ListPref reelsSort = new ListPref(context) {
                @Override
                protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
                    super.onSetInitialValue(restoreValue, defaultValue);
                    setEntries(new CharSequence[]{str("spookd_array_default"),
                            str("spookd_profile_reels_sort_keep"), str("spookd_profile_reels_sort_remove")});
                    setEntryValues(new CharSequence[]{"default", "keep", "remove"});
                }
            };
            reelsSort.setTitle(str("spookd_profile_reels_sort"));
            reelsSort.setDialogTitle(str("spookd_profile_reels_sort"));
            reelsSort.setSummary(str("spookd_profile_reels_sort_desc"));
            reelsSort.setKey(Settings.PROFILE_REELS_SORT.key);
            reelsSort.setDefaultValue(Settings.PROFILE_REELS_SORT.defaultValue);
            addPreference(reelsSort);
        }
        if (SettingsStatus.reorderHighlights) {
            addPreference(helper.switchPreference(
                    str("spookd_reorder_highlights"),
                    str("spookd_reorder_highlights_desc"),
                    Settings.REORDER_HIGHLIGHTS
            ));
        }
        if (SettingsStatus.disableAppHaptics) {
            addPreference(helper.switchPreference(
                    str("spookd_disable_app_haptics"),
                    str("spookd_disable_app_haptics_desc"),
                    Settings.DISABLE_APP_HAPTICS
            ));
        }
        if (SettingsStatus.resumeReelsAfterScrolling) {
            addPreference(helper.switchPreference(
                    str("spookd_resume_reels_after_scrolling"),
                    str("spookd_resume_reels_after_scrolling_desc"),
                    Settings.RESUME_REELS_AFTER_SCROLLING
            ));
        }
        if (SettingsStatus.playbackSpeedControls) {
            addPreference(helper.switchPreference(
                    str("spookd_playback_speed_controls"),
                    str("spookd_playback_speed_controls_desc"),
                    Settings.PLAYBACK_SPEED_CONTROLS
            ));
        }
        if (SettingsStatus.defaultPlaybackSpeed) {
            addPreference(helper.listPreference(
                    str("spookd_default_playback_speed"),
                    str("spookd_default_playback_speed_desc"),
                    Settings.DEFAULT_PLAYBACK_SPEED
            ));
        }
        if (SettingsStatus.reelsPlayerOptions) {
            addPreference(helper.switchPreference(
                    str("spookd_reels_start_muted"),
                    str("spookd_reels_start_muted_desc"),
                    Settings.REELS_START_MUTED
            ));
            addPreference(helper.switchPreference(
                    str("spookd_reels_stop_looping"),
                    str("spookd_reels_stop_looping_desc"),
                    Settings.REELS_STOP_LOOPING
            ));
            addPreference(helper.switchPreference(
                    str("spookd_reels_tap_to_pause"),
                    str("spookd_reels_tap_to_pause_desc"),
                    Settings.REELS_TAP_TO_PAUSE
            ));
            addPreference(helper.switchPreference(
                    str("spookd_expanded_feed_videos_muted"),
                    str("spookd_expanded_feed_videos_muted_desc"),
                    Settings.EXPANDED_FEED_VIDEOS_MUTED
            ));
        }
        if (SettingsStatus.reelsAutoScroll) {
            addPreference(helper.listPreference(
                    str("spookd_reels_auto_scroll"),
                    str("spookd_reels_auto_scroll_desc"),
                    Settings.REELS_AUTO_SCROLL
            ));
        }
        if (SettingsStatus.reelsHoldToPause) {
            addPreference(helper.switchPreference(
                    str("spookd_reels_hold_to_pause"),
                    str("spookd_reels_hold_to_pause_desc"),
                    Settings.REELS_HOLD_TO_PAUSE
            ));
        }
        // Instagram's PiP view needs Android 15; the switch does nothing below it.
        if (SettingsStatus.pipForVideos && Build.VERSION.SDK_INT >= 35) {
            addPreference(helper.switchPreference(
                    str("spookd_pip_for_videos"),
                    str("spookd_pip_for_videos_desc"),
                    Settings.PIP_FOR_VIDEOS
            ));
        }
        if (SettingsStatus.keepOtherAudio) {
            addPreference(helper.switchPreference(
                    str("spookd_keep_other_audio"),
                    str("spookd_keep_other_audio_desc"),
                    Settings.KEEP_OTHER_AUDIO
            ));
        }
        if (SettingsStatus.improveImageViewing) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_improve_image_viewing"),
                            str("spookd_improve_image_viewing_desc"),
                            Settings.IMPROVE_IMAGE_VIEWING
                    )
            );
        }
        if (SettingsStatus.copyCommentButton) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_copy_comment"),
                            str("spookd_copy_comment_desc"),
                            Settings.COMMENT_COPY_BUTTON
                    )
            );
        }
        if (SettingsStatus.removeEmptyBottomSpace) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_remove_empty_bottom_space"),
                            "",
                            Settings.REMOVE_EMPTY_BOTTOM_SPACE
                    )
            );
        }
        if (SettingsStatus.confirmationsSection()) {
            PreferenceCategory confirmations = addCategory(str("spookd_category_confirmations"));
            if (SettingsStatus.confirmFeedLike || SettingsStatus.confirmReelLike) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_like"), str("spookd_confirm_like_desc"), Settings.CONFIRM_LIKE));
            }
            if (SettingsStatus.confirmFollow) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_follow"), str("spookd_confirm_follow_desc"), Settings.CONFIRM_FOLLOW));
            }
            if (SettingsStatus.confirmReelRepost || SettingsStatus.confirmFeedRepost) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_repost"), str("spookd_confirm_repost_desc"), Settings.CONFIRM_REPOST));
            }
            if (SettingsStatus.confirmComment || SettingsStatus.confirmCommentSheet) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_comment"), str("spookd_confirm_comment_desc"), Settings.CONFIRM_COMMENT));
            }
            if (SettingsStatus.confirmUnfollow) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_unfollow"), str("spookd_confirm_unfollow_desc"), Settings.CONFIRM_UNFOLLOW));
            }
            if (SettingsStatus.confirmStoryLike || SettingsStatus.confirmStoryReaction) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_story_like"), str("spookd_confirm_story_like_desc"), Settings.CONFIRM_STORY_LIKE));
            }
            if (SettingsStatus.confirmCall) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_call"), str("spookd_confirm_call_desc"), Settings.CONFIRM_CALL));
            }
            if (SettingsStatus.confirmFollowRequest) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_follow_request"), str("spookd_confirm_follow_request_desc"), Settings.CONFIRM_FOLLOW_REQUEST));
            }
            if (SettingsStatus.confirmRefresh) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_refresh"), str("spookd_confirm_refresh_desc"), Settings.CONFIRM_REFRESH));
            }
            if (SettingsStatus.confirmCommentLike) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_comment_like"), str("spookd_confirm_comment_like_desc"), Settings.CONFIRM_COMMENT_LIKE));
            }
            if (SettingsStatus.confirmDisappearingMessages) {
                addPreference(confirmations, helper.switchPreference(
                        str("spookd_confirm_dm_actions"), str("spookd_confirm_dm_actions_desc"), Settings.CONFIRM_DM_ACTIONS));
            }
        }
        if (SettingsStatus.refreshControls) {
            PreferenceCategory refresh = addCategory(str("spookd_category_refresh"));
            addPreference(refresh, helper.switchPreference(
                    str("spookd_disable_home_tab_refresh"), str("spookd_disable_home_tab_refresh_desc"), Settings.DISABLE_HOME_TAB_REFRESH));
            addPreference(refresh, helper.switchPreference(
                    str("spookd_disable_home_background_refresh"), str("spookd_disable_home_background_refresh_desc"),
                    Settings.DISABLE_HOME_BACKGROUND_REFRESH));
            addPreference(refresh, helper.switchPreference(
                    str("spookd_disable_reels_tab_refresh"), str("spookd_disable_reels_tab_refresh_desc"), Settings.DISABLE_REELS_TAB_REFRESH));
        }
    }

    public void buildInstantsSection() {
        if (SettingsStatus.instantsDownload) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_instants_download"),
                            str("spookd_instants_download_desc"),
                            Settings.INSTANTS_DOWNLOAD
                    )
            );
            addPreference(
                    helper.buttonPreference(
                            str("spookd_view_saved_instants"),
                            "",
                            "spookd_view_saved_instants"
                    )
            );
        }
        if (SettingsStatus.instantsSeen) {
            addPreference(helper.switchPreference(
                    str("spookd_instants_dont_mark_seen"), str("spookd_instants_dont_mark_seen_desc"), Settings.INSTANTS_DONT_MARK_SEEN));
            Preference always = helper.buttonPreference(
                    str("spookd_instants_always_seen_users"), str("spookd_instants_always_seen_users_desc"), "spookd_instants_always_seen_users");
            always.setOnPreferenceClickListener(preference -> {
                InstantsMenu.showUserList(preference.getContext(), true);
                return true;
            });
            addPreference(always);
            Preference never = helper.buttonPreference(
                    str("spookd_instants_never_seen_users"), str("spookd_instants_never_seen_users_desc"), "spookd_instants_never_seen_users");
            never.setOnPreferenceClickListener(preference -> {
                InstantsMenu.showUserList(preference.getContext(), false);
                return true;
            });
            addPreference(never);
        }
        if (SettingsStatus.disableInstantsCreation) {
            addPreference(helper.switchPreference(
                    str("spookd_disable_instants_creation"), str("spookd_disable_instants_creation_desc"), Settings.DISABLE_INSTANTS_CREATION));
        }
        if (SettingsStatus.confirmInstantVideo) {
            addPreference(helper.switchPreference(
                    str("spookd_confirm_instant_video"), str("spookd_confirm_instant_video_desc"), Settings.CONFIRM_INSTANT_VIDEO));
        }
        if (SettingsStatus.confirmInstantReaction) {
            addPreference(helper.switchPreference(
                    str("spookd_confirm_instant_reaction"), str("spookd_confirm_instant_reaction_desc"), Settings.CONFIRM_INSTANT_REACTION));
        }
    }

    public void buildDownloadSection() {
        if (!SettingsStatus.downloadSection()) return;

        // PreferenceCategory category= addCategory(str("spookd_category_download_media"));

        addPreference(
                helper.switchPreference(
                        str("spookd_enable_download"),
                        "",
                        Settings.ENABLE_DOWNLOAD
                )
        );

        if (SettingsStatus.feedDownloadButton || SettingsStatus.reelDownloadButton
                || SettingsStatus.storyDownloadButton) {
            addPreference(
                    helper.availableWhen(
                            new DownloadButtonPreference(context),
                            () -> SharedPref.getBooleanPref(Settings.ENABLE_DOWNLOAD)
                    )
            );
        }
        if (SettingsStatus.audioPageDownloadButton) {
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_audio_page_download_button"),
                                    str("spookd_audio_page_download_button_desc"),
                                    Settings.AUDIO_PAGE_DOWNLOAD_BUTTON
                            ),
                            () -> SharedPref.getBooleanPref(Settings.ENABLE_DOWNLOAD)
                    )
            );
        }
        if (SettingsStatus.saveMediaCommentButton) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_save_media_comment"),
                            str("spookd_save_media_comment_desc"),
                            Settings.COMMENT_SAVE_MEDIA_BUTTON
                    )
            );
        }

        addPreference(
                helper.switchPreference(
                        str("spookd_enable_direct_download"),
                        str("spookd_enable_direct_download_desc"),
                        Settings.ENABLE_DIRECT_DOWNLOAD
                )
        );

        addPreference(
                helper.switchPreference(
                        str("spookd_download_username_folder"),
                        str("spookd_download_username_folder_desc"),
                        Settings.DOWNLOAD_USERNAME_FOLDER
                )
        );

        addPreference(
                helper.switchPreference(
                        str("spookd_prefer_dash_format"),
                        str("spookd_prefer_dash_format_desc"),
                        Settings.PREFER_DASH_FORMAT
                )
        );

        addPreference(
                helper.availableWhen(
                        helper.switchPreference(
                                str("spookd_dash_audio_aac_lc"),
                                str("spookd_dash_audio_aac_lc_desc"),
                                Settings.DASH_AUDIO_AAC_LC
                        ),
                        () -> SharedPref.getBooleanPref(Settings.PREFER_DASH_FORMAT)
                )
        );

        addPreference(
                helper.switchPreference(
                        str("spookd_embed_download_metadata"),
                        str("spookd_embed_download_metadata_desc"),
                        Settings.EMBED_DOWNLOAD_METADATA
                )
        );

        addPreference(
                helper.listPreference(
                        str("spookd_download_conflict_policy"),
                        str("spookd_download_conflict_policy_desc"),
                        Settings.DOWNLOAD_CONFLICT_POLICY
                )
        );

        addPreference(
                helper.downloadFileNameTemplatePreference(
                        str("spookd_download_file_name_template"),
                        Pref.downloadFileNameTemplate(),
                        Settings.DOWNLOAD_FILE_NAME_TEMPLATE
                )
        );

        addPreference(
                helper.buttonPreference(
                        str("spookd_private_gallery"),
                        str("spookd_private_gallery_desc"),
                        "spookd_private_gallery"
                )
        );

        if (SettingsStatus.homeLongPressSettings) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_home_long_press_gallery"),
                            str("spookd_home_long_press_gallery_desc"),
                            Settings.HOME_LONG_PRESS_GALLERY
                    )
            );
        }

        if (SettingsStatus.galleryPicker) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_gallery_picker"),
                            str("spookd_gallery_picker_desc"),
                            Settings.GALLERY_PICKER
                    )
            );
        }

        addPreference(
                helper.buttonPreference(
                        str("spookd_download_history"),
                        str("spookd_download_history_desc"),
                        "spookd_download_history"
                )
        );

        String phoneStorageReason = Build.VERSION.SDK_INT < 30
                ? str("spookd_save_downloads_to_phone_storage_old_android")
                : Gallery.canOpen(context) ? null : str("spookd_save_downloads_to_phone_storage_no_lock");
        addPreference(
                helper.forcedSwitchPreference(
                        str("spookd_save_downloads_to_phone_storage"),
                        str("spookd_save_downloads_to_phone_storage_desc"),
                        Settings.SAVE_DOWNLOADS_TO_PHONE_STORAGE,
                        phoneStorageReason != null,
                        phoneStorageReason
                )
        );

        addPreference(galleryLimitPreference("spookd_gallery_max_age_days", "spookd_gallery_max_age_days_desc",
                Settings.GALLERY_MAX_AGE_DAYS,
                Pref::galleryMaxAgeDays));
        addPreference(galleryLimitPreference("spookd_gallery_max_size_gb", "spookd_gallery_max_size_gb_desc",
                Settings.GALLERY_MAX_SIZE_GB,
                () -> Pref.galleryMaxBytes() / 1_000_000_000L));

        addPreference(
                helper.buttonPreference(
                        str("spookd_download_set_path"),
                        StorageUtils.getCustomPathForDisplay(),
                        "spookd_download_set_path"
                )
        );

        if(SettingsStatus.downloadWithExternalDownloader) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_download_with_external_downloader"),
                            "",
                            Settings.DOWNLOAD_WITH_EXTERNAL_DOWNLOADER
                    )
            );

            addPreference(
                    helper.availableWhen(
                            helper.editTextPreference(
                                    str("spookd_external_downloader_package_name"),
                                    Pref.externalDownloaderPackageName(),
                                    Settings.EXTERNAL_DOWNLOADER_PACKAGE_NAME
                            ),
                            () -> SharedPref.getBooleanPref(Settings.DOWNLOAD_WITH_EXTERNAL_DOWNLOADER)
                    )
            );
        }
    }

    /** A cleanup limit whose summary shows the limit in force and that 0 turns it off, also after edits. */
    private Preference galleryLimitPreference(String titleKey, String summaryKey, StringSetting setting,
                                              LongSupplier limit) {
        Preference preference = helper.editTextNumPreference(str(titleKey),
                String.format(str(summaryKey), limit.getAsLong()), setting);
        preference.setOnPreferenceChangeListener((changed, value) -> {
            helper.setValue(changed, value);
            changed.setSummary(String.format(str(summaryKey), limit.getAsLong()));
            return true;
        });
        return preference;
    }

    public void buildActionBarSection() {

        addPreference(
                helper.multiSelectListPref(
                        str("spookd_action_bar_main_feed"),
                        "",
                        Settings.ACTION_BAR_MAIN_FEED
                )
        );

        addPreference(
                helper.multiSelectListPref(
                        str("spookd_action_bar_user_profile"),
                        "",
                        Settings.ACTION_BAR_USER_PROFILE
                )
        );

        addPreference(
                helper.multiSelectListPref(
                        str("spookd_action_bar_inbox"),
                        "",
                        Settings.ACTION_BAR_INBOX
                )
        );

        addPreference(
                helper.multiSelectListPref(
                        str("spookd_action_bar_chat"),
                        str("spookd_action_bar_chat_desc"),
                        Settings.ACTION_BAR_CHAT
                )
        );
    }

    public void filterContentSection() {
        if(!SettingsStatus.filterContentSection()) return;

        if(SettingsStatus.storyFilters) {
            PreferenceCategory category = addCategory(str("spookd_filter_story"));

            addPreference(
                    category,
                    helper.multiSelectListPref(
                            str("spookd_filter_story_types_to_hide"),
                            "",
                            Settings.FILTER_STORY_BY_TYPE
                    )
            );

            addPreference(
                    category,
                    helper.multiSelectListPref(
                            str("spookd_filter_story_user_types_to_hide"),
                            "",
                            Settings.FILTER_STORY_BY_USER_TYPE
                    )
            );

            addPreference(
                    category,
                    helper.editTextNumPreference(
                            str("spookd_filter_story_minimum_item_count"),
                            str("spookd_filter_story_minimum_item_count_desc"),
                            Settings.FILTER_STORY_MIN_STORY_ITEMS
                    )
            );

            addPreference(
                    category,
                    helper.editTextNumPreference(
                            str("spookd_filter_story_maximum_item_count"),
                            str("spookd_filter_story_maximum_item_count_desc"),
                            Settings.FILTER_STORY_MAX_STORY_ITEMS
                    )
            );
        }
    }

    public void buildNavigationSection() {
        if (SettingsStatus.swipeBetweenTabs) {
            addPreference(helper.listPreference(
                    str("spookd_swipe_between_tabs"),
                    str("spookd_swipe_between_tabs_desc"),
                    Settings.SWIPE_BETWEEN_TABS
            ));
        }
        if (SettingsStatus.floatingTabBar) {
            addPreference(helper.switchPreference(
                    str("spookd_floating_tab_bar"),
                    str("spookd_floating_tab_bar_desc"),
                    Settings.FLOATING_TAB_BAR
            ));
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_floating_tab_bar_hide_on_scroll"),
                                    str("spookd_floating_tab_bar_hide_on_scroll_desc"),
                                    Settings.FLOATING_TAB_BAR_HIDE_ON_SCROLL
                            ),
                            () -> SharedPref.getBooleanPref(Settings.FLOATING_TAB_BAR)
                    )
            );
            addPreference(
                    helper.availableWhen(
                            helper.switchPreference(
                                    str("spookd_floating_tab_bar_glass"),
                                    str("spookd_floating_tab_bar_glass_desc"),
                                    Settings.FLOATING_TAB_BAR_GLASS
                            ),
                            () -> SharedPref.getBooleanPref(Settings.FLOATING_TAB_BAR)
                    )
            );
        }
        for (NavigationBarPatch.Tab tab : NavigationBarPatch.Tab.values()) {
            boolean home = tab == NavigationBarPatch.Tab.HOME;
            if (!(home ? SettingsStatus.homeLongPressSettings : SettingsStatus.tabShortcuts)) continue;
            StringSetting setting = TabShortcuts.setting(tab);
            ListPref shortcut = new ListPref(context) {
                @Override
                protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
                    super.onSetInitialValue(restoreValue, defaultValue);
                    setEntries(TabShortcuts.choices(true));
                    setEntryValues(TabShortcuts.choices(false));
                }
            };
            String title = str("spookd_tab_shortcut_title", str(tab.labelName()));
            shortcut.setTitle(title);
            shortcut.setDialogTitle(title);
            shortcut.setSummary(home ? str("spookd_tab_shortcut_home_summary") : "%s");
            shortcut.setKey(setting.key);
            shortcut.setDefaultValue(setting.defaultValue);
            addPreference(shortcut);
        }
        if (!(SettingsStatus.hideNavigationButtons)) return;

        addPreference(
                helper.navigationBarPreference(
                        str("spookd_navigation_tabs_title"),
                        str("spookd_navigation_tabs_summary")
                )
        );

        addPreference(
                helper.navigationStartupPreference(
                        str("spookd_navigation_startup_tab"),
                        str("spookd_navigation_startup_tab_summary")
                )
        );
    }

    public void aboutSection(TreeMap<String, Boolean> flags) {

        String appVersionText = String.format(str("spookd_app_version"), Utils.getAppVersionName());
        String patchVersionText = String.format(str("spookd_patch_version"), Utils.getPatchesReleaseVersion());

        addPreference(
                helper.buttonPreference(
                        appVersionText,
                        "",
                        Constants.SPOOKD_APP_VERSION_PREFERENCE
                )
        );

        addPreference(
                helper.buttonPreference(
                        patchVersionText,
                        "",
                        Constants.SPOOKD_PATCH_VERSION_PREFERENCE
                )
        );

        addPreference(
                helper.buttonPreference(
                        str("spookd_export_pref"),
                        "",
                        "spookd_export_pref"
                )
        );

        addPreference(
                helper.buttonPreference(
                        str("spookd_import_pref"),
                        "",
                        "spookd_import_pref"
                )
        );

        addPreference(
                helper.buttonPreference(
                        str("spookd_reset_pref"),
                        "",
                        "spookd_reset_pref"
                )
        );

        // Device-wide, so it is kept outside the per-account settings the framework would write.
        SwitchPref disableAll = new SwitchPref(context);
        disableAll.setTitle(str("spookd_disable_all_features"));
        disableAll.setSummary(str("spookd_disable_all_features_desc"));
        disableAll.setKey(SafeStartup.DISABLE_ALL_KEY);
        disableAll.setPersistent(false);
        disableAll.setChecked(SafeStartup.disableAllStored());
        disableAll.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = Boolean.TRUE.equals(newValue);
            boolean previous = disableAll.isChecked();
            SafeStartup.runSave(() -> {
                boolean saved = SafeStartup.setDisableAll(enabled);
                if (saved) SettingsRestart.markChanged(SafeStartup.DISABLE_ALL_KEY, RestartPolicy.REQUIRED, previous, enabled);
                Utils.runOnMainThread(() -> {
                    if (saved) disableAll.setChecked(enabled);
                    else Utils.showToastShort(str("spookd_setting_save_failed"));
                });
            });
            // Rejected for now; the switch flips once the value is on disk.
            return false;
        });
        addPreference(disableAll);

        addPreference(
                helper.switchPreference(
                        str("spookd_open_settings_on_launch"),
                        str("spookd_open_settings_on_launch_desc"),
                        Settings.OPEN_SETTINGS_ON_LAUNCH
                )
        );

        addPreference(
                helper.switchPreference(
                        str("spookd_show_new_labels"),
                        str("spookd_show_new_labels_desc"),
                        Settings.SHOW_NEW_LABELS
                )
        );

        Preference storage = helper.buttonPreference(
                str("spookd_storage"),
                str("spookd_storage_desc"),
                "spookd_storage"
        );
        storage.setOnPreferenceClickListener(preference -> {
            StorageScreen.show(preference.getContext());
            return true;
        });
        addPreference(storage);

        ListPref autoClearCache = new ListPref(context) {
            @Override
            protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
                super.onSetInitialValue(restoreValue, defaultValue);
                String[] megabytes = {"100", "250", "500", "1000"};
                CharSequence[] entries = new CharSequence[megabytes.length + 1];
                CharSequence[] values = new CharSequence[megabytes.length + 1];
                entries[0] = str("spookd_auto_clear_cache_off");
                values[0] = "0";
                for (int i = 0; i < megabytes.length; i++) {
                    entries[i + 1] = String.format(str("spookd_auto_clear_cache_over"), Integer.parseInt(megabytes[i]));
                    values[i + 1] = megabytes[i];
                }
                setEntries(entries);
                setEntryValues(values);
            }
        };
        autoClearCache.setTitle(str("spookd_auto_clear_cache"));
        autoClearCache.setDialogTitle(str("spookd_auto_clear_cache"));
        autoClearCache.setSummary("%s");
        autoClearCache.setKey(Settings.AUTO_CLEAR_CACHE_MB.key);
        autoClearCache.setDefaultValue(Settings.AUTO_CLEAR_CACHE_MB.defaultValue);
        addPreference(autoClearCache);

        addPreference(
                helper.switchPreference(
                        str("spookd_debug"),
                        str("spookd_debug_desc"),
                        Settings.SPOOKD_DEBUG
                )
        );

        addPreference(newIssueRow(true, "spookd_report_bug"));
        addPreference(newIssueRow(false, "spookd_request_feature"));

        PreferenceCategory category = addCategory(str("spookd_patch_info_title"));
        category.setKey(Constants.SPOOKD_PATCH_INFO_PREFERENCE_CATEGORY);
        String enabledStr = str("spookd_patch_enabled");
        String disabledStr = str("spookd_patch_disabled");

        for (Map.Entry<String, Boolean> entry : flags.entrySet()) {
            String resName = entry.getKey();
            boolean sts = (boolean) entry.getValue();
            String status = sts ? enabledStr : disabledStr;

            addPreference(category,
                    helper.buttonPreference(
                            resName,
                            status,
                            resName
                    )
            );
        }

    }

    public void storySection() {
        if (!SettingsStatus.storySection()) return;

        if (SettingsStatus.hideStoryFollowButton) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_hide_story_follow_button"),
                            str("spookd_hide_story_follow_button_desc"),
                            Settings.HIDE_STORY_FOLLOW_BUTTON
                    )
            );
        }
        if (SettingsStatus.viewStoryMentions) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_view_story_mentions"),
                            "",
                            Settings.VIEW_STORY_MENTIONS
                    )
            );
        }
        if (SettingsStatus.disableStoryFlipping) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_disable_story_flipping"),
                            str("spookd_disable_story_flipping_desc"),
                            Settings.DISABLE_STORY_FLIPPING
                    )
            );
        }
        if (SettingsStatus.loopStory) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_loop_story"),
                            str("spookd_loop_story_desc"),
                            Settings.LOOP_STORY
                    )
            );
        }
        if (SettingsStatus.storyMute) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_mute_stories"),
                            str("spookd_mute_stories_desc"),
                            Settings.MUTE_STORIES
                    )
            );
        }
        if (SettingsStatus.customiseStoryTimestamp) {
            addPreference(
                    helper.listPreference(
                            str("spookd_customise_story_timestamp"),
                            str("spookd_customise_story_timestamp_desc"),
                            Settings.CUSTOMISE_STORY_TIMESTAMP
                    )
            );
        }
        if (SettingsStatus.customiseStoryRingSize) {
            addPreference(
                    helper.editTextNumPreference(
                            str("spookd_customise_story_ring_size"),
                            str("spookd_customise_story_ring_size_desc"),
                            Settings.CUSTOMISE_STORY_RING_SIZE
                    )
            );
        }
        if (SettingsStatus.storyAutoSave) {
            addPreference(
                    helper.listPreference(
                            str("spookd_story_auto_save"),
                            "%s",
                            Settings.STORY_AUTO_SAVE
                    )
            );
            Preference storyAutoSaveUsers = helper.buttonPreference(
                    str("spookd_story_auto_save_users"),
                    str("spookd_story_auto_save_users_desc"),
                    "spookd_story_auto_save_users"
            );
            storyAutoSaveUsers.setOnPreferenceClickListener(preference -> {
                StoryAutoSave.showList(preference.getContext());
                return true;
            });
            addPreference(storyAutoSaveUsers);
        }
        if (SettingsStatus.searchStoryViewers) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_search_story_viewers"),
                            str("spookd_search_story_viewers_desc"),
                            Settings.SEARCH_STORY_VIEWERS
                    )
            );
        }
        if (SettingsStatus.copyStoryLink) {
            addPreference(
                    helper.switchPreference(
                            str("spookd_copy_story_link"),
                            str("spookd_copy_story_link_desc"),
                            Settings.COPY_STORY_LINK
                    )
            );
        }
    }

    public void buildSettingsPage() {
        if (SettingsStatus.adsSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_ads"),
                            "",
                            Constants.SPOOKD_FRAGMENT_ADS
                    )
            );
        }

        if (SettingsStatus.filterContentSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_filter_content"),
                            "",
                            Constants.SPOOKD_FRAGMENT_FILTER_CONTENT
                    )
            );
        }

        if (SettingsStatus.ghostSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_ghost"),
                            "",
                            Constants.SPOOKD_FRAGMENT_GHOST
                    )
            );
        }

        if (SettingsStatus.dmSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_dm"),
                            "",
                            Constants.SPOOKD_FRAGMENT_DM
                    )
            );
        }

        if (SettingsStatus.storySection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_story"),
                            "",
                            Constants.SPOOKD_FRAGMENT_STORY
                    )
            );
        }

        if (SettingsStatus.linksSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_links"),
                            "",
                            Constants.SPOOKD_FRAGMENT_LINKS
                    )
            );
        }

        if (SettingsStatus.distractionFreeSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_distraction_free"),
                            "",
                            Constants.SPOOKD_FRAGMENT_DISTRACTION_FREE
                    )
            );
        }

        if (SettingsStatus.miscSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_misc"),
                            "",
                            Constants.SPOOKD_FRAGMENT_MISC
                    )
            );
        }

        if (SettingsStatus.downloadSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_downloads"),
                            "",
                            Constants.SPOOKD_FRAGMENT_DOWNLOAD_MEDIA
                    )
            );
        }

        if (SettingsStatus.instantsSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_instants_title"),
                            "",
                            Constants.SPOOKD_FRAGMENT_INSTANTS
                    )
            );
        }

        addPreference(
                helper.categoryPreference(
                        str("spookd_category_action_bar"),
                        "",
                        Constants.SPOOKD_FRAGMENT_ACTION_BAR
                )
        );

        if (SettingsStatus.hideNavigationButtons || SettingsStatus.floatingTabBar || SettingsStatus.swipeBetweenTabs
                || SettingsStatus.homeLongPressSettings){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_navigation_tabs"),
                            "",
                            Constants.SPOOKD_FRAGMENT_NAV_BTNS
                    )
            );
        }

        if (SettingsStatus.developerOptionsSection()){
            addPreference(
                    helper.categoryPreference(
                            str("spookd_category_dev_options"),
                            "",
                            Constants.SPOOKD_FRAGMENT_DEV_OPTIONS
                    )
            );
        }

        addPreference(
                helper.categoryPreference(
                        str("spookd_category_about"),
                        "",
                        Constants.SPOOKD_FRAGMENT_ABOUT
                )
        );

    }

    /** A row that opens the project's new-issue form in the browser; the link sends the details to GitHub, but no issue is created until the user submits it. */
    private Preference newIssueRow(boolean bug, String key) {
        Preference row = helper.buttonPreference(str(key), str(key + "_desc"), key);
        row.setOnPreferenceClickListener(preference -> {
            Uri uri = newIssueUri(bug, Build.MANUFACTURER + " " + Build.MODEL,
                    Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                    Utils.getAppVersionName(), Utils.getPatchesReleaseVersion());
            try {
                preference.getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e) {
                Utils.showToastShort(str("spookd_no_browser"));
            }
            return true;
        });
        return row;
    }

    /** The new-issue form of the project's bug or feature template, prefilled with the device and versions. */
    static Uri newIssueUri(boolean bug, String device, String android, String instagram, String patches) {
        String about = "Device: " + device + "\nAndroid: " + android
                + "\nInstagram: " + instagram + "\nPatches: " + patches;
        Uri.Builder uri = Uri.parse("https://github.com/hawkff/spookd/issues/new").buildUpon();
        // Blank issues are off in the project, so the text goes into the template's fields by their ids.
        if (bug) {
            return uri.appendQueryParameter("template", "bug_instagram.yml")
                    .appendQueryParameter("problem", "Expected:\nActual:\nSteps:\n\n" + about)
                    .appendQueryParameter("app-version", instagram)
                    .appendQueryParameter("patches-version", patches)
                    .build();
        }
        return uri.appendQueryParameter("template", "feature_instagram.yml")
                .appendQueryParameter("feature", "\n\n" + about)
                .build();
    }

    //end
}
