package com.shakenokirimi12.uoa_app.services.attendance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

/**
 * どの授業に自動の欠席を付けるか (iOS の AutoAbsencePlanner と同じ規則)。
 *
 * - 時間割に載っている授業だけを見る。休講・オンラインの授業は見ない。
 * - 授業が終わった後に取った時間割でなければ見ない (それより前に取った時間割では、後から出た休講を知らない)。
 * - 科目が 1 つに決まらない授業は見ない。
 * - その日に記録 (手動・自動) がある科目と、前に自動で欠席を付けた科目は見ない (消されても付け直さない)。
 * - 同じ日に同じ科目の授業が複数あれば、全部が欠席のときだけ付ける。
 */
public final class AutoAbsencePlanner {
    private AutoAbsencePlanner() {}

    /** 自動出席と同じ、授業の開始の何分前から到着を出席とみなすか (LocationGeofenceService)。 */
    public static final long EARLY_WINDOW_MS = 15 * 60_000L;
    /** 自動の欠席を付けに行く範囲 (授業の終わりから)。 */
    public static final long LOOKBACK_MS = 7L * 24 * 60 * 60_000L;

    public static final class Course {
        final String id;
        final String fullname;
        final String shortname;

        public Course(@NonNull String id, @Nullable String fullname, @Nullable String shortname) {
            this.id = id;
            this.fullname = fullname != null ? fullname : "";
            this.shortname = shortname != null ? shortname : "";
        }
    }

    public static final class Mark {
        public final String courseId;
        /** AttendanceManager と同じ yyyy-MM-dd。 */
        public final String date;
        /** その日のその科目の最後の授業の終わり。履歴の並び順に使う。 */
        public final long lastClassEnd;

        Mark(String courseId, String date, long lastClassEnd) {
            this.courseId = courseId;
            this.date = date;
            this.lastClassEnd = lastClassEnd;
        }

        @NonNull public String key() { return key(courseId, date); }

        @NonNull public static String key(@NonNull String courseId, @NonNull String date) { return courseId + "|" + date; }
    }

    @NonNull
    public static List<Mark> plan(
            @NonNull List<CalendarEvent> events,
            @NonNull List<Course> courses,
            long timetableFetchedAt,
            @NonNull Map<String, Set<String>> recordedDates,
            @NonNull Set<String> alreadyMarked,
            @NonNull List<PresenceEvidence.Entry> evidence,
            long now,
            @NonNull TimeZone timeZone) {
        List<Mark> marks = new ArrayList<>();
        if (timetableFetchedAt <= 0) return marks;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(timeZone);

        long earliest = now - LOOKBACK_MS;
        Map<String, List<CalendarEvent>> groups = new LinkedHashMap<>();
        Map<String, String[]> groupIds = new LinkedHashMap<>();
        for (CalendarEvent ev : events) {
            if (ev.getDtstart() == null || ev.getDtend() == null) continue;
            long start = ev.getDtstart().getTime();
            long end = ev.getDtend().getTime();
            if (end <= start || end < earliest) continue;
            if (timetableFetchedAt < end) continue;
            if (isCancelled(ev) || isOnline(ev)) continue;
            Course course = uniqueCourse(ev.getSummary(), courses);
            if (course == null) continue;
            String date = fmt.format(ev.getDtstart());
            String key = Mark.key(course.id, date);
            List<CalendarEvent> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(key, list);
                groupIds.put(key, new String[] {course.id, date});
            }
            list.add(ev);
        }

        for (Map.Entry<String, List<CalendarEvent>> g : groups.entrySet()) {
            String[] ids = groupIds.get(g.getKey());
            String courseId = ids[0];
            String date = ids[1];
            if (alreadyMarked.contains(g.getKey())) continue;
            Set<String> recorded = recordedDates.get(courseId);
            if (recorded != null && recorded.contains(date)) continue;
            boolean allAbsent = true;
            long lastEnd = 0;
            for (CalendarEvent ev : g.getValue()) {
                long start = ev.getDtstart().getTime();
                long end = ev.getDtend().getTime();
                lastEnd = Math.max(lastEnd, end);
                PresenceEvidence.Verdict v = PresenceEvidence.verdict(
                        start - EARLY_WINDOW_MS, end, evidence, now, PresenceEvidence.DELIVERY_MARGIN_MS);
                if (v != PresenceEvidence.Verdict.ABSENT) { allAbsent = false; break; }
            }
            if (allAbsent) marks.add(new Mark(courseId, date, lastEnd));
        }
        Collections.sort(marks, (a, b) -> !a.date.equals(b.date) ? a.date.compareTo(b.date) : a.courseId.compareTo(b.courseId));
        return marks;
    }

    /** 休講と分かる授業。ICS の STATUS か、授業名・場所・説明の「休講」。 */
    static boolean isCancelled(@NonNull CalendarEvent ev) {
        // STATUS に引数が付くと値の前に「引数:」が残る (CampusSquareService.extractIcsField) ので、含むかで見る。
        if (ev.getStatus() != null && ev.getStatus().toUpperCase(Locale.ROOT).contains("CANCELLED")) return true;
        return text(ev).contains("休講");
    }

    /** キャンパスに来なくてよい授業。来なかったことを欠席の根拠にできない。 */
    static boolean isOnline(@NonNull CalendarEvent ev) {
        String t = text(ev).toLowerCase(Locale.ROOT);
        return t.contains("オンライン") || t.contains("遠隔") || t.contains("online");
    }

    private static String text(CalendarEvent ev) {
        return Objects.toString(ev.getSummary(), "") + " " + ev.getLocation() + " " + ev.getDescription();
    }

    /** 授業名に当たる科目が 1 つだけならそれを返す。名前の空の科目は使わない (どの授業にも当たってしまう)。 */
    @Nullable
    static Course uniqueCourse(@Nullable String summary, @NonNull List<Course> courses) {
        String event = normalize(summary);
        if (event.isEmpty()) return null;
        Course found = null;
        for (Course c : courses) {
            String full = normalize(c.fullname);
            String shortName = normalize(c.shortname);
            boolean match = (!full.isEmpty() && (event.contains(full) || full.contains(event)))
                    || (!shortName.isEmpty() && event.contains(shortName));
            if (!match) continue;
            if (found != null) return null;
            found = c;
        }
        return found;
    }

    /** iOS と同じく、全角半角をそろえ (NFKC)、小文字にし、空白 (全角の空白を含む) を除く。 */
    private static String normalize(@Nullable String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
