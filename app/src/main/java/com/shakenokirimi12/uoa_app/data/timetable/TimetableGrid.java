package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String[] DAY_LABELS = {"月", "火", "水", "木", "金", "土", "日"};

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
        Set<String> mineTitles = new HashSet<>();
        for (SharedClass c : m) mineTitles.add(comparableTitle(c.title));
        for (SharedClass c : f) if (mineTitles.contains(comparableTitle(c.title))) return Overlap.SAME;
        return Overlap.BOTH_BUSY;
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
    public static String dayLabel(int day) { return day >= 1 && day <= 7 ? DAY_LABELS[day - 1] : ""; }

    /** 「1〜2限」「11限」。 */
    @NonNull
    public static String rowLabel(@NonNull int[] row) {
        return row[0] == row[1] ? row[0] + "限" : row[0] + "〜" + row[1] + "限";
    }

    /** 空いているコマをまとめた文。続いているコマは「1〜6限」にまとめ、表の全部の行なら「終日」。iOS と同じ。 */
    @NonNull
    public static String freeSummary(@NonNull List<int[]> free, @NonNull List<int[]> shownRows) {
        if (!free.isEmpty() && free.size() == shownRows.size()) {
            boolean all = true;
            for (int i = 0; i < free.size(); i++) {
                if (!Arrays.equals(free.get(i), shownRows.get(i))) { all = false; break; }
            }
            if (all) return "終日";
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
        List<String> labels = new ArrayList<>();
        for (int[] r : merged) labels.add(rowLabel(r));
        return String.join("、", labels);
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
