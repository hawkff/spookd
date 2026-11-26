/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.location;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Utils;

/**
 * The places Fake location can show and the one chosen, kept in {@code spookd_fake_location} on this device for
 * every account. Read from disk once; afterwards every read is from memory.
 */
public final class FakePlaces {
    private static final String PREFS_NAME = "spookd_fake_location";
    private static final String KEY_PLACES = "places";
    private static final String KEY_ACTIVE = "active";
    private static final String NUMBER = "(-?\\d{1,3}(?:\\.\\d+)?)";
    // In order: a Google Maps place pin, an OpenStreetMap marker, an OpenStreetMap view, then any number pair.
    private static final Pattern[] COORDINATES = {
            Pattern.compile("!3d" + NUMBER + "!4d" + NUMBER),
            Pattern.compile("mlat=" + NUMBER + "&mlon=" + NUMBER),
            Pattern.compile("map=\\d+(?:\\.\\d+)?/" + NUMBER + "/" + NUMBER),
            Pattern.compile("(?<![\\d.])" + NUMBER + "\\s*[,\\s]\\s*" + NUMBER),
    };

    /** A saved place. */
    public static final class Place {
        public final String id;
        public final String label;
        public final double latitude;
        public final double longitude;

        Place(String id, String label, double latitude, double longitude) {
            this.id = id;
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final List<Place> places = new ArrayList<>();
    private static volatile Place active;
    private static volatile boolean loaded;

    private FakePlaces() {
    }

    /** The chosen place, or null when none is chosen; any thread. */
    public static Place active() {
        if (!loaded) load();
        return active;
    }

    public static synchronized List<Place> all() {
        load();
        return new ArrayList<>(places);
    }

    /** Saves a new place and chooses it. */
    public static synchronized void add(String label, double latitude, double longitude) {
        load();
        Place place = new Place(UUID.randomUUID().toString(), labelOr(label, latitude, longitude), latitude, longitude);
        places.add(place);
        active = place;
        save();
    }

    public static synchronized void rename(String id, String label) {
        load();
        for (int i = 0; i < places.size(); i++) {
            Place place = places.get(i);
            if (!place.id.equals(id)) continue;
            Place renamed = new Place(id, labelOr(label, place.latitude, place.longitude), place.latitude, place.longitude);
            places.set(i, renamed);
            if (place == active) active = renamed;
        }
        save();
    }

    /** Deletes a place; deleting the chosen one chooses the first place left, so the real location stays hidden. */
    public static synchronized void remove(String id) {
        load();
        places.removeIf(place -> place.id.equals(id));
        if (active != null && active.id.equals(id)) active = places.isEmpty() ? null : places.get(0);
        save();
    }

    public static synchronized void choose(String id) {
        load();
        Place place = find(places, id);
        if (place != null) active = place;
        save();
    }

    private static synchronized void load() {
        if (loaded) return;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        places.clear();
        places.addAll(parse(preferences.getString(KEY_PLACES, "[]")));
        active = find(places, preferences.getString(KEY_ACTIVE, null));
        loaded = true;
    }

    private static void save() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        Place chosen = active;
        preferences.edit()
                .putString(KEY_PLACES, toJson(places))
                .putString(KEY_ACTIVE, chosen == null ? null : chosen.id)
                .apply();
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Place find(List<Place> places, String id) {
        if (id == null) return null;
        for (Place place : places) {
            if (place.id.equals(id)) return place;
        }
        return null;
    }

    /** Latitude and longitude from typed coordinates or a maps link, or null when none are in range. */
    public static double[] parseCoordinates(String text) {
        if (text == null) return null;
        String decoded = text.trim();
        try {
            decoded = URLDecoder.decode(decoded, "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
        }
        for (Pattern pattern : COORDINATES) {
            Matcher matcher = pattern.matcher(decoded);
            int from = 0;
            while (from < decoded.length() && matcher.find(from)) {
                double latitude = Double.parseDouble(matcher.group(1));
                double longitude = Double.parseDouble(matcher.group(2));
                if (isValid(latitude, longitude)) return new double[]{latitude, longitude};
                from = matcher.start() + 1;
            }
        }
        return null;
    }

    // Instagram's location wrapper rejects exactly 0, 0.
    static boolean isValid(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
                && !(latitude == 0 && longitude == 0);
    }

    public static String format(double latitude, double longitude) {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }

    static String labelOr(String label, double latitude, double longitude) {
        String trimmed = label == null ? "" : label.trim();
        return trimmed.isEmpty() ? format(latitude, longitude) : trimmed;
    }

    static List<Place> parse(String json) {
        List<Place> parsed = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                double latitude = item.optDouble("lat", Double.NaN);
                double longitude = item.optDouble("lng", Double.NaN);
                if (id.isEmpty() || !isValid(latitude, longitude)) continue;
                parsed.add(new Place(id, labelOr(item.optString("label", ""), latitude, longitude), latitude, longitude));
            }
        } catch (JSONException ignored) {
        }
        return parsed;
    }

    static String toJson(List<Place> places) {
        JSONArray array = new JSONArray();
        for (Place place : places) {
            try {
                array.put(new JSONObject()
                        .put("id", place.id)
                        .put("label", place.label)
                        .put("lat", place.latitude)
                        .put("lng", place.longitude));
            } catch (JSONException ignored) {
                // Only a non-finite number throws, which isValid keeps out.
            }
        }
        return array.toString();
    }
}
