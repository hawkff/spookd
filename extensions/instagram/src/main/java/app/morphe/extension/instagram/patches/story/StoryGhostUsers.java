/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.Settings;

/**
 * The story ghost list: usernames by user id, oldest first. It lives in the settings file,
 * so per-account settings give every account its own list.
 */
final class StoryGhostUsers {
    private static String cachedJson;
    private static Map<String, String> cachedUsers = Collections.emptyMap();

    private StoryGhostUsers() {
    }

    /** Parsed again only after the stored list changes, so story paths stay in memory. */
    static synchronized Map<String, String> all() {
        String json = SharedPref.getStringPref(Settings.STORY_GHOST_USERS);
        if (!json.equals(cachedJson)) {
            cachedUsers = parse(json);
            cachedJson = json;
        }
        return cachedUsers;
    }

    /** False when the user is already listed. */
    static synchronized boolean add(String userId, String username) {
        String json = withUser(SharedPref.getStringPref(Settings.STORY_GHOST_USERS), userId, username);
        if (json == null) return false;
        SharedPref.setStringPref(Settings.STORY_GHOST_USERS.key, json);
        return true;
    }

    static synchronized void remove(String userId) {
        SharedPref.setStringPref(
                Settings.STORY_GHOST_USERS.key,
                withoutUser(SharedPref.getStringPref(Settings.STORY_GHOST_USERS), userId)
        );
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
