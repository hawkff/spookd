/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.AlertDialog;
import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.StringSetting;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;

import com.instagram.common.session.UserSession;

/**
 * A list of story owners: usernames by user id, oldest first. It lives in the settings file,
 * so per-account settings give every account its own list.
 */
final class StoryUserList {
    static final StoryUserList GHOST = new StoryUserList(Settings.STORY_GHOST_USERS,
            "spookd_story_ghost_users", "spookd_story_ghost_remove_confirm");
    static final StoryUserList AUTO_SAVE = new StoryUserList(Settings.STORY_AUTO_SAVE_USERS,
            "spookd_story_auto_save_users", "spookd_story_auto_save_remove_confirm");

    private final StringSetting setting;
    private final String titleKey;
    private final String removeConfirmKey;
    private String cachedJson;
    private Map<String, String> cachedUsers = Collections.emptyMap();

    private StoryUserList(StringSetting setting, String titleKey, String removeConfirmKey) {
        this.setting = setting;
        this.titleKey = titleKey;
        this.removeConfirmKey = removeConfirmKey;
    }

    /** The user id of a story media's owner, which the lists are keyed by; null when unknown. */
    static String ownerId(Object media, Object session) throws Exception {
        if (media == null || !(session instanceof UserSession)) return null;
        UserData owner = new MediaData(media, (UserSession) session).getUserData();
        return owner == null ? null : userId(owner.getUserId());
    }

    /** {@code id}, or null when it is missing or empty. */
    static String userId(String id) {
        return id == null || id.isEmpty() ? null : id;
    }

    /** Parsed again only after the stored list changes, so story paths stay in memory. */
    synchronized Map<String, String> all() {
        String json = SharedPref.getStringPref(setting);
        if (!json.equals(cachedJson)) {
            cachedUsers = parse(json);
            cachedJson = json;
        }
        return cachedUsers;
    }

    /** False when the user is already listed. */
    synchronized boolean add(String userId, String username) {
        String json = withUser(SharedPref.getStringPref(setting), userId, username);
        if (json == null) return false;
        SharedPref.setStringPref(setting.key, json);
        return true;
    }

    synchronized void remove(String userId) {
        SharedPref.setStringPref(setting.key, withoutUser(SharedPref.getStringPref(setting), userId));
    }

    /** The list screen, on the main thread: tap a username to remove it. */
    void showList(Context context) {
        Map<String, String> users = all();
        List<String> ids = new ArrayList<>(users.keySet());
        CharSequence[] names = new CharSequence[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = "@" + users.get(ids.get(i));

        AlertDialog.Builder dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(str(titleKey))
                .setNegativeButton(str("spookd_close"), null);
        if (ids.isEmpty()) {
            dialog.setMessage(str("spookd_story_ghost_users_empty"));
        } else {
            dialog.setItems(names, (d, which) -> confirmRemove(context, ids.get(which), names[which]));
        }
        dialog.show();
    }

    private void confirmRemove(Context context, String userId, CharSequence name) {
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setMessage(str(removeConfirmKey, name))
                .setNegativeButton(str("spookd_cancel"), (d, which) -> showList(context))
                .setPositiveButton(str("spookd_story_ghost_remove"), (d, which) -> {
                    remove(userId);
                    showList(context);
                })
                .show();
    }

    static Map<String, String> parse(String json) {
        Map<String, String> users = new LinkedHashMap<>();
        try {
            JSONObject saved = new JSONObject(json);
            for (Iterator<String> ids = saved.keys(); ids.hasNext(); ) {
                String id = ids.next();
                users.put(id, saved.optString(id, ""));
            }
        } catch (JSONException ignored) {
        }
        return Collections.unmodifiableMap(users);
    }

    /** The list with the user added at the end, or null when the user is already listed. */
    static String withUser(String json, String userId, String username) {
        Map<String, String> users = new LinkedHashMap<>(parse(json));
        if (users.containsKey(userId)) return null;
        users.put(userId, username);
        return new JSONObject(users).toString();
    }

    static String withoutUser(String json, String userId) {
        Map<String, String> users = new LinkedHashMap<>(parse(json));
        users.remove(userId);
        return new JSONObject(users).toString();
    }
}
