package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 「今期だけ」の友達を消すための学期 (docs/timetable-share-v3.md の 10.4)。アプリの時間割には学期の判定が無いので、
 * 日本時間の日付で決める: 4〜9 月は "<年>-1"、10〜12 月は "<年>-2"、1〜3 月は "<前の年>-2"。iOS と同じ。
 */
public final class ShareTerm {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private ShareTerm() {}

    @NonNull
    public static String of(long epochMs) {
        ZonedDateTime t = Instant.ofEpochMilli(epochMs).atZone(JST);
        int month = t.getMonthValue();
        if (month >= 4 && month <= 9) return t.getYear() + "-1";
        if (month >= 10) return t.getYear() + "-2";
        return (t.getYear() - 1) + "-2";
    }
}
