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
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

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
    // Listeners whose answered request has not been delivered or removed yet, with that request's token.
    static final Map<LocationListener, Object> ANSWERING =
            Collections.synchronizedMap(new IdentityHashMap<>());
    // Plugin listeners and shop finder callbacks waiting for the places, with their request's token.
    static final Map<Object, Object> PLUGIN_ANSWERING = Collections.synchronizedMap(new IdentityHashMap<>());
    static final Map<Object, Object> SHOP_FINDER_ANSWERING = Collections.synchronizedMap(new IdentityHashMap<>());
    private static volatile Constructor<?> wrapperConstructor;
    // Rechecked right before every delivery that waited for the places; replaceable in tests.
    static BooleanSupplier switchOn = FakeLocation::replacesDevice;

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
     * place and nothing is asked of the device (null); a request made while the places load stays open until they
     * are read. Otherwise the listener to register, which checks again on every fix, so a later switch to an
     * account with Fake location on hands on no real fix.
     */
    public static LocationListener requestUpdates(LocationListener listener, String provider) {
        try {
            if (listener == null) return null;
            if (!switchOn.getAsBoolean()) return LISTENERS.computeIfAbsent(listener, SpoofingListener::new);
            // A token per request, so an answer queued for an earlier, removed request never takes a later one's.
            Object token = new Object();
            ANSWERING.put(listener, token);
            Looper looper = Looper.myLooper();
            // Delivered later on the requesting looper, as LocationManager would.
            Handler handler = new Handler(looper != null ? looper : Looper.getMainLooper());
            handler.post(() -> answer(listener, provider, token, handler, false));
            return null;
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.requestUpdates failed", t);
            // Dropping the request is safer than registering a listener that could hand on a real fix.
            return null;
        }
    }

    private static void answer(LocationListener listener, String provider, Object token, Handler handler,
                               boolean afterLoad) {
        if (ANSWERING.get(listener) != token) return;
        // Fake location may have been turned off meanwhile, by an account switch.
        if (!switchOn.getAsBoolean()) {
            ANSWERING.remove(listener, token);
            return;
        }
        answerWith(lastKnown(provider), listener, provider, token, handler, afterLoad);
    }

    /**
     * Takes the fix already read. Without one, it waits for the places even if they look read by now, since they may
     * have finished between that read and here; {@code whenLoaded} runs at once for a finished read.
     */
    static void answerWith(Location fix, LocationListener listener, String provider, Object token, Handler handler,
                           boolean afterLoad) {
        if (fix == null) {
            if (afterLoad) ANSWERING.remove(listener, token);
            else FakePlaces.whenLoaded(() -> handler.post(() -> answer(listener, provider, token, handler, true)));
            return;
        }
        if (ANSWERING.remove(listener, token)) listener.onLocationChanged(fix);
    }

    /** The plugin's update request while on: the fix to deliver now, or null with it delivered once places load. */
    public static Location pluginAnswer(Object listener) {
        return nowOrWhenLoaded(PLUGIN_ANSWERING, listener, "onLocationChanged", Location.class);
    }

    /** Start of the plugin's update removal: a delivery still waiting for the places is dropped. */
    public static void pluginRemoved(Object listener) {
        try {
            if (listener != null) PLUGIN_ANSWERING.remove(listener);
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation.pluginRemoved failed", t);
        }
    }

    /** The shop finder's location request while on: the fix to deliver now, or null with it delivered once places load. */
    public static Location shopFinderAnswer(Object callback) {
        // The shop finder has no way to cancel its request, so only the switch can drop the delivery.
        return nowOrWhenLoaded(SHOP_FINDER_ANSWERING, callback, "invoke", Object.class);
    }

    private static Location nowOrWhenLoaded(Map<Object, Object> pending, Object target, String name, Class<?> parameter) {
        return nowOrWhenLoaded(current(), pending, target, name, parameter);
    }

    // Takes the fix already read; without one it always waits, as in answerWith.
    static Location nowOrWhenLoaded(Location fix, Map<Object, Object> pending, Object target, String name,
                                    Class<?> parameter) {
        if (fix != null || target == null) return fix;
        Object token = new Object();
        pending.put(target, token);
        FakePlaces.whenLoaded(() -> deliverLate(pending, target, token, name, parameter));
        return null;
    }

    static void deliverLate(Map<Object, Object> pending, Object target, Object token, String name, Class<?> parameter) {
        try {
            if (!pending.remove(target, token) || !switchOn.getAsBoolean()) return;
            Location late = current();
            if (late == null) return;
            Method method = target.getClass().getMethod(name, parameter);
            method.setAccessible(true);
            method.invoke(target, late);
        } catch (Throwable t) {
            Logger.printException(() -> "FakeLocation late answer failed", t);
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
