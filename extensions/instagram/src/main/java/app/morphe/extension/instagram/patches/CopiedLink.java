/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Utils;

/** Opens an Instagram link or username from the clipboard inside this app. */
public final class CopiedLink {
    // Instagram's own in-app link router; clones keep the class name and change only the package.
    private static final String URL_HANDLER_ACTIVITY = "com.instagram.url.UrlHandlerActivity";

    // The host must end at a path, query, fragment or the end, so "instagram.com.evil.com" and "instagram.com@evil.com" fail.
    private static final Pattern LINK = Pattern.compile(
            "(?i:(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am))(/[^\\s?#]*)?(\\?[^\\s#]*)?(#\\S*)?");
    // Letters, digits, periods and underscores, at most 30, no leading, trailing or double period.
    private static final Pattern USERNAME = Pattern.compile("@?((?!\\.)(?!.*\\.\\.)[A-Za-z0-9._]{1,30}(?<!\\.))");

    private CopiedLink() {
    }

    /** Main thread: reads the clipboard once and opens its link, or shows a toast when it holds none. */
    static void open(Activity activity) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        String url = toUrl(clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText());
        if (url == null) {
            Utils.showToastShort(str("spookd_open_copied_link_invalid"));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setClassName(activity.getPackageName(), URL_HANDLER_ACTIVITY);
        activity.startActivity(intent);
    }

    /** The https://www.instagram.com link for copied text, or null when it is not an Instagram link or username. */
    static String toUrl(CharSequence text) {
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
