/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.instagram.settings.SafeStartup;
import app.morphe.extension.shared.Logger;

/** Opens a copied Instagram link or @username inside this app from a Search tab long-press. */
@SuppressWarnings("unused")
public final class CopiedLink {
    // Instagram's own in-app link router; clones keep the class name and change only the package.
    private static final String URL_HANDLER_ACTIVITY = "com.instagram.url.UrlHandlerActivity";

    // The host must end at a path, query, fragment or the end, so "instagram.com.evil.com" and "instagram.com@evil.com" fail.
    private static final Pattern LINK = Pattern.compile(
            "(?i:(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am))(/[^\\s?#]*)?(\\?[^\\s#]*)?(#\\S*)?");
    // "@", then letters, digits, periods and underscores, at most 30, no leading, trailing or double period.
    private static final Pattern USERNAME = Pattern.compile("@((?!\\.)(?!.*\\.\\.)[A-Za-z0-9._]{1,30}(?<!\\.))");

    private CopiedLink() {
    }

    /**
     * Called on the main thread at the start of the Search tab's onLongClick.
     *
     * @return true when a copied link was opened, false to run Instagram's original handler.
     */
    public static boolean onSearchLongClick(View view) {
        if (SafeStartup.featuresOff()) return false;
        Context context = view.getContext();
        String url = copiedUrl(context);
        return url != null && open(context, url);
    }

    /** Main thread. The link for the copied Instagram link or @username, or null when nothing usable is copied. */
    public static String copiedUrl(Context context) {
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            return fromClip(clipboard == null ? null : clipboard.getPrimaryClip());
        } catch (Throwable t) {
            logFailure("Reading the copied link failed", t);
            return null;
        }
    }

    /** Main thread. Opens {@code url} inside this app; false when it could not be started. */
    public static boolean open(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setClassName(context.getPackageName(), URL_HANDLER_ACTIVITY);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            logFailure("Opening the copied link failed", t);
            return false;
        }
    }

    private static void logFailure(String message, Throwable t) {
        try {
            Logger.printException(() -> message, t);
        } catch (Throwable ignored) {
            // Logging must never throw into Instagram's long-click handling.
        }
    }

    /** The link for the first item's text, else its URI string; never coerceToText, which can read a content provider. */
    static String fromClip(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) return null;
        ClipData.Item item = clip.getItemAt(0);
        CharSequence text = item.getText();
        if (text == null && item.getUri() != null) text = item.getUri().toString();
        return toUrl(text);
    }

    /** The https://www.instagram.com link for copied text, or null when it is not an Instagram link or @username. */
    public static String toUrl(CharSequence text) {
        if (text == null) return null;
        String value = text.toString().trim();
        Matcher link = LINK.matcher(value);
        if (link.matches()) {
            String path = link.group(1);
            String query = link.group(2);
            return "https://www.instagram.com" + (path == null ? "/" : path)
                    + (query == null || query.length() == 1 ? "" : query);
        }
        Matcher username = USERNAME.matcher(value);
        if (username.matches()) {
            return "https://www.instagram.com/" + username.group(1).toLowerCase(Locale.ROOT) + "/";
        }
        return null;
    }
}
