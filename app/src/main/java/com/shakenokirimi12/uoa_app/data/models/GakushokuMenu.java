package com.shakenokirimi12.uoa_app.data.models;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** Response of gakushoku-proxy GET /api/menus/v2. Field names match the JSON (Gson). */
public class GakushokuMenu {
    public List<Week> weeks;
    public List<RegularSet> regularSets;
    public List<String> hours;
    public String fetchedAt;

    public static class Week {
        public String title;
        public int month;
        public int weekIndex;
        public List<Day> days;

        public List<Day> days() { return days != null ? days : Collections.emptyList(); }

        /** Weeks can run past the end of the month; a day number smaller than the one before it belongs to the next month. */
        public int monthOf(Day target) {
            int m = month;
            int prev = -1;
            for (Day d : days()) {
                if (prev >= 0 && d.day < prev) m = m % 12 + 1;
                prev = d.day;
                if (d == target) return m;
            }
            return m;
        }
    }

    public static class Day {
        public String dateString;
        public int day;
        public String weekday;
        public List<String> lunch;
        public List<String> noodles;
        public List<String> fish;
        public List<String> salad;
        public List<String> dinner;
        public boolean hasMenu;

        @Nullable
        public String lunchMain() {
            return lunch != null && !lunch.isEmpty() ? lunch.get(0) : null;
        }

        /** Lunch lines after the main dish. */
        public List<String> lunchSides() {
            return lunch != null && lunch.size() > 1 ? lunch.subList(1, lunch.size()) : Collections.emptyList();
        }

        /** true when there is something to display; hasMenu alone can be set with every category empty. */
        public boolean hasContent() {
            return hasMenu && (lunchMain() != null || !isEmpty(noodles) || !isEmpty(fish)
                    || !isEmpty(salad) || !isEmpty(dinner));
        }

        private static boolean isEmpty(List<String> lines) {
            return lines == null || lines.isEmpty();
        }
    }

    public static class RegularSet {
        public String name;
        public Integer price;
    }

    public List<Week> weeks() { return weeks != null ? weeks : Collections.emptyList(); }

    /** Cafeteria dates are Japan-local; the device may be elsewhere. */
    public static Calendar todayInTokyo() {
        return Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    /** The entry for the given calendar month (1-12) and day, or null when the site does not list it. */
    @Nullable
    public Day findDay(int month, int dayOfMonth) {
        for (Week w : weeks()) {
            for (Day d : w.days()) {
                if (d.day == dayOfMonth && w.monthOf(d) == month) return d;
            }
        }
        return null;
    }

    @Nullable
    public Day today() {
        Calendar c = todayInTokyo();
        return findDay(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * Weeks in chronological order, rotated so the week containing today (or, on a day the site
     * skips, the next upcoming week) comes first: the API lists next week before the current one.
     * Weeks entirely in the past go last.
     */
    public List<Week> weeksSortedFromToday() {
        Calendar c = todayInTokyo();
        return weeksSortedFrom(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    List<Week> weeksSortedFrom(int todayMonth, int todayDay) {
        List<Week> sorted = new ArrayList<>(weeks());
        Collections.sort(sorted, (a, b) -> Long.compare(sortKey(a, todayMonth), sortKey(b, todayMonth)));
        int start = sorted.size();
        for (int i = 0; i < sorted.size(); i++) {
            if (!endsBefore(sorted.get(i), todayMonth, todayDay)) { start = i; break; }
        }
        List<Week> result = new ArrayList<>(sorted.subList(start, sorted.size()));
        result.addAll(sorted.subList(0, start));
        return result;
    }

    /** true when every listed day of the week is before today. */
    private static boolean endsBefore(Week w, int todayMonth, int todayDay) {
        if (w.days().isEmpty()) return true;
        Day last = w.days().get(w.days().size() - 1);
        long lastKey = dayKey(w.monthOf(last), last.day, todayMonth);
        return lastKey < dayKey(todayMonth, todayDay, todayMonth);
    }

    /** Month distance relative to today in [-6, 5] so December/January sort correctly across the year end. */
    private static long dayKey(int month, int day, int todayMonth) {
        int distance = ((month - todayMonth + 6) % 12 + 12) % 12 - 6;
        return distance * 100L + day;
    }

    private static long sortKey(Week w, int todayMonth) {
        if (w.days().isEmpty()) return dayKey(w.month, 0, todayMonth);
        return dayKey(w.month, w.days().get(0).day, todayMonth);
    }
}
