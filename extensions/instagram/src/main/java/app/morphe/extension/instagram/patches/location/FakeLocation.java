/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.location;

import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Shows Instagram the chosen saved place wherever its code reads the device location: the location plugin, the
 * wrapper its location stack builds around every fix, and its LocationManager calls. Each hook runs on the
 * caller's thread and reads only memory once the places are loaded.
 */
@SuppressWarnings("unused")
public final class FakeLocation {
    private static final String PROVIDER = "fused";
    // About 3 m, so repeated fixes differ a little like a real receiver's.
    private static final double JITTER_DEGREES = 0.00003;
    private static final Map<LocationListener, LocationListener> LISTENERS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private FakeLocation() {
    }

    private static FakePlaces.Place place() {
        return Pref.fakeLocation() ? FakePlaces.active() : null;
    }

    /** Start of the wrapper Instagram puts around every device fix: the chosen place instead. */
    public static Location spoof(Location device) {
        try {
            FakePlaces.Place place = place();
            return place == null || device == null ? device : build(place, device.getProvider());
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.spoof failed", t);
            return device;
        }
    }

    /** Start of the location plugin's last-location read and update request: the chosen place, or null to go on. */
    public static Location current() {
        return lastKnown(PROVIDER);
    }

    /** Start of Instagram's LocationManager last-known read: the chosen place, or null to ask the device. */
    public static Location lastKnown(String provider) {
        try {
            FakePlaces.Place place = place();
            return place == null ? null : build(place, provider);
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.lastKnown failed", t);
            return null;
        }
    }

    /** Start of Instagram's LocationManager update request: a listener that hands on the chosen place. */
    public static LocationListener updatesListener(LocationListener listener) {
        try {
            if (listener == null || place() == null) return listener;
            return LISTENERS.computeIfAbsent(listener, SpoofingListener::new);
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.updatesListener failed", t);
            return listener;
        }
    }

    /** Start of Instagram's LocationManager update removal: the listener that was registered for {@code listener}. */
    public static LocationListener removalListener(LocationListener listener) {
        try {
            LocationListener registered = listener == null ? null : LISTENERS.remove(listener);
            return registered == null ? listener : registered;
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.removalListener failed", t);
            return listener;
        }
    }

    /** Start of the location and Wi-Fi signal collection Instagram sets up for each session: true skips it. */
    public static boolean blocksCollection() {
        try {
            return Pref.fakeLocation();
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.blocksCollection failed", t);
            return false;
        }
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
            delegate.onLocationChanged(spoof(location));
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
