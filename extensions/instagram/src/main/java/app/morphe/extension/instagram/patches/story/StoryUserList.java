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
 * A list of story owners: usernames by user id, oldest first; or of DM chats: titles by chat id.
 * It lives in the settings file, so per-account settings give every account its own list. The chat list is also
 * stored under the account id, because an account seen for the first time starts with a copy of the settings file.
 */
public final class StoryUserList {
    static final StoryUserList GHOST = new StoryUserList(Settings.STORY_GHOST_USERS,
            "spookd_story_ghost_users", "spookd_story_ghost_remove_confirm", "@", "spookd_story_ghost_users_empty", false);
    static final StoryUserList AUTO_SAVE = new StoryUserList(Settings.STORY_AUTO_SAVE_USERS,
            "spookd_story_auto_save_users", "spookd_story_auto_save_remove_confirm", "@", "spookd_story_ghost_users_empty", false);
    public static final StoryUserList DM_GHOST = new StoryUserList(Settings.DM_GHOST_CHATS,
            "spookd_dm_ghost_chats", "spookd_dm_ghost_remove_confirm", "", "spookd_dm_ghost_chats_empty", true);

    private final StringSetting setting;
    private final String titleKey;
    private final String removeConfirmKey;
    private final String namePrefix;
    private final String emptyKey;
    private final boolean perAccount;
    private String cachedJson;
    private Map<String, String> cachedUsers = Collections.emptyMap();

    private StoryUserList(StringSetting setting, String titleKey, String removeConfirmKey, String namePrefix,
                          String emptyKey, boolean perAccount) {
        this.setting = setting;
        this.titleKey = titleKey;
        this.removeConfirmKey = removeConfirmKey;
        this.namePrefix = namePrefix;
        this.emptyKey = emptyKey;
        this.perAccount = perAccount;
    }

    /** The stored key of a list: per account id when {@code perAccount}; null for an unknown account. */
    static String storageKey(String key, boolean perAccount, String account) {
        if (!perAccount) return key;
        return account == null || account.isEmpty() ? null : key + "_" + account;
    }

    private String read(String key) {
        return key == null ? setting.defaultValue : SharedPref.getStringPref(key, setting.defaultValue);
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
        return all(null);
    }

    public synchronized Map<String, String> all(String account) {
        String json = read(storageKey(setting.key, perAccount, account));
        if (!json.equals(cachedJson)) {
            cachedUsers = parse(json);
            cachedJson = json;
        }
        return cachedUsers;
    }

    /** False when the user is already listed. */
    synchronized boolean add(String userId, String username) {
        return add(null, userId, username);
    }

    /** False when the user is already listed or the account is unknown. */
    public synchronized boolean add(String account, String userId, String username) {
        String key = storageKey(setting.key, perAccount, account);
        if (key == null) return false;
        String json = withUser(read(key), userId, username);
        if (json == null) return false;
        SharedPref.setStringPref(key, json);
        return true;
    }

    synchronized void remove(String userId) {
        remove(null, userId);
    }

    public synchronized void remove(String account, String userId) {
        String key = storageKey(setting.key, perAccount, account);
        if (key != null) SharedPref.setStringPref(key, withoutUser(read(key), userId));
    }

    void showList(Context context) {
        showList(context, null);
    }

    /** The list screen, on the main thread: tap a username to remove it. */
    public void showList(Context context, String account) {
        Map<String, String> users = all(account);
        List<String> ids = new ArrayList<>(users.keySet());
        CharSequence[] names = new CharSequence[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = namePrefix + users.get(ids.get(i));

        AlertDialog.Builder dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(str(titleKey))
                .setNegativeButton(str("spookd_close"), null);
        if (ids.isEmpty()) {
            dialog.setMessage(str(emptyKey));
        } else {
            dialog.setItems(names, (d, which) -> confirmRemove(context, account, ids.get(which), names[which]));
        }
        dialog.show();
    }

    private void confirmRemove(Context context, String account, String userId, CharSequence name) {
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setMessage(str(removeConfirmKey, name))
                .setNegativeButton(str("spookd_cancel"), (d, which) -> showList(context, account))
                .setPositiveButton(str("spookd_story_ghost_remove"), (d, which) -> {
                    remove(account, userId);
                    showList(context, account);
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
