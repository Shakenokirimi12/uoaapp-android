package com.shakenokirimi12.uoa_app.data.timetable;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 時間割の表の行・列と、自分との重なり。iOS の TimetableGrid と同じ規則。 */
public final class TimetableGrid {
    private TimetableGrid() {}

    /** 表の行。大学の授業は 2 時限ずつなので、1〜2 限・3〜4 限… を 1 行にする。11 限は 1 行で 1 時限。{最初, 最後}。 */
    public static final int[][] ALL_ROWS = {{1, 2}, {3, 4}, {5, 6}, {7, 8}, {9, 10}, {11, 11}};

    public enum Overlap { SAME, BOTH_FREE, FRIEND_ONLY, MINE_ONLY, BOTH_BUSY;
        /** フィクスチャと iOS の rawValue に合わせた名前。 */
        @NonNull
        public String key() {
            switch (this) {
                case SAME: return "same";
                case BOTH_FREE: return "bothFree";
                case FRIEND_ONLY: return "friendOnly";
                case MINE_ONLY: return "mineOnly";
                default: return "bothBusy";
            }
        }
    }

    private static final int[] DAY_LABELS = {
            R.string.weekday_short_mon, R.string.weekday_short_tue, R.string.weekday_short_wed,
            R.string.weekday_short_thu, R.string.weekday_short_fri, R.string.weekday_short_sat,
            R.string.weekday_short_sun};

