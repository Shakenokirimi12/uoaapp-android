package com.shakenokirimi12.uoa_app.data;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前回と今回の時間割 (CampusSquare の ICS) を比べて、休講・教室変更・補講を拾う。
 *
 * ICS には UID が無い (パーサーが読んでいない) ので、「開始時刻 + 科目名」を同じ授業の目印にする。
 * - 前回あって今回消えた → 休講
 * - 今回の科目名に「休講」が入った → 休講 (CampusSquare が消さずに書き換えてくる場合)
 * - 同じ授業で教室が変わった → 教室変更
 * - 今回増えた → 補講・追加
 *
 * 見るのは「いまから LOOKAHEAD_MS 先まで」に始まる授業だけ。終わった授業が一覧から落ちたり、
 * 学期の切り替えで遠い先の授業が入れ替わったりするのを変更として拾わないため。
 */
public final class TimetableDiff {
    public static final long LOOKAHEAD_MS = 14L * 24 * 60 * 60_000L;

    public enum Kind { CANCELLED, ROOM_CHANGED, ADDED }

    public static final class Change {
        public final Kind kind;
        public final CalendarEvent event;
        /** ROOM_CHANGED のときだけ、変更前の教室。 */
        public final String previousLocation;

        Change(Kind kind, CalendarEvent event, String previousLocation) {
            this.kind = kind;
            this.event = event;
            this.previousLocation = previousLocation;
        }
    }

    private TimetableDiff() {}

    @NonNull
    public static List<Change> diff(List<CalendarEvent> before, List<CalendarEvent> after, long nowMs) {
        // 初回 (ログイン直後・ログアウト後) は比べる相手が無い。今回が空なのは取得の不調とみなす
        // (全部が休講になることは無い)。どちらも通知しない。
        if (before == null || before.isEmpty() || after == null || after.isEmpty()) {
            return Collections.emptyList();
        }
        long until = nowMs + LOOKAHEAD_MS;
        Map<String, CalendarEvent> old = index(before, nowMs, until);
        Map<String, CalendarEvent> cur = index(after, nowMs, until);

        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, CalendarEvent> e : old.entrySet()) {
            CalendarEvent was = e.getValue();
            CalendarEvent now = cur.get(e.getKey());
            if (now == null) {
                // 「休講」付きに書き換えられた場合は、下のループで今回側から拾う。
                if (!hasCancelledVariant(cur, was)) {
                    changes.add(new Change(Kind.CANCELLED, was, null));
                }
            } else if (!isCancelled(now) && !now.getLocation().isEmpty()
                    && !normalize(now.getLocation()).equals(normalize(was.getLocation()))) {
                changes.add(new Change(Kind.ROOM_CHANGED, now, was.getLocation()));
            }
        }
        for (Map.Entry<String, CalendarEvent> e : cur.entrySet()) {
            CalendarEvent now = e.getValue();
            // 前回すでに同じ形 (「休講」付きを含む) で出ていたなら新しい知らせではない。
            if (old.containsKey(e.getKey())) {
                continue;
            }
            if (isCancelled(now)) {
                changes.add(new Change(Kind.CANCELLED, now, null));
            } else {
                changes.add(new Change(Kind.ADDED, now, null));
            }
        }
        Collections.sort(changes, (a, b) -> a.event.getDtstart().compareTo(b.event.getDtstart()));
        return changes;
    }

    public static boolean isCancelled(CalendarEvent e) {
        return e.getSummary() != null && e.getSummary().contains("休講");
    }

    /** 科目名から「休講」の印を落としたもの。休講前後で同じ授業だと分かるようにする。 */
    static String baseTitle(CalendarEvent e) {
        String s = e.getSummary() == null ? "" : e.getSummary();
        s = s.replaceAll("[\\[【(（]?休講[\\]】)）]?", "");
        return normalize(s);
    }

    private static boolean hasCancelledVariant(Map<String, CalendarEvent> cur, CalendarEvent was) {
        String base = baseTitle(was);
        long start = was.getDtstart().getTime();
        for (CalendarEvent c : cur.values()) {
            if (isCancelled(c) && c.getDtstart().getTime() == start && baseTitle(c).equals(base)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, CalendarEvent> index(List<CalendarEvent> events, long from, long until) {
        Map<String, CalendarEvent> map = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (CalendarEvent e : events) {
            if (e.getDtstart() == null) continue;
            long start = e.getDtstart().getTime();
            if (start < from || start >= until) continue;
            String key = start + "|" + normalize(e.getSummary());
            // 同じ時刻・同じ科目名が 2 件ある (演習の分割など) ときも潰さずに持つ。
            int n = seen.merge(key, 1, Integer::sum);
            map.put(n == 1 ? key : key + "#" + n, e);
        }
        return map;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('　', ' ').replaceAll("\\s+", " ").trim();
    }
}
