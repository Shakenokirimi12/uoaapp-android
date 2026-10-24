package com.shakenokirimi12.uoa_app.widget;

import androidx.annotation.NonNull;

/**
 * 次の授業が無いとき、ウィジェットの見出しに何と書くか。
 *
 * 時間割を一度も取れていない・しばらく取れていないのに「今日の授業はありません」と出すと、
 * 授業がある日に「無い」と伝えてしまう (偽データ禁止のルールに触れる)。取れていないことと
 * 授業が無いことを分ける。
 */
public enum WidgetHeadlineState {
    /** ID/パスワードが無い。 */
    NOT_LOGGED_IN,
    /** 一度も時間割を保存していない。 */
    NOT_FETCHED,
    /** 最後に保存してから STALE_AFTER_MS 以上たっている。同期が失敗し続けている。 */
    STALE,
    /** 今日の授業はあったが、もう全部終わっている。 */
    FINISHED,
    /** 取れた時間割で、今日の授業が無い。 */
    NONE;

    /** 同期は既定で 1 時間ごと。丸 1 日取れていなければ失敗し続けていると見なす。 */
    public static final long STALE_AFTER_MS = 24 * 60 * 60_000L;

    @NonNull
    public static WidgetHeadlineState of(boolean hasCredentials, long fetchedAt, long now, int todayCount) {
        if (!hasCredentials) return NOT_LOGGED_IN;
        if (fetchedAt <= 0) return NOT_FETCHED;
        if (now - fetchedAt >= STALE_AFTER_MS) return STALE;
        return todayCount > 0 ? FINISHED : NONE;
    }
}
