package com.shakenokirimi12.uoa_app.data.models;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GroupedClass {

    private static final int[][] CLASS_PERIODS = {
            {1,  9, 0,  9, 50},
            {2,  9, 50, 10, 40},
            {3,  10, 50, 11, 40},
            {4,  11, 40, 12, 30},
            {5,  13, 20, 14, 10},
            {6,  14, 10, 15, 0},
            {7,  15, 10, 16, 0},
            {8,  16, 0,  16, 50},
            {9,  17, 0,  17, 50},
            {10, 17, 50, 18, 40},
            {11, 18, 50, 19, 40},
    };

    private final String summary;
    private final String location;
    private final Date dtstart;
    private final Date dtend;
    private final List<Integer> periods;
    private final int eventCount;

    public GroupedClass(String summary, String location, Date dtstart, Date dtend,
                        List<Integer> periods, int eventCount) {
        this.summary = summary;
        this.location = location;
        this.dtstart = dtstart;
        this.dtend = dtend;
        this.periods = periods;
        this.eventCount = eventCount;
    }

    public String getSummary() { return summary; }
    public String getLocation() { return location != null ? location : ""; }
    public Date getDtstart() { return dtstart; }
    public Date getDtend() { return dtend; }
    public List<Integer> getPeriods() { return periods; }
    public int getEventCount() { return eventCount; }

    public long getDurationMinutes() {
        if (dtstart == null || dtend == null) return 0;
        return (dtend.getTime() - dtstart.getTime()) / (60 * 1000);
    }

    public boolean isActiveAt(Date date) {
        if (dtstart == null || dtend == null) return false;
        long t = date.getTime();
        return t >= dtstart.getTime() && t <= dtend.getTime();
    }

    public String formatPeriods() {
        if (periods == null || periods.isEmpty()) return "";
        if (periods.size() == 1) return periods.get(0) + "限";
        List<Integer> sorted = new ArrayList<>(periods);
        Collections.sort(sorted);
        return sorted.get(0) + "-" + sorted.get(sorted.size() - 1) + "限";
    }

    // --- Static grouping logic ---

    public static List<GroupedClass> groupBySubject(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) return new ArrayList<>();

        Map<String, List<CalendarEvent>> groups = new LinkedHashMap<>();
        for (CalendarEvent e : events) {
            if (e.getSummary() == null || e.getDtstart() == null || e.getDtend() == null) continue;
            String key = e.getSummary().trim();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        List<GroupedClass> result = new ArrayList<>();

        for (Map.Entry<String, List<CalendarEvent>> entry : groups.entrySet()) {
            String summary = entry.getKey();
            List<CalendarEvent> sorted = new ArrayList<>(entry.getValue());
            sorted.sort((a, b) -> a.getDtstart().compareTo(b.getDtstart()));

            List<CalendarEvent> currentGroup = new ArrayList<>();
            currentGroup.add(sorted.get(0));

            for (int i = 1; i < sorted.size(); i++) {
                CalendarEvent prev = sorted.get(i - 1);
                CalendarEvent curr = sorted.get(i);

                long gap = curr.getDtstart().getTime() - prev.getDtend().getTime();

                if (gap <= 20 * 60 * 1000) {
                    currentGroup.add(curr);
                } else {
                    result.add(createGrouped(summary, currentGroup));
                    currentGroup = new ArrayList<>();
                    currentGroup.add(curr);
                }
            }

            if (!currentGroup.isEmpty()) {
                result.add(createGrouped(summary, currentGroup));
            }
        }

        result.sort((a, b) -> {
            if (a.dtstart == null && b.dtstart == null) return 0;
            if (a.dtstart == null) return 1;
            if (b.dtstart == null) return -1;
            return a.dtstart.compareTo(b.dtstart);
        });
        return result;
    }

    private static GroupedClass createGrouped(String summary, List<CalendarEvent> events) {
        List<Integer> periods = new ArrayList<>();
        for (CalendarEvent e : events) {
            int p = getPeriodNumber(e.getDtstart());
            if (p > 0) periods.add(p);
        }

        return new GroupedClass(
                summary,
                events.get(0).getLocation(),
                events.get(0).getDtstart(),
                events.get(events.size() - 1).getDtend(),
                periods,
                events.size()
        );
    }

    private static int getPeriodNumber(Date date) {
        if (date == null) return 0;
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int time = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        for (int[] p : CLASS_PERIODS) {
            int startMin = p[1] * 60 + p[2];
            if (Math.abs(time - startMin) <= 5) {
                return p[0];
            }
        }
        return 0;
    }
}
