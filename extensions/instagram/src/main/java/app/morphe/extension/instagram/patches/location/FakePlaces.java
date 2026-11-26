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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final String KEY_DEFAULT = "default";
    public static final String DEFAULT_ID = "mojave";
    public static final String DEFAULT_LABEL = "Mojave Desert";
    // The lookahead keeps a longer number such as 1800 from being read as 180.
    private static final String NUMBER = "(-?\\d{1,3}(?:\\.\\d+)?)(?!\\d)";
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
    private static boolean loading;
    private static double[] defaultPoint;
    private static SharedPreferences preferencesForTest;

    private FakePlaces() {
    }

    /** Starts reading the saved places on a background thread, so location hooks never wait for the disk. */
    public static synchronized void preload() {
        if (loaded || loading) return;
        loading = true;
        new Thread(() -> {
            try {
                load();
            } finally {
                synchronized (FakePlaces.class) {
                    loading = false;
                }
            }
        }, "spookd-fake-location").start();
    }

    /**
     * The place Instagram sees while Fake location is on, from memory only, on any thread: the chosen place, or the
     * Mojave Desert one when none is chosen. Null only while the saved places are still being read.
     */
    public static Place chosen() {
        if (!loaded) {
            preload();
            return null;
        }
        Place place = active;
        return place != null ? place : chooseDefault();
    }

    /** The chosen place, or null when none is chosen; reads the disk the first time, so for the settings screen. */
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

    /**
     * Deletes a place. Deleting the chosen one chooses the first place left; with none left and {@code keepChosen}
     * (Fake location is on) a new Mojave Desert point is made, so the real location stays hidden.
     */
    public static synchronized void remove(String id, boolean keepChosen) {
        load();
        places.removeIf(place -> place.id.equals(id));
        if (DEFAULT_ID.equals(id)) defaultPoint = null;
        if (active != null && active.id.equals(id)) active = places.isEmpty() ? null : places.get(0);
        if (active == null && keepChosen) chooseDefault();
        else save();
    }

    public static synchronized void choose(String id) {
        load();
        Place place = find(places, id);
        if (place != null) active = place;
        save();
    }

    /** Where the Mojave Desert place is, made once and kept until that place is deleted; also the add form's default. */
    public static synchronized double[] defaultPoint() {
        load();
        if (defaultPoint == null) {
            defaultPoint = randomMojavePoint(ThreadLocalRandom.current());
            save();
        }
        return defaultPoint.clone();
    }

    private static synchronized Place chooseDefault() {
        if (active != null) return active;
        Place place = find(places, DEFAULT_ID);
        if (place == null) {
            double[] point = defaultPoint();
            place = new Place(DEFAULT_ID, DEFAULT_LABEL, point[0], point[1]);
            places.add(place);
        }
        active = place;
        save();
        return place;
    }

    /** A point inside the Mojave National Preserve, away from towns, to 5 decimals. */
    static double[] randomMojavePoint(Random random) {
        return new double[]{
                round5(34.75 + random.nextDouble() * 0.80),
                round5(-116.15 + random.nextDouble() * 0.90),
        };
    }

    private static double round5(double value) {
        return Math.round(value * 100_000d) / 100_000d;
    }

    private static synchronized void load() {
        if (loaded) return;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        places.clear();
        places.addAll(parse(preferences.getString(KEY_PLACES, "[]")));
        defaultPoint = parseCoordinates(preferences.getString(KEY_DEFAULT, ""));
        active = find(places, preferences.getString(KEY_ACTIVE, null));
        loaded = true;
    }

    // apply() changes the in-memory copy at once and writes the file in the background.
    private static void save() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        Place chosen = active;
        double[] point = defaultPoint;
        preferences.edit()
                .putString(KEY_PLACES, toJson(places))
                .putString(KEY_ACTIVE, chosen == null ? null : chosen.id)
                .putString(KEY_DEFAULT, point == null ? null : format(point[0], point[1]))
                .apply();
    }

    private static SharedPreferences preferences() {
        if (preferencesForTest != null) return preferencesForTest;
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Forgets what is in memory and reads {@code preferences} next, as after a restart. */
    static synchronized void useForTest(SharedPreferences preferences) {
        preferencesForTest = preferences;
        places.clear();
        active = null;
        defaultPoint = null;
        loaded = false;
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
