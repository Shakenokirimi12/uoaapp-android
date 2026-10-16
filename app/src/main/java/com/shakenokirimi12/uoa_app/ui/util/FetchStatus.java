package com.shakenokirimi12.uoa_app.ui.util;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 取得に失敗したときの帯 (view_status_notice を fetch_notice として各画面に置いている)。
 *
 * 失敗しても手元のキャッシュは出し続けるが、それが古いことを黙っていると「今日の授業はありません」の
 * ように事実と違う表示になる。いつの時点の情報かを必ず添える。
 */
public final class FetchStatus {
    private FetchStatus() {}

    /** 「取得できませんでした（最終更新 10/16 12:34）」。一度も取れていなければその旨。 */
    @NonNull
    public static String failureText(long lastFetchedAt, long now, @NonNull TimeZone zone) {
        if (lastFetchedAt <= 0) return "取得できませんでした（まだ一度も取得できていません）";
        Calendar fetched = Calendar.getInstance(zone);
        fetched.setTimeInMillis(lastFetchedAt);
        Calendar today = Calendar.getInstance(zone);
        today.setTimeInMillis(now);
        boolean sameDay = fetched.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && fetched.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
        SimpleDateFormat f = new SimpleDateFormat(sameDay ? "HH:mm" : "M/d HH:mm", Locale.JAPAN);
        f.setTimeZone(zone);
        return "取得できませんでした（最終更新 " + f.format(new Date(lastFetchedAt)) + "）";
    }

    /** notice は fetch_notice (view_status_notice) のルート。null なら何もしない。 */
    public static void showFailure(@Nullable View notice, long lastFetchedAt) {
        if (notice == null) return;
        TextView text = notice.findViewById(R.id.status_notice_text);
        text.setText(failureText(lastFetchedAt, System.currentTimeMillis(), TimeZone.getDefault()));
        View dismiss = notice.findViewById(R.id.status_notice_dismiss);
        if (dismiss != null) dismiss.setOnClickListener(v -> notice.setVisibility(View.GONE));
        notice.setVisibility(View.VISIBLE);
    }

    public static void hide(@Nullable View notice) {
        if (notice != null) notice.setVisibility(View.GONE);
    }
}
