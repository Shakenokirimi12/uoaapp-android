package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** CampusSquare のカレンダーから、毎週の時間割を作る。iOS の TimetableDerivation と同じ規則。 */
public final class TimetableDerivation {
    /** 時限は大学の時刻で決まるので、端末のタイムゾーンによらず日本時間で読む。 */
    public static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private TimetableDerivation() {}

    private static final class Key {
        final int day, start, end;
        final String title;

        Key(int day, int start, int end, String title) {
            this.day = day; this.start = start; this.end = end; this.title = title;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return day == k.day && start == k.start && end == k.end && title.equals(k.title);
        }

        @Override
        public int hashCode() { return Objects.hash(day, start, end, title); }
    }

    /**
     * 今週の月曜の 1 週前から 5 週後までを見て、同じ曜日・時限・授業名が 2 週以上にあるものを毎週の授業とする
     * (1 回だけの補講や試験を入れない)。授業のある週が 1 週しか無い (学期の始めや終わり) ときは 1 回でも入れる。
     * 教室は一番多く出てきたもの (同数なら文字列の順)。
     */
    @NonNull
    public static List<SharedClass> weeklyClasses(@NonNull List<CalendarEvent> events, long nowMs) {
        LocalDate thisWeek = Instant.ofEpochMilli(nowMs).atZone(TOKYO).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate windowStart = thisWeek.minusDays(7);
        long fromMs = windowStart.atStartOfDay(TOKYO).toInstant().toEpochMilli();
        long toMs = thisWeek.plusDays(35).atStartOfDay(TOKYO).toInstant().toEpochMilli();

        // 日付と授業名ごとに、時限 → 教室 (最初に出てきたもの)。
        Map<String, LocalDate> dates = new LinkedHashMap<>();
        Map<String, String> titles = new HashMap<>();
        Map<String, TreeMap<Integer, String>> periods = new HashMap<>();
        for (CalendarEvent e : events) {
            if (e == null || e.getDtstart() == null || e.getSummary() == null) continue;
            long t = e.getDtstart().getTime();
            if (t < fromMs || t >= toMs) continue;
            ZonedDateTime start = Instant.ofEpochMilli(t).atZone(TOKYO);
            // 1 件で何コマも続く予定 (2〜4限など) は、かかるコマを全部数える。開始だけを見ると最初のコマしか残らない。
            ZonedDateTime end = e.getDtend() == null ? start : Instant.ofEpochMilli(e.getDtend().getTime()).atZone(TOKYO);
            List<Integer> eventPeriods = periodNumbers(start, end);
            if (eventPeriods.isEmpty()) continue;
            String title = SharedTimetable.sanitize(e.getSummary(), SharedTimetable.MAX_TITLE);
            if (title.isEmpty()) continue;
            String room = SharedTimetable.sanitize(e.getLocation(), SharedTimetable.MAX_ROOM);
            LocalDate date = start.toLocalDate();
            String key = date + "|" + title;
            dates.put(key, date);
            titles.put(key, title);
            TreeMap<Integer, String> byPeriod = periods.computeIfAbsent(key, k -> new TreeMap<>());
            for (int period : eventPeriods) byPeriod.putIfAbsent(period, room);
        }

        // 時限ごと (曜日・時限・授業名) に、出てきた週と教室を数える。続いている時限は、決めた教室が同じなら後で 1 コマにまとめる。
        // 1 回ごとの続き方 (2〜4 限など) で数えると、ある週だけ途中で教室が変わったときに、どの続き方も 2 週そろわず授業ごと消える。
        Map<Key, Set<Integer>> weeks = new HashMap<>();
        Map<Key, Map<String, Integer>> rooms = new HashMap<>();
        Set<Integer> weeksWithClasses = new HashSet<>();
        for (Map.Entry<String, LocalDate> entry : dates.entrySet()) {
            LocalDate date = entry.getValue();
            int day = date.getDayOfWeek().getValue();  // 1 = 月 … 7 = 日
            int week = (int) (ChronoUnit.DAYS.between(windowStart, date) / 7);
            weeksWithClasses.add(week);
            for (Map.Entry<Integer, String> p : periods.get(entry.getKey()).entrySet()) {
                Key k = new Key(day, p.getKey(), p.getKey(), titles.get(entry.getKey()));
                weeks.computeIfAbsent(k, x -> new HashSet<>()).add(week);
                rooms.computeIfAbsent(k, x -> new HashMap<>()).merge(p.getValue(), 1, Integer::sum);
            }
        }

        int needed = Math.min(2, weeksWithClasses.size());
        List<Key> kept = new ArrayList<>();
        Map<Key, String> keptRooms = new HashMap<>();
        for (Map.Entry<Key, Set<Integer>> entry : weeks.entrySet()) {
            if (entry.getValue().size() < needed) continue;
            // 一番多い教室。同数なら空でない教室を優先し、その中では文字列の順で決める (iOS と同じ)。
            String room = null;
            int best = -1;
            for (Map.Entry<String, Integer> r : rooms.get(entry.getKey()).entrySet()) {
                if (room == null || better(r.getKey(), r.getValue(), room, best)) {
                    best = r.getValue();
                    room = r.getKey();
                }
            }
            kept.add(entry.getKey());
            keptRooms.put(entry.getKey(), room == null ? "" : room);
        }
        kept.sort((a, b) -> {
            if (a.day != b.day) return Integer.compare(a.day, b.day);
            int t = a.title.compareTo(b.title);
            return t != 0 ? t : Integer.compare(a.start, b.start);
        });
        List<SharedClass> result = new ArrayList<>();
        for (Key k : kept) {
            String room = keptRooms.get(k);
            SharedClass last = result.isEmpty() ? null : result.get(result.size() - 1);
            // 教室が変わる時限では分ける (同じ授業名でも、まとめると後ろの時限の教室が消える)。
            if (last != null && last.day == k.day && last.title.equals(k.title) && last.end == k.start - 1 && last.room.equals(room)) {
                result.set(result.size() - 1, new SharedClass(last.day, last.start, k.start, last.title, room));
            } else {
                result.add(new SharedClass(k.day, k.start, k.start, k.title, room));
            }
        }
        result.sort((a, b) -> {
            if (a.day != b.day) return Integer.compare(a.day, b.day);
            if (a.start != b.start) return Integer.compare(a.start, b.start);
            if (a.end != b.end) return Integer.compare(a.end, b.end);
            return a.title.compareTo(b.title);
        });
        return result.size() > SharedTimetable.MAX_CLASSES ? new ArrayList<>(result.subList(0, SharedTimetable.MAX_CLASSES)) : result;
    }

