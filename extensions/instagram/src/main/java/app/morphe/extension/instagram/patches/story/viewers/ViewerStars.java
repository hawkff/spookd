package app.morphe.extension.instagram.patches.story.viewers;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Starred story viewers: a JSON object of user id to username, stored under the account id, because
 * an account seen for the first time starts with a copy of the previous account's settings file.
 */
final class ViewerStars {
    private static final String KEY_PREFIX = "story_viewer_stars_";

    private ViewerStars() {
    }

    /** The settings key of this account's stars, or null for an unknown account. */
    static String key(String account) {
        return account == null || account.isEmpty() ? null : KEY_PREFIX + account;
    }

    static Set<String> ids(String json) {
        Set<String> ids = new HashSet<>();
        JSONObject stars = parse(json);
        for (Iterator<String> keys = stars.keys(); keys.hasNext(); ) ids.add(keys.next());
        return ids;
    }

    /** The stars with {@code id} removed if it was starred, else added. */
    static String toggled(String json, String id, String username) {
        JSONObject stars = parse(json);
        if (stars.has(id)) {
            stars.remove(id);
        } else {
            try {
                stars.put(id, username == null ? "" : username);
            } catch (JSONException ignored) {
            }
        }
        return stars.toString();
    }

    private static JSONObject parse(String json) {
        try {
            return new JSONObject(json == null ? "{}" : json);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
