package app.morphe.extension.instagram.patches.userprofile;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.After;
import org.junit.Test;

public class ProfileTweaksTest {
    private final Locale original = Locale.getDefault();

    @After
    public void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    public void groupsDigitsForTheDeviceLocale() {
        Locale.setDefault(Locale.US);
        assertEquals("123,456,789", ProfileTweaks.exactCount("123M", 123456789));
        assertEquals("12,345", ProfileTweaks.exactCount("12.3K", 12345));
        assertEquals("999", ProfileTweaks.exactCount("999", 999));

        Locale.setDefault(Locale.GERMANY);
        assertEquals("12.345", ProfileTweaks.exactCount("12,3K", 12345));
    }

    @Test
    public void keepsInstagramTextWithoutACount() {
        assertEquals("12.3K", ProfileTweaks.exactCount("12.3K", null));
    }
}
