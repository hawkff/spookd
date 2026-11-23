package app.morphe.extension.instagram.patches;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class TextLinksTest {

    @Test
    public void linksSchemeAndWwwAddressesWithoutTrailingPunctuation() {
        assertLinks("see https://example.com/a?b=1. and www.example.org!",
                "https://example.com/a?b=1", "https://www.example.org");
    }

    @Test
    public void lowercasesSchemeAndKeepsPort() {
        assertLinks("HTTP://Example.com:8080/Path", "http://Example.com:8080/Path");
    }

    @Test
    public void keepsBalancedBracketsAndDropsUnbalancedOnes() {
        assertLinks("(see https://en.wikipedia.org/wiki/Foo_(bar))", "https://en.wikipedia.org/wiki/Foo_(bar)");
    }

    @Test
    public void ignoresBareDomainsOtherSchemesAndEmbeddedPrefixes() {
        assertLinks("example.com ftp://example.com user@www.example.com xhttp://a.com www.nodot http:// javascript:alert(1)");
    }

    @Test
    public void ignoresLinksCutByEllipsis() {
        assertLinks("go to https://exam\u2026 more");
        assertLinks("go to https://example.com/abc... more");
    }

    @Test
    public void linksOnlyWhenTheWholeAuthorityMatched() {
        assertLinks("https://user@example.com/path");
        assertLinks("https://example.\u0440\u0444/path");
        assertLinks("https://b\u00fccher.de");
        assertLinks("https://example.com:80x/path");
        assertLinks("\u8bbf\u95eewww.example.com\u4e86\u89e3");
        assertLinks("(www.example.com) \"https://example.org\"", "https://www.example.com", "https://example.org");
    }

    @Test
    public void skipsTextInstagramAlreadyLinked() {
        SpannableStringBuilder text = new SpannableStringBuilder("@user https://example.com");
        text.setSpan(new Tap(), 6, 25, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextLinks.addLinks(text);
        assertEquals(0, text.getSpans(0, text.length(), TextLinks.LinkSpan.class).length);

        text = new SpannableStringBuilder("visit https://example.com now");
        text.setSpan(new URLSpan("https://example.com/long"), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextLinks.addLinks(text);
        assertEquals(0, text.getSpans(0, text.length(), TextLinks.LinkSpan.class).length);
    }

    @Test
    public void skipsLinkInsideAnEnclosingCustomLinkSpan() {
        SpannableStringBuilder text = new SpannableStringBuilder("visit https://example.com now");
        text.setSpan(new Tap(), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertLinks(text);
    }

    @Test
    public void linksNextToAnAdjacentMention() {
        SpannableStringBuilder text = new SpannableStringBuilder("@user https://example.com");
        text.setSpan(new Tap(), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertLinks(text, "https://example.com");
    }

    @Test
    public void addsEachLinkOnlyOnce() {
        SpannableStringBuilder text = new SpannableStringBuilder("a www.example.com b");
        TextLinks.addLinks(text);
        TextLinks.addLinks(text);
        TextLinks.LinkSpan[] spans = text.getSpans(0, text.length(), TextLinks.LinkSpan.class);
        assertEquals(1, spans.length);
        assertEquals(2, text.getSpanStart(spans[0]));
        assertEquals(17, text.getSpanEnd(spans[0]));
    }

    private static void assertLinks(String text, String... urls) {
        assertLinks(new SpannableStringBuilder(text), urls);
    }

    private static void assertLinks(SpannableStringBuilder text, String... urls) {
        TextLinks.addLinks(text);
        TextLinks.LinkSpan[] spans = text.getSpans(0, text.length(), TextLinks.LinkSpan.class);
        String[] found = new String[spans.length];
        for (int i = 0; i < spans.length; i++) found[i] = spans[i].url;
        assertArrayEquals(urls, found);
    }

    private static final class Tap extends ClickableSpan {
        @Override
        public void onClick(View widget) {
        }
    }
}
