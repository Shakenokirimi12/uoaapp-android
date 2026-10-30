package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.Context;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.R;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 画面に出す日時。iOS の TimetableDates と同じ文面。 */
final class TimetableDates {
    private TimetableDates() {}

    @NonNull
    static String received(@NonNull Context ctx, long ms) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        boolean thisYear = t.getYear() == ZonedDateTime.now().getYear();
        String pattern = ctx.getString(thisYear ? R.string.timetable_date_format_month_day : R.string.timetable_date_format_full);
        String date = DateTimeFormatter.ofPattern(pattern, Locale.JAPAN).format(t);
        return ctx.getString(R.string.timetable_received_on_format, date);
    }

    @NonNull
    static String until(@NonNull Context ctx, long ms) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        String date = DateTimeFormatter.ofPattern(ctx.getString(R.string.timetable_date_format_until), Locale.JAPAN).format(t);
        return ctx.getString(R.string.timetable_valid_until_format, date);
    }

    @NonNull
    static String receivedLine(@NonNull Context ctx, long ms, int classes) {
        return ctx.getString(R.string.timetable_received_line_format, received(ctx, ms), classes);
    }
}
