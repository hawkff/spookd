package app.morphe.extension.instagram.patches.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class FakeLocationTest {
    @Test
    public void builtLocationsArePlausibleAndNearThePlace() {
        FakePlaces.Place place = new FakePlaces.Place("a", "Edge", 90, -180);
        long before = System.currentTimeMillis();
        Location location = FakeLocation.build(place, "gps");
        assertEquals("gps", location.getProvider());
        assertTrue(location.getLatitude() <= 90 && location.getLatitude() > 89.999);
        assertTrue(location.getLongitude() >= -180 && location.getLongitude() < -179.999);
        assertTrue(location.hasAccuracy() && location.getAccuracy() >= 8 && location.getAccuracy() <= 20);
        assertTrue(location.getTime() >= before);
        assertEquals("fused", FakeLocation.build(place, null).getProvider());
    }

    /** Stands in for the plugin's listener and the shop finder's callback, which are answered by reflection. */
    public static final class Recorder implements LocationListener {
        final List<Location> received = new CopyOnWriteArrayList<>();

        @Override
        public void onLocationChanged(Location location) {
            received.add(location);
        }

        public Object invoke(Object location) {
            received.add((Location) location);
            return null;
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    }

    private final AtomicBoolean on = new AtomicBoolean(true);
    private final CountDownLatch release = new CountDownLatch(1);

    // Places whose read waits for release, with Fake location on until a test turns it off.
    private void startBlockedLoad() {
        FakeLocation.switchOn = on::get;
        SharedPreferences real = RuntimeEnvironment.getApplication()
                .getSharedPreferences("fake_location_hook_test", Context.MODE_PRIVATE);
        real.edit().clear().putString("places", "[{\"id\":\"h\",\"label\":\"Home\",\"lat\":48.8584,\"lng\":2.2945}]")
                .putString("active", "h").commit();
        FakePlaces.useForTest((SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getString")) release.await();
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
        FakePlaces.preload();
    }

    // Lets the read finish and runs everything queued behind it, including the late answers.
    private void finishLoad() throws InterruptedException {
        AtomicBoolean drained = new AtomicBoolean();
        FakePlaces.whenLoaded(() -> drained.set(true));
        release.countDown();
        long deadline = System.currentTimeMillis() + 5_000;
        while (!drained.get() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(5);
        }
        assertTrue(drained.get());
        // Answers posted by the late callbacks themselves.
        ShadowLooper.idleMainLooper();
    }

    @After
    public void tearDown() {
        release.countDown();
        FakeLocation.switchOn = FakeLocation::replacesDevice;
        FakePlaces.useForTest(null);
    }

    @Test
    public void requestsWhileLoadingAreAnsweredUnlessRemoved() throws InterruptedException {
        startBlockedLoad();
        Recorder kept = new Recorder();
        Recorder removed = new Recorder();
        Recorder managerKept = new Recorder();
        Recorder managerRemoved = new Recorder();
        Recorder shopFinder = new Recorder();
        assertNull(FakeLocation.pluginAnswer(kept));
        assertNull(FakeLocation.pluginAnswer(removed));
        assertNull(FakeLocation.requestUpdates(managerKept, "gps"));
        assertNull(FakeLocation.requestUpdates(managerRemoved, "gps"));
        assertNull(FakeLocation.shopFinderAnswer(shopFinder));
        FakeLocation.pluginRemoved(removed);
        FakeLocation.removalListener(managerRemoved);
        ShadowLooper.idleMainLooper();

        finishLoad();
        assertEquals(1, kept.received.size());
        assertEquals(1, managerKept.received.size());
        assertEquals(1, shopFinder.received.size());
        assertTrue(removed.received.isEmpty());
        assertTrue(managerRemoved.received.isEmpty());
        assertTrue(FakeLocation.PLUGIN_ANSWERING.isEmpty());
    }

    @Test
    public void requestsWhileLoadingAreDroppedWhenTheSwitchTurnsOff() throws InterruptedException {
        startBlockedLoad();
        Recorder plugin = new Recorder();
        Recorder manager = new Recorder();
        Recorder shopFinder = new Recorder();
        assertNull(FakeLocation.pluginAnswer(plugin));
        assertNull(FakeLocation.requestUpdates(manager, "gps"));
        assertNull(FakeLocation.shopFinderAnswer(shopFinder));
        ShadowLooper.idleMainLooper();

        on.set(false);
        finishLoad();
        assertTrue(plugin.received.isEmpty());
        assertTrue(manager.received.isEmpty());
        assertTrue(shopFinder.received.isEmpty());
        assertTrue(FakeLocation.PLUGIN_ANSWERING.isEmpty());
        assertTrue(FakeLocation.SHOP_FINDER_ANSWERING.isEmpty());
    }
}
