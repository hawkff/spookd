/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.View;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/** Makes plain-text web addresses in feed captions tappable. */
@SuppressWarnings("unused")
public final class TextLinks {
    // http(s):// or a scheme-less www. host, optional port, then everything up to whitespace.
    private static final Pattern LINK = Pattern.compile(
            "(?<![a-z0-9_@./:-])"
                    + "(?:https?://(?:[a-z0-9-]+\\.)*[a-z0-9-]+|www\\.(?:[a-z0-9-]+\\.)+[a-z0-9-]{2,})"
                    + "(?::\\d{1,5})?(?:[/?#][^\\s<>\"]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final String TRAILING = ".,;:!?'*\u2026";

    private TextLinks() {
    }

    /** Called on the main thread at the start of IgTextLayoutView.setTextLayout. */
    public static void onTextLayout(Layout layout) {
        try {
            if (layout == null || !Pref.detectLinksInText()) return;
            CharSequence text = layout.getText();
            // The layout draws spans from its text, so spans added to it show without a rebuild.
            if (text instanceof Spannable) addLinks((Spannable) text);
        } catch (Throwable t) {
            Logger.printException(() -> "TextLinks.onTextLayout failed", t);
        }
    }

    static void addLinks(Spannable text) {
        if (!mayContainLink(text)) return;
        Matcher matcher = LINK.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = trimEnd(text, start, matcher.end());
            if (isCut(text, end) || isLinked(text, start, end)) continue;
            String link = text.subSequence(start, end).toString();
            // Browsers register lowercase schemes only.
            String url = regionMatches(link, 0, "https://") ? "https" + link.substring(5)
                    : regionMatches(link, 0, "http://") ? "http" + link.substring(4)
                    : "https://" + link;
            text.setSpan(new LinkSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static boolean mayContainLink(CharSequence text) {
        for (int i = 0, last = text.length() - 4; i <= last; i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if ((c == 'h' && regionMatches(text, i, "http")) || (c == 'w' && regionMatches(text, i, "www."))) {
                return true;
            }
        }
        return false;
    }

    private static boolean regionMatches(CharSequence text, int offset, String lowerCase) {
        if (offset + lowerCase.length() > text.length()) return false;
        for (int i = 0; i < lowerCase.length(); i++) {
            if (Character.toLowerCase(text.charAt(offset + i)) != lowerCase.charAt(i)) return false;
        }
        return true;
    }

    /** Drops sentence punctuation and unbalanced closing brackets from the end of a match. */
    private static int trimEnd(CharSequence text, int start, int end) {
        while (end > start) {
            char c = text.charAt(end - 1);
            int bracket = ")]}".indexOf(c);
            if (TRAILING.indexOf(c) < 0
                    && (bracket < 0 || count(text, start, end, "([{".charAt(bracket)) >= count(text, start, end, c))) {
                break;
            }
            end--;
        }
        return end;
    }

    private static int count(CharSequence text, int start, int end, char c) {
        int count = 0;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == c) count++;
        }
        return count;
    }

    // A collapsed caption can cut a link before its ellipsis, and a non-ASCII host letter ends the match early.
    private static boolean isCut(CharSequence text, int end) {
        if (end >= text.length()) return false;
        char next = text.charAt(end);
        return next == '\u2026' || regionMatches(text, end, "...")
                || (Character.isLetter(next) && Character.UnicodeScript.of(next) == Character.UnicodeScript.LATIN);
    }

    /** True when Instagram or this class already linked part of the range; a larger span around it does not count. */
    private static boolean isLinked(Spanned text, int start, int end) {
        for (ClickableSpan span : text.getSpans(start, end, ClickableSpan.class)) {
            int spanStart = text.getSpanStart(span);
            int spanEnd = text.getSpanEnd(span);
            boolean around = spanStart <= start && spanEnd >= end && spanEnd - spanStart > end - start;
            if (!around || span instanceof URLSpan) return true;
        }
        return false;
    }

    static final class LinkSpan extends ClickableSpan {
        final String url;

        LinkSpan(String url) {
            this.url = url;
        }

        /** Runs on the main thread from Instagram's link tap handling. */
        @Override
        public void onClick(View widget) {
            try {
                PikoUtils.openUrl(Links.sanitizeUrl(url), false);
            } catch (Throwable t) {
                Logger.printException(() -> "TextLinks open failed", t);
            }
        }

        // Keeps the caption's own color; the layout's paint may have no link color set.
        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setUnderlineText(true);
        }
    }
}
