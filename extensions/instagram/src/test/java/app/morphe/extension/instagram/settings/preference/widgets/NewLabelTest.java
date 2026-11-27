package app.morphe.extension.instagram.settings.preference.widgets;

import static org.junit.Assert.assertEquals;

import android.text.Spanned;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class NewLabelTest {

    @Test
    public void theLabelFollowsTheTitleAsOneBadge() {
        CharSequence text = InstagramPreferenceStyle.withNewLabel("Keep other audio playing", "NEW");
        assertEquals("Keep other audio playing NEW", text.toString());
        Spanned spanned = (Spanned) text;
        Object[] spans = spanned.getSpans(0, text.length(), InstagramPreferenceStyle.NewLabelSpan.class);
        assertEquals(1, spans.length);
        assertEquals(text.length() - 3, spanned.getSpanStart(spans[0]));
        assertEquals(text.length(), spanned.getSpanEnd(spans[0]));
    }
}
