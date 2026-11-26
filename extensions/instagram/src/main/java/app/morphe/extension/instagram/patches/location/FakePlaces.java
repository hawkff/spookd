/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The places Fake location can show and the one chosen, kept in {@code spookd_fake_location} on this device for
 * every account. Read once on a background thread; every read, from hooks or the settings screen, is from memory.
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

    /** What was read from disk, replaced as a whole on every change, so readers never take a lock. */
    private static final class Snapshot {
        final List<Place> places;
        final Place active;
        final double[] defaultPoint;

        Snapshot(List<Place> places, Place active, double[] defaultPoint) {
            this.places = Collections.unmodifiableList(new ArrayList<>(places));
            this.active = active;
            this.defaultPoint = defaultPoint;
        }
    }

    // Guards changes and the waiting callbacks; never held while the disk is read.
    private static final Object LOCK = new Object();
    private static final AtomicBoolean loading = new AtomicBoolean();
    private static final List<Runnable> whenLoaded = new ArrayList<>();
    private static volatile Snapshot snapshot;
    private static int generation;
    private static SharedPreferences preferencesForTest;

    private FakePlaces() {
    }

    /** Starts reading the saved places on a background thread, once; returns at once on any thread. */
    public static void preload() {
        if (snapshot != null || !loading.compareAndSet(false, true)) return;
        int started;
        synchronized (LOCK) {
            started = generation;
        }
        new Thread(() -> read(started), "spookd-fake-location").start();
    }

    private static void read(int started) {
        Snapshot read = null;
        try {
            SharedPreferences preferences = preferences();
            if (preferences != null) {
                List<Place> places = parse(preferences.getString(KEY_PLACES, "[]"));
                read = new Snapshot(places, find(places, preferences.getString(KEY_ACTIVE, null)),
                        parseCoordinates(preferences.getString(KEY_DEFAULT, "")));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "FakePlaces.read failed", t);
        }
        List<Runnable> callbacks;
        synchronized (LOCK) {
            if (started != generation) return;
            loading.set(false);
            // Without a context yet, the next reader starts another read.
            if (read == null || snapshot != null) return;
            snapshot = read;
            callbacks = new ArrayList<>(whenLoaded);
            whenLoaded.clear();
        }
        Handler main = new Handler(Looper.getMainLooper());
        for (Runnable callback : callbacks) main.post(callback);
    }

    public static boolean isLoaded() {
        return snapshot != null;
    }

    /** Runs {@code callback} on the main thread once the places are read; at once when they already are. */
    public static void whenLoaded(Runnable callback) {
        synchronized (LOCK) {
            if (snapshot == null) {
                whenLoaded.add(callback);
                preload();
                return;
            }
        }
        callback.run();
    }

    /**
     * The place Instagram sees while Fake location is on, from memory only, on any thread: the chosen place, or the
     * Mojave Desert one when none is chosen. Null only while the saved places are still being read.
     */
    public static Place chosen() {
        Snapshot current = snapshot;
        if (current == null) {
            preload();
            return null;
        }
        return current.active != null ? current.active : chooseDefault();
    }

    /** The chosen place, or null when none is chosen or the places are still being read. */
    public static Place active() {
        Snapshot current = snapshot;
        if (current == null) preload();
        return current == null ? null : current.active;
    }

    /** The saved places, or none while they are still being read. */
    public static List<Place> all() {
        Snapshot current = snapshot;
        if (current == null) preload();
        return current == null ? Collections.emptyList() : current.places;
    }

    /** Saves a new place and chooses it. */
    public static void add(String label, double latitude, double longitude) {
        synchronized (LOCK) {
            Snapshot current = snapshot;
            if (current == null) return;
            Place place = new Place(UUID.randomUUID().toString(), labelOr(label, latitude, longitude), latitude, longitude);
            List<Place> places = new ArrayList<>(current.places);
            places.add(place);
            publish(new Snapshot(places, place, current.defaultPoint));
        }
    }

    public static void rename(String id, String label) {
        synchronized (LOCK) {
            Snapshot current = snapshot;
            if (current == null) return;
            List<Place> places = new ArrayList<>(current.places);
            Place active = current.active;
            for (int i = 0; i < places.size(); i++) {
                Place place = places.get(i);
                if (!place.id.equals(id)) continue;
                Place renamed = new Place(id, labelOr(label, place.latitude, place.longitude), place.latitude, place.longitude);
                places.set(i, renamed);
                if (place == active) active = renamed;
            }
            publish(new Snapshot(places, active, current.defaultPoint));
        }
    }

    /**
     * Deletes a place. Deleting the chosen one chooses the first place left; with none left and {@code keepChosen}
     * (Fake location is on) a new Mojave Desert point is made, so the real location stays hidden.
     */
    public static void remove(String id, boolean keepChosen) {
        synchronized (LOCK) {
            Snapshot current = snapshot;
            if (current == null) return;
            List<Place> places = new ArrayList<>(current.places);
            places.removeIf(place -> place.id.equals(id));
            double[] point = DEFAULT_ID.equals(id) ? null : current.defaultPoint;
            Place active = current.active;
            if (active != null && active.id.equals(id)) active = places.isEmpty() ? null : places.get(0);
            Snapshot next = new Snapshot(places, active, point);
            publish(active == null && keepChosen ? withDefaultChosen(next) : next);
        }
    }

    public static void choose(String id) {
        synchronized (LOCK) {
            Snapshot current = snapshot;
            Place place = current == null ? null : find(current.places, id);
            if (place != null) publish(new Snapshot(current.places, place, current.defaultPoint));
        }
    }

    /**
     * Where the Mojave Desert place is, made once and kept until that place is deleted; also the add form's
     * default. Null while the places are still being read.
     */
    public static double[] defaultPoint() {
        synchronized (LOCK) {
            Snapshot current = snapshot;
            if (current == null) return null;
            if (current.defaultPoint == null) {
                current = new Snapshot(current.places, current.active, randomMojavePoint(ThreadLocalRandom.current()));
                publish(current);
            }
            return current.defaultPoint.clone();
        }
    }

    private static Place chooseDefault() {
        synchronized (LOCK) {
            Snapshot current = snapshot;
            if (current == null) return null;
            if (current.active != null) return current.active;
            Snapshot next = withDefaultChosen(current);
            publish(next);
            return next.active;
        }
    }

    private static Snapshot withDefaultChosen(Snapshot current) {
        Place place = find(current.places, DEFAULT_ID);
        double[] point = current.defaultPoint != null ? current.defaultPoint
                : randomMojavePoint(ThreadLocalRandom.current());
        List<Place> places = new ArrayList<>(current.places);
        if (place == null) {
            place = new Place(DEFAULT_ID, DEFAULT_LABEL, point[0], point[1]);
            places.add(place);
        }
        return new Snapshot(places, place, point);
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

    // apply() changes the in-memory copy, already read by then, at once and writes the file in the background.
    private static void publish(Snapshot next) {
        snapshot = next;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        double[] point = next.defaultPoint;
        preferences.edit()
                .putString(KEY_PLACES, toJson(next.places))
                .putString(KEY_ACTIVE, next.active == null ? null : next.active.id)
                .putString(KEY_DEFAULT, point == null ? null : format(point[0], point[1]))
                .apply();
    }

    private static SharedPreferences preferences() {
        if (preferencesForTest != null) return preferencesForTest;
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Forgets what is in memory and reads {@code preferences} next, as after a restart. */
    static void useForTest(SharedPreferences preferences) {
        synchronized (LOCK) {
            generation++;
            preferencesForTest = preferences;
            snapshot = null;
            loading.set(false);
            whenLoaded.clear();
        }
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
