package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.Context;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableFriend;

/** 継続共有の状態の文面 (docs/timetable-share-v3.md の 10.8)。一覧の行と友達の画面で同じものを出す。 */
final class TimetableShareUi {
    private TimetableShareUi() {}

    @NonNull
    static String stateLabel(@NonNull Context ctx, @NonNull TimetableFriend.State state) {
        switch (state) {
            case SHARING: return ctx.getString(R.string.timetable_state_sharing);
            case WAITING: return ctx.getString(R.string.timetable_state_waiting);
            case STOPPED: return ctx.getString(R.string.timetable_state_stopped);
            default: return ctx.getString(R.string.timetable_state_term_only);
        }
    }

    @NonNull
    static String stateNote(@NonNull Context ctx, @NonNull TimetableFriend.State state) {
        switch (state) {
            case SHARING: return ctx.getString(R.string.timetable_state_note_sharing);
            case WAITING: return ctx.getString(R.string.timetable_state_note_waiting);
            case STOPPED: return ctx.getString(R.string.timetable_state_note_stopped);
            default: return ctx.getString(R.string.timetable_state_note_term_only);
        }
    }
}
