package app.morphe.extension.instagram.settings.preference.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class InstagramPreferenceStyleTest {
    private static int distance(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b))
                + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    @Test
    public void lightSearchFieldStandsOffWhiteAndTintedPagesAlike() {
        int text = 0xff0c1014;
        int white = Color.WHITE;
        int tinted = 0xffeef1f8;
        int onWhite = InstagramPreferenceStyle.lightSearchFieldColor(white, text);
        int onTinted = InstagramPreferenceStyle.lightSearchFieldColor(tinted, text);
        assertTrue(distance(white, onWhite) >= 45);
        assertTrue(distance(tinted, onTinted) >= 45);
        assertEquals(Color.rgb(238, 238, 239), onWhite);
    }

    @Test
    public void darkDialogSurfaceLiftsOffThePage() {
        int page = 0xff0c1014;
        int elevated = 0xff212328;
        int highlight = 0xff25292e;
        assertEquals(elevated, InstagramPreferenceStyle.elevatedSurface(page, elevated, highlight));
        // A theme that flattens elevation into the page, or to black, falls back to the highlight.
        assertEquals(highlight, InstagramPreferenceStyle.elevatedSurface(page, page, highlight));
        assertEquals(highlight, InstagramPreferenceStyle.elevatedSurface(Color.BLACK, Color.BLACK, highlight));
        assertEquals(highlight, InstagramPreferenceStyle.elevatedSurface(page, Color.BLACK, highlight));
    }
}
