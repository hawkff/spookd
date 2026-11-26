package app.morphe.extension.instagram.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class ExactTimeTest {
    // 2024-03-05 14:07 UTC, a Tuesday.
    private static final long MILLIS = 1709647620000L;
    private TimeZone original;

    @Before
    public void useUtc() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(original);
    }

    @Test
    public void fullDateHasWeekdayDateYearAndTwelveHourTime() {
        String text = ExactTime.format(MILLIS, Locale.US, false, ExactTime.FULL_DATE);
        assertTrue(text, text.contains("Tuesday"));
        assertTrue(text, text.contains("March 5, 2024"));
        assertTrue(text, text.contains("2:07"));
        assertTrue(text, text.contains("PM"));
    }

    @Test
    public void twentyFourHourSettingDropsTheDayPeriod() {
        String text = ExactTime.format(MILLIS, Locale.US, true, ExactTime.SHORT_DATE);
        assertTrue(text, text.contains("Mar 5"));
        assertTrue(text, text.contains("14:07"));
        assertFalse(text, text.contains("PM"));
    }
}
