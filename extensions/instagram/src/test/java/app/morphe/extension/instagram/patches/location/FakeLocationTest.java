package app.morphe.extension.instagram.patches.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

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
}
