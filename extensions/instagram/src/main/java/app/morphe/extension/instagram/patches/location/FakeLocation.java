/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.location;

import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Shows Instagram the chosen saved place wherever its code reads the device location: the location plugin, the
 * location cache, the wrapper its location stack builds around every fix, and its LocationManager calls. While
 * Fake location is on the device location is never handed on; until the saved places are read, there is none.
 * Each hook runs on the caller's thread and reads only memory.
 */
@SuppressWarnings("unused")
public final class FakeLocation {
    private static final String PROVIDER = "fused";
    // About 3 m, so repeated fixes differ a little like a real receiver's.
    private static final double JITTER_DEGREES = 0.00003;
    private static final Map<LocationListener, LocationListener> LISTENERS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    // Listeners whose answered request has not been delivered or removed yet.
    private static final Set<LocationListener> ANSWERING =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<LocationListener, Boolean>()));
    private static volatile Constructor<?> wrapperConstructor;

    private FakeLocation() {
    }

    /** Whether Instagram's location reads are answered here instead of by the device. */
    public static boolean replacesDevice() {
        try {
            return Pref.fakeLocation();
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.replacesDevice failed", t);
            return false;
        }
    }

    /** The location plugin's last-location read and update request while on: the chosen place, or null for none. */
    public static Location current() {
        return lastKnown(PROVIDER);
    }

    /** Instagram's LocationManager last-known read while on: the chosen place, or null for none. */
    public static Location lastKnown(String provider) {
        try {
            FakePlaces.Place place = FakePlaces.chosen();
            return place == null ? null : build(place, provider);
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.lastKnown failed", t);
            return null;
        }
    }

    /**
     * Start of the wrapper Instagram puts around every fix: the chosen place instead. It cannot refuse a fix, so
     * while the places are still being read the fix passes; every source of real fixes is answered or held back
     * above it ({@link #blocksFix}, {@link #lastKnown}, {@link #requestUpdates}).
     */
    public static Location spoof(Location device) {
        try {
            if (device == null || !replacesDevice()) return device;
            Location fix = lastKnown(device.getProvider());
            return fix == null ? device : fix;
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.spoof failed", t);
            return device;
        }
    }

    /** Start of the wrapper's factory for device fixes: true makes it return none while there is no place yet. */
    public static boolean blocksFix() {
        try {
            return replacesDevice() && FakePlaces.chosen() == null;
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.blocksFix failed", t);
            return true;
        }
    }

    /**
     * The fix Instagram's location cache holds, as it reads it: while on, a new wrapper of the chosen place, so a
     * fix cached before the switch or an earlier place is never handed out; null when there is no place yet.
     */
    public static Object cachedFix(Object wrapper) {
        try {
            if (wrapper == null || !replacesDevice()) return wrapper;
            FakePlaces.Place place = FakePlaces.chosen();
            if (place == null) return null;
            Constructor<?> constructor = wrapperConstructor;
            if (constructor == null || constructor.getDeclaringClass() != wrapper.getClass()) {
                constructor = wrapper.getClass().getConstructor(Location.class);
                wrapperConstructor = constructor;
            }
            return constructor.newInstance(build(place, PROVIDER));
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.cachedFix failed", t);
            return null;
        }
    }

    /**
     * Start of Instagram's LocationManager update request. While on, the request is answered here with the chosen
     * place and nothing is asked of the device (null). Otherwise the listener to register, which checks again on
     * every fix, so a later switch to an account with Fake location on hands on no real fix.
     */
    public static LocationListener requestUpdates(LocationListener listener, String provider) {
        try {
            if (listener == null) return null;
            if (!replacesDevice()) return LISTENERS.computeIfAbsent(listener, SpoofingListener::new);
            ANSWERING.add(listener);
            Looper looper = Looper.myLooper();
            // Delivered later on the requesting looper, as LocationManager would.
            new Handler(looper != null ? looper : Looper.getMainLooper()).post(() -> {
                if (!ANSWERING.remove(listener)) return;
                Location fix = lastKnown(provider);
                if (fix != null) listener.onLocationChanged(fix);
            });
            return null;
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.requestUpdates failed", t);
            // Dropping the request is safer than registering a listener that could hand on a real fix.
            return null;
        }
    }

    /** Start of Instagram's LocationManager update removal: the listener that was registered for {@code listener}. */
    public static LocationListener removalListener(LocationListener listener) {
        try {
            if (listener == null) return null;
            ANSWERING.remove(listener);
            LocationListener registered = LISTENERS.remove(listener);
            return registered == null ? listener : registered;
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.removalListener failed", t);
            return listener;
        }
    }

    /** Start of the location and Wi-Fi signal collection Instagram sets up for each session: true skips it. */
    public static boolean blocksCollection() {
        return replacesDevice();
    }

    static Location build(FakePlaces.Place place, String provider) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location location = new Location(provider == null ? PROVIDER : provider);
        location.setLatitude(clamp(place.latitude + random.nextDouble(-JITTER_DEGREES, JITTER_DEGREES), 90));
        location.setLongitude(clamp(place.longitude + random.nextDouble(-JITTER_DEGREES, JITTER_DEGREES), 180));
        location.setAccuracy((float) random.nextDouble(8, 20));
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private static final class SpoofingListener implements LocationListener {
        private final LocationListener delegate;

        SpoofingListener(LocationListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onLocationChanged(Location location) {
            if (!replacesDevice()) {
                delegate.onLocationChanged(location);
                return;
            }
            Location fix = lastKnown(location == null ? PROVIDER : location.getProvider());
            if (fix != null) delegate.onLocationChanged(fix);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) {
            delegate.onStatusChanged(provider, status, extras);
        }

        @Override
        public void onProviderEnabled(String provider) {
            delegate.onProviderEnabled(provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            delegate.onProviderDisabled(provider);
        }
    }
}
