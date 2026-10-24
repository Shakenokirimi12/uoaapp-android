package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;

import java.util.Objects;

/** 1 コマ分の授業。day は 1 = 月 … 7 = 日、start / end は時限 (1〜11、両端を含む)。iOS の SharedClass と同じ。 */
public final class SharedClass {
    public final int day;
    public final int start;
    public final int end;
    @NonNull public final String title;
    @NonNull public final String room;

    public SharedClass(int day, int start, int end, @NonNull String title, @NonNull String room) {
        this.day = day;
        this.start = start;
        this.end = end;
        this.title = title;
        this.room = room;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SharedClass)) return false;
        SharedClass c = (SharedClass) o;
        return day == c.day && start == c.start && end == c.end && title.equals(c.title) && room.equals(c.room);
    }

    @Override
    public int hashCode() { return Objects.hash(day, start, end, title, room); }

    @NonNull
    @Override
    public String toString() { return day + ":" + start + "-" + end + " " + title + " " + room; }
}
