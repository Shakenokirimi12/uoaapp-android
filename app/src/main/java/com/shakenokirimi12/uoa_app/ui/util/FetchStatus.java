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

    /** 一覧が空のときに何と言うか。 */
    public enum Empty {
        /** 未ログイン。「ログインすると表示されます」。 */
        NOT_LOGGED_IN,
        /** まだ一度も取れておらず、取得中。何も言わない (「ありません」は事実と違いうる)。 */
        LOADING,
        /** 取得に失敗し、一度も取れていない。「〜を取得できませんでした」。 */
        UNAVAILABLE,
        /** 取れたデータが空。「〜はありません」と言ってよい。 */
        NONE
    }

    /**
     * 空の一覧に出す状態。キャッシュが新しくて取得を飛ばした場合も、取れたことがある (lastFetchedAt > 0)
     * なら NONE になる。lastFetchedAt はその一覧の元データを最後に保存した時刻 (DataCache.fetchedAt)。
     */
    @NonNull
    public static Empty emptyKind(boolean loggedIn, boolean failed, long lastFetchedAt) {
        if (!loggedIn) return Empty.NOT_LOGGED_IN;
        if (lastFetchedAt > 0) return Empty.NONE;
        return failed ? Empty.UNAVAILABLE : Empty.LOADING;
    }

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
