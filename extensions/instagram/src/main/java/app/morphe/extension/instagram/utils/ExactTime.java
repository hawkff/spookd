/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.utils;

import android.content.Context;
import android.icu.text.DateTimePatternGenerator;
import android.icu.text.SimpleDateFormat;
import android.icu.util.TimeZone;
import android.text.format.DateFormat;

import java.util.Date;
import java.util.Locale;

import app.morphe.extension.shared.Utils;

/** Local date and time in the user's locale and 12 or 24 hour setting. */
public final class ExactTime {
    /** Weekday, day, month and year. */
    public static final String FULL_DATE = "yMMMMEEEEd";
    /** Day and short month. */
    public static final String SHORT_DATE = "MMMd";

    private ExactTime() {
    }

    public static String format(long millis, String dateSkeleton) {
        Context context = Utils.getContext();
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return format(millis, locale, DateFormat.is24HourFormat(context), dateSkeleton);
    }

    // ICU directly gives the patterns Android's DateFormat uses, and real ones under unit tests too.
    static String format(long millis, Locale locale, boolean is24Hour, String dateSkeleton) {
        String skeleton = dateSkeleton + (is24Hour ? "Hm" : "hm");
        SimpleDateFormat format = new SimpleDateFormat(
                DateTimePatternGenerator.getInstance(locale).getBestPattern(skeleton), locale);
        format.setTimeZone(TimeZone.getTimeZone(java.util.TimeZone.getDefault().getID()));
        return format.format(new Date(millis));
    }
}
