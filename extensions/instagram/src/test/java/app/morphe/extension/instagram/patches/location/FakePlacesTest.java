package app.morphe.extension.instagram.patches.location;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class FakePlacesTest {
    private static final double DELTA = 1e-9;

    private static void assertPoint(double latitude, double longitude, String text) {
        assertArrayEquals(text, new double[]{latitude, longitude}, FakePlaces.parseCoordinates(text), DELTA);
    }

    @Test
    public void typedCoordinatesAreRead() {
        assertPoint(48.8584, 2.2945, "48.8584, 2.2945");
        assertPoint(48.8584, 2.2945, " 48.8584 2.2945 ");
        assertPoint(-33.8568, 151.2153, "-33.8568,151.2153");
        assertPoint(40, -74, "40, -74");
        assertPoint(48.85, 2.29, "Meet at 48.85, 2.29.");
    }

    @Test
    public void mapsLinksAreRead() {
        assertPoint(48.8583701, 2.2944813,
                "https://www.google.com/maps/place/Eiffel+Tower/@48.8583,2.2919,17z/data=!3m1!4b1!4m6!3m5!8m2!3d48.8583701!4d2.2944813");
        assertPoint(48.8583, 2.2919, "https://www.google.com/maps/@48.8583,2.2919,17z");
        assertPoint(48.8583, 2.2945, "https://maps.google.com/?q=48.8583%2C2.2945");
        assertPoint(48.8583, 2.2945, "https://maps.apple.com/?ll=48.8583,2.2945&q=Pin");
        assertPoint(48.85, 2.29, "https://www.openstreetmap.org/?mlat=48.85&mlon=2.29#map=15/1.0/1.0");
        assertPoint(48.85, 2.29, "https://www.openstreetmap.org/#map=15/48.85/2.29");
        assertPoint(48.85, 2.29, "geo:0,0?q=48.85,2.29(Pin)");
    }

    @Test
    public void outOfRangeAndMissingCoordinatesAreRejected() {
        assertNull(FakePlaces.parseCoordinates("91, 10"));
        assertNull(FakePlaces.parseCoordinates("10, 181"));
        assertNull(FakePlaces.parseCoordinates("48, 1800"));
        assertNull(FakePlaces.parseCoordinates("48, -1800"));
        assertNull(FakePlaces.parseCoordinates("1000.5, 10"));
        assertNull(FakePlaces.parseCoordinates("https://www.google.com/maps/@48.85,1800.5,17z"));
        assertNull(FakePlaces.parseCoordinates("https://www.google.com/maps/place/x/data=!3d48.85!4d1800.5"));
        assertNull(FakePlaces.parseCoordinates("0, 0"));
        assertNull(FakePlaces.parseCoordinates("https://maps.app.goo.gl/AbCdEf"));
        assertNull(FakePlaces.parseCoordinates("Paris"));
        assertNull(FakePlaces.parseCoordinates(""));
        assertNull(FakePlaces.parseCoordinates(null));
        // A failed pair does not hide a valid one that overlaps it.
        assertPoint(45.5, 10.2, "200, 45.5, 10.2");
    }

    @Test
    public void placesSurviveSavingAndBadEntriesAreDropped() {
        List<FakePlaces.Place> saved = Arrays.asList(
                new FakePlaces.Place("a", "Home", 48.8584, 2.2945),
                new FakePlaces.Place("b", "Work", -33.8568, 151.2153));
        List<FakePlaces.Place> loaded = FakePlaces.parse(FakePlaces.toJson(saved));
        assertEquals(2, loaded.size());
        assertEquals("a", loaded.get(0).id);
        assertEquals("Home", loaded.get(0).label);
        assertEquals(151.2153, loaded.get(1).longitude, DELTA);

        String mixed = "[{\"id\":\"x\",\"lat\":95,\"lng\":1},{\"lat\":1,\"lng\":1},"
                + "{\"id\":\"y\",\"label\":\" \",\"lat\":1.5,\"lng\":2.5}]";
        List<FakePlaces.Place> kept = FakePlaces.parse(mixed);
        assertEquals(1, kept.size());
        assertEquals("1.50000, 2.50000", kept.get(0).label);
        assertTrue(FakePlaces.parse("not json").isEmpty());
    }

    private static void assertInMojave(FakePlaces.Place place) {
        assertTrue(place.latitude >= 34.75 && place.latitude <= 35.55);
        assertTrue(place.longitude >= -116.15 && place.longitude <= -115.25);
    }

    @Test
    public void mojavePointsStayInTheRangeToFiveDecimals() {
        Random random = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            double[] point = FakePlaces.randomMojavePoint(random);
            assertInMojave(new FakePlaces.Place("p", "p", point[0], point[1]));
            assertEquals(point[0], Math.round(point[0] * 100_000d) / 100_000d, 0);
            assertEquals(point[1], Math.round(point[1] * 100_000d) / 100_000d, 0);
        }
    }

    @Test
    public void withoutAChoiceTheMojavePlaceIsMadeOnceAndKept() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("fake_location_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        FakePlaces.useForTest(preferences);
        assertNull(FakePlaces.active());

        FakePlaces.Place first = FakePlaces.chosen();
        assertNotNull(first);
        assertEquals(FakePlaces.DEFAULT_ID, first.id);
        assertEquals(FakePlaces.DEFAULT_LABEL, first.label);
        assertInMojave(first);
        assertEquals(first.latitude, FakePlaces.chosen().latitude, 0);

        // As after a restart.
        FakePlaces.useForTest(preferences);
        assertEquals(1, FakePlaces.all().size());
        FakePlaces.Place reloaded = FakePlaces.chosen();
        assertEquals(first.latitude, reloaded.latitude, 0);
        assertEquals(first.longitude, reloaded.longitude, 0);

        // Deleting it while Fake location is on makes a new one; while off, nothing is chosen.
        FakePlaces.remove(FakePlaces.DEFAULT_ID, true);
        assertEquals(FakePlaces.DEFAULT_ID, FakePlaces.active().id);
        assertInMojave(FakePlaces.active());
        FakePlaces.remove(FakePlaces.DEFAULT_ID, false);
        assertNull(FakePlaces.active());

        FakePlaces.add("Home", 48.8584, 2.2945);
        assertEquals("Home", FakePlaces.chosen().label);
        FakePlaces.useForTest(null);
    }
}
