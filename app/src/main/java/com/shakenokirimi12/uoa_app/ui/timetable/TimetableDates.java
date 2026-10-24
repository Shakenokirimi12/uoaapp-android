package com.shakenokirimi12.uoa_app.ui.timetable;

import androidx.annotation.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 画面に出す日時。iOS の TimetableDates と同じ文面。 */
final class TimetableDates {
    private TimetableDates() {}

    @NonNull
    static String received(long ms) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        boolean thisYear = t.getYear() == ZonedDateTime.now().getYear();
        String date = DateTimeFormatter.ofPattern(thisYear ? "M月d日" : "yyyy年M月d日", Locale.JAPAN).format(t);
        return date + " に受け取り";
    }

    @NonNull
    static String until(long ms) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        return DateTimeFormatter.ofPattern("M月d日 H:mm", Locale.JAPAN).format(t) + " まで有効";
    }

    @NonNull
    static String receivedLine(long ms, int classes) {
        return received(ms) + " ・ 週 " + classes + " コマ";
    }
}