    private static boolean better(String room, int count, String current, int currentCount) {
        if (count != currentCount) return count > currentCount;
        if (room.isEmpty() != current.isEmpty()) return !room.isEmpty();
        return room.compareTo(current) < 0;
    }

    /** 開始時刻 (0 時からの分) がどの時限の始まりか (前後 5 分まで)。どれでもなければ 0。 */
    public static int periodNumber(int minutesOfDay) {
        for (int[] p : GroupedClass.CLASS_PERIODS) {
            if (Math.abs(minutesOfDay - (p[1] * 60 + p[2])) <= 5) return p[0];
        }
        return 0;
    }

    /**
     * 開始から終了までにかかる時限。開始がどれかの時限の始まり (前後 5 分まで) でなければ空。そこから、終了の 5 分前までに
     * 始まる時限を全部入れる。日をまたぐ・終了が開始より前の予定は最初の時限だけ。iOS の periodNumbers と同じ。
     */
    @NonNull
    static List<Integer> periodNumbers(@NonNull ZonedDateTime start, @NonNull ZonedDateTime end) {
        List<Integer> result = new ArrayList<>();
        int startMin = start.getHour() * 60 + start.getMinute();
        int first = periodNumber(startMin);
        if (first == 0) return result;
        result.add(first);
        int endMin = start.toLocalDate().equals(end.toLocalDate()) && end.isAfter(start)
                ? end.getHour() * 60 + end.getMinute() : startMin;
        for (int[] p : GroupedClass.CLASS_PERIODS) {
            if (p[0] > first && p[1] * 60 + p[2] < endMin - 5) result.add(p[0]);
        }
        return result;
    }
}
