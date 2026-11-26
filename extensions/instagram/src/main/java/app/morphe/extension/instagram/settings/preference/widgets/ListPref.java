/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.instagram.settings.preference.widgets;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.preference.ListPreference;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.shared.ResourceUtils;
import android.preference.Preference;
import app.morphe.extension.instagram.settings.preference.Helper;

public class ListPref extends ListPreference {
    private static Helper helper;

    public ListPref(Context context) {
        super(InstagramPreferenceStyle.dialogContext(context));
        helper = new Helper(context);
        init();
    }

    public ListPref(Context context, AttributeSet attrs) {
        super(context, attrs);
        helper = new Helper(context);
        init();
    }

    public ListPref(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        helper = new Helper(context);
        init();
    }

    private void init() {
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                helper.setValue(preference,newValue);
                return true;
            }
        });
    }

    @Override
    protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        super.onSetInitialValue(restoreValue, defaultValue);
        String key = getKey();

        CharSequence[] entries = new CharSequence[]{};
        CharSequence[] entriesValues = new CharSequence[]{};
        // Flag codes doesnt have _ in them
        if(key.contains("_")){
            if (key == Settings.CUSTOMISE_STORY_TIMESTAMP.key) {
                entries = ResourceUtils.getStringArray("spookd_array_customise_story_timestamp");
                entriesValues = ResourceUtils.getStringArray("spookd_array_customise_story_timestamp_val");
            } else if (key == Settings.HIDDEN_CHATS_TAPS.key) {
                entries = ResourceUtils.getStringArray("spookd_array_hidden_chats_taps");
                entriesValues = entries;
            } else if (Settings.DOWNLOAD_CONFLICT_POLICY.key.equals(key)) {
                entries = ResourceUtils.getStringArray("spookd_array_download_conflict_policy");
                entriesValues = ResourceUtils.getStringArray("spookd_array_download_conflict_policy_val");
            } else if (key == Settings.CHANGE_LIKE_ANIMATION.key) {
                entries = ResourceUtils.getStringArray("spookd_array_change_like_animation");
                entriesValues = ResourceUtils.getStringArray("spookd_array_change_like_animation_val");
            } else if (Settings.DEFAULT_PLAYBACK_SPEED.key.equals(key)) {
                entries = new CharSequence[]{"1x", "1.25x", "1.5x", "1.75x", "2x"};
                entriesValues = new CharSequence[]{"1", "1.25", "1.5", "1.75", "2"};
            } else if (Settings.STORY_GHOST_MODE.key.equals(key)) {
                entries = new CharSequence[]{
                        str("spookd_story_ghost_mode_everyone"),
                        str("spookd_story_ghost_mode_except_listed"),
                        str("spookd_story_ghost_mode_only_listed")
                };
                entriesValues = new CharSequence[]{"everyone", "except_listed", "only_listed"};
            } else if (Settings.STORY_AUTO_SAVE.key.equals(key)) {
                entries = new CharSequence[]{
                        str("spookd_story_auto_save_off"),
                        str("spookd_story_ghost_mode_everyone"),
                        str("spookd_story_auto_save_listed")
                };
                entriesValues = new CharSequence[]{"off", "everyone", "listed"};
            } else if (Settings.REELS_AUTO_SCROLL.key.equals(key)) {
                entries = new CharSequence[]{
                        str("spookd_array_default"), str("spookd_reels_auto_scroll_on"), str("spookd_reels_auto_scroll_off")
                };
                entriesValues = new CharSequence[]{"default", "on", "off"};
            }
        } else {
            entries = ResourceUtils.getStringArray("spookd_array_recmd_flag_states");
            entriesValues = ResourceUtils.getStringArray("spookd_array_recmd_flag_states_val");
        }

        setEntries(entries);
        setEntryValues(entriesValues);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        return InstagramPreferenceStyle.createPreferenceView(getContext(), InstagramPreferenceStyle.TRAILING_CHEVRON);
    }

    @Override
    protected void onBindView(View view) {
        InstagramPreferenceStyle.bindText(this, view);
    }
}