    /** 出す行。1〜8 限はいつも出し、9 限以降は誰かの授業があるときだけ出す。 */
    @SafeVarargs
    @NonNull
    public static List<int[]> rows(@NonNull List<SharedClass>... timetables) {
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < ALL_ROWS.length; i++) {
            int[] row = ALL_ROWS[i];
            boolean used = i < 4;
            for (List<SharedClass> list : timetables) {
                for (SharedClass c : list) if (c.start <= row[1] && c.end >= row[0]) used = true;
            }
            if (used) result.add(row);
        }
        return result;
    }

    /**
     * その曜日の列を、上から並べる箱に分ける (箱ごとの {最初の時限, 最後の時限})。iOS の TimetableGrid.segments と同じ。
     * 授業名と教室が同じ授業が続く時限は、行をまたいでも 1 箱。空いている時限は行ごとの箱で、授業が行の片方の時限だけなら
     * もう片方が 1 時限の空の箱になる。区切りは友達の授業で決める。比べるとき (mine がある) は、時限ごとの重なり
     * (同じ・どちらも授業・友達だけ) が変わるところでも区切る (友達 2〜4 限・自分 2 限だけのとき、1 箱にすると 2〜4 限
     * すべてが「どちらも授業」に見える)。自分の授業名の違いだけでは区切らない。友達が空いている時限は、自分の授業でも区切る
     * (友達が 1〜2 限とも空きで自分が 1 限だけのとき、「1〜2限」の 1 箱にすると自分の授業が 2 時限あるように見える)。
     * 出さない行をはさむ時限はつなげない。
     */
    @NonNull
    public static List<int[]> segments(@NonNull List<SharedClass> friend, int day, @NonNull List<int[]> rows) {
        return segments(friend, null, day, rows);
    }

    @NonNull
    public static List<int[]> segments(@NonNull List<SharedClass> friend, @Nullable List<SharedClass> mine, int day, @NonNull List<int[]> rows) {
        List<int[]> result = new ArrayList<>();
        int[] current = null;
        List<String> currentKey = null;
        int[] currentRow = null;
        for (int[] row : rows) {
            for (int p = row[0]; p <= row[1]; p++) {
                List<String> k = segmentKey(friend, day, p);
                if (mine != null) {
                    if (k.isEmpty()) {
                        // 先頭の \u0001 で、友達の授業の区切りと混ざらないようにする。
                        for (String m : segmentKey(mine, day, p)) k.add("\u0001" + m);
                    } else {
                        // 先頭の \u0002 は重なりの種類。
                        k.add("\u0002" + overlap(friend, mine, day, new int[]{p, p}).name());
                    }
                }
                if (current != null && current[1] + 1 == p && currentKey.equals(k) && (!k.isEmpty() || currentRow == row)) {
                    current[1] = p;
                } else {
                    if (current != null) result.add(current);
                    current = new int[]{p, p};
                    currentKey = k;
                }
                currentRow = row;
            }
        }
        if (current != null) result.add(current);
        return result;
    }

    private static List<String> segmentKey(@NonNull List<SharedClass> friend, int day, int period) {
        List<String> key = new ArrayList<>();
        // 区切りは NUL。授業名と教室は sanitize で制御文字を落としてあるので、中に現れない。
        for (SharedClass c : classes(friend, day, new int[]{period, period})) key.add(c.title + "\u0000" + c.room);
        Collections.sort(key);
        return key;
    }

    /** 出す曜日。月〜金はいつも出し、土日は誰かの授業があるときだけ出す。 */
    @SafeVarargs
    @NonNull
    public static List<Integer> days(@NonNull List<SharedClass>... timetables) {
        Set<Integer> used = new HashSet<>();
        for (List<SharedClass> list : timetables) for (SharedClass c : list) used.add(c.day);
        List<Integer> result = new ArrayList<>();
        for (int d = 1; d <= 7; d++) if (d <= 5 || used.contains(d)) result.add(d);
        return result;
    }

    @NonNull
    public static List<SharedClass> classes(@NonNull List<SharedClass> classes, int day, @NonNull int[] row) {
        List<SharedClass> result = new ArrayList<>();
        for (SharedClass c : classes) if (c.day == day && c.start <= row[1] && c.end >= row[0]) result.add(c);
        result.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start) : a.title.compareTo(b.title));
        return result;
    }

    @NonNull
    public static Overlap overlap(@NonNull List<SharedClass> friend, @NonNull List<SharedClass> mine, int day, @NonNull int[] row) {
        List<SharedClass> f = classes(friend, day, row);
        List<SharedClass> m = classes(mine, day, row);
        if (f.isEmpty() && m.isEmpty()) return Overlap.BOTH_FREE;
        if (m.isEmpty()) return Overlap.FRIEND_ONLY;
        if (f.isEmpty()) return Overlap.MINE_ONLY;
        // 時限ごとに比べる。友達のどの授業も、その時限に自分も同じ授業を持つときだけ「同じ」 (iOS と同じ)。
        // 範囲に同じ授業名があるだけで決めると、友達 2〜4 限・自分 2 限だけの授業や、1 限と 2 限の同名授業が「同じ」になる。
        // 友達が同じ時限に 2 つ持つ (重複) ときは、両方とも自分と同じでなければ「同じ」にしない。
        // 片方だけに授業がある時限があれば「同じ」にしない (友達 1 限 A・自分 1 限 A と 2 限 X の 1〜2 限。iOS と同じ)。
        for (int p = row[0]; p <= row[1]; p++) {
            Set<String> mineTitles = new HashSet<>();
            for (SharedClass c : m) if (c.start <= p && c.end >= p) mineTitles.add(comparableTitle(c.title));
            boolean friendHere = false;
            for (SharedClass c : f) {
                if (c.start > p || c.end < p) continue;
                friendHere = true;
                if (!mineTitles.contains(comparableTitle(c.title))) return Overlap.BOTH_BUSY;
            }
            if (friendHere != !mineTitles.isEmpty()) return Overlap.BOTH_BUSY;
        }
        return Overlap.SAME;
    }

    /** 比べるときの表の箱 (segments) と、その重なり。 */
    public static final class Box {
        @NonNull public final int[] range;
        @NonNull public final Overlap overlap;

        Box(@NonNull int[] range, @NonNull Overlap overlap) {
            this.range = range;
            this.overlap = overlap;
        }
    }

    /**
     * 比べるときの表の箱と重なり。表の上の数と下のまとめは、表と同じ箱で数える
     * (行 (1〜2限) で数えると、1 限がどちらも空きの箱が表にあっても「どちらも空き」に数えない)。iOS の comparedBoxes と同じ。
     */
    @NonNull
    public static List<Box> comparedBoxes(@NonNull List<SharedClass> friend, @NonNull List<SharedClass> mine, int day,
                                          @NonNull List<int[]> rows) {
        List<Box> result = new ArrayList<>();
        for (int[] range : segments(friend, mine, day, rows)) result.add(new Box(range, overlap(friend, mine, day, range)));
        return result;
    }

    /** 同じ授業かを比べるための形。大文字小文字と空白の違いは同じとみなす。 */
    @NonNull
    public static String comparableTitle(@NonNull String title) {
        // 空白は Unicode の空白すべて (全角空白 U+3000 や U+00A0 も) で区切る。iOS の isWhitespace と同じ。
        List<String> parts = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        String lower = title.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (word.length() > 0) { parts.add(word.toString()); word.setLength(0); }
            } else {
                word.appendCodePoint(cp);
            }
        }
        if (word.length() > 0) parts.add(word.toString());
        return String.join(" ", parts);
    }

    @NonNull
    public static String dayLabel(@NonNull Context ctx, int day) {
        return day >= 1 && day <= 7 ? ctx.getString(DAY_LABELS[day - 1]) : "";
    }

    /** 「1〜2限」「11限」。 */
    @NonNull
    public static String rowLabel(@NonNull Context ctx, @NonNull int[] row) {
        return row[0] == row[1]
                ? ctx.getString(R.string.class_period_single, row[0])
                : ctx.getString(R.string.timetable_grid_period_range_format, row[0], row[1]);
    }

    /**
     * 空いているコマの範囲。続いているコマは 1 つ (「1〜6」) にまとめる。
     * 表の全部の行が空いているときは null (文にするときは「終日」)。
     */
    @Nullable
    public static List<int[]> freeRanges(@NonNull List<int[]> free, @NonNull List<int[]> shownRows) {
        if (!free.isEmpty() && free.size() == shownRows.size()) {
            boolean all = true;
            for (int i = 0; i < free.size(); i++) {
                if (!Arrays.equals(free.get(i), shownRows.get(i))) { all = false; break; }
            }
            if (all) return null;
        }
        List<int[]> sorted = new ArrayList<>(free);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] row : sorted) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last[1] + 1 == row[0]) {
                merged.set(merged.size() - 1, new int[]{last[0], row[1]});
            } else {
                merged.add(row);
            }
        }
        return merged;
    }

    /** 空いているコマをまとめた文。続いているコマは「1〜6限」にまとめ、表の全部の行なら「終日」。iOS と同じ。 */
    @NonNull
    public static String freeSummary(@NonNull Context ctx, @NonNull List<int[]> free, @NonNull List<int[]> shownRows) {
        List<int[]> merged = freeRanges(free, shownRows);
        if (merged == null) return ctx.getString(R.string.timetable_grid_all_day);
        List<String> labels = new ArrayList<>();
        for (int[] r : merged) labels.add(rowLabel(ctx, r));
        return String.join(ctx.getString(R.string.timetable_grid_list_separator), labels);
    }

    /** 行の見出し。狭いので「1-2」。 */
    @NonNull
    public static String rowHeader(@NonNull int[] row) {
        return row[0] == row[1] ? String.valueOf(row[0]) : row[0] + "-" + row[1];
    }

    @NonNull
    public static String rowStartTime(@NonNull int[] row) {
        for (int[] p : GroupedClass.CLASS_PERIODS) {
            if (p[0] == row[0]) return String.format(Locale.ROOT, "%d:%02d", p[1], p[2]);
        }
        return "";
    }
}
