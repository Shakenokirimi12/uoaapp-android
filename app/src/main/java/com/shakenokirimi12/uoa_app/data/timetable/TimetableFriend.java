package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;

/** 受け取った友達の時間割。端末の中だけに置く (サーバーにもアカウントにも結び付けない)。 */
public final class TimetableFriend {
    @NonNull public final String id;
    /** 画面に出す名前。受け取った後に変えられる。 */
    @NonNull public final String name;
    /** 相手が付けて送ってきた名前。同じ人からもう一度届いたときに、同じ友達として上書きするのに使う。 */
    @NonNull public final String sentName;
    public final long receivedAt;
    @NonNull public final SharedTimetable timetable;

    public TimetableFriend(@NonNull String id, @NonNull String name, @NonNull String sentName, long receivedAt,
                           @NonNull SharedTimetable timetable) {
        this.id = id;
        this.name = name;
        this.sentName = sentName;
        this.receivedAt = receivedAt;
        this.timetable = timetable;
    }
}
