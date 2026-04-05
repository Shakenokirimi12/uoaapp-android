package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class AttendanceManager {
    private static final String PREF_NAME = "attendance_data_v2";
    private static final String KEY_DATA = "data";

    private static AttendanceManager instance;
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public enum Status { PRESENT, ABSENT, LATE }

    public static class HistoryItem {
        public String id;
        public String date;
        public String status;
        public long timestamp;
        public String source;
    }

    public static class Record {
        public int present;
        public int absent;
        public int late;
        public List<HistoryItem> history = new ArrayList<>();
    }

    public static class AttendanceSummary {
        public final int present, absent, late;
        public final List<HistoryItem> history;
        AttendanceSummary(int p, int a, int l, List<HistoryItem> h) {
            present = p; absent = a; late = l; history = h;
        }
    }

    private AttendanceManager(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AttendanceManager getInstance(Context ctx) {
        if (instance == null) instance = new AttendanceManager(ctx);
        return instance;
    }

    private Map<String, Record> loadAll() {
        String json = prefs.getString(KEY_DATA, null);
        if (json == null) return new HashMap<>();
        Type type = new TypeToken<Map<String, Record>>() {}.getType();
        try {
            Map<String, Record> map = gson.fromJson(json, type);
            return map != null ? map : new HashMap<>();
        } catch (Exception e) { return new HashMap<>(); }
    }

    private void saveAll(Map<String, Record> data) {
        prefs.edit().putString(KEY_DATA, gson.toJson(data)).apply();
    }

    public AttendanceSummary getAttendance(String courseId) {
        Map<String, Record> all = loadAll();
        Record r = all.get(courseId);
        if (r == null) return new AttendanceSummary(0, 0, 0, new ArrayList<>());

        int p = 0, a = 0, l = 0;
        for (HistoryItem h : r.history) {
            switch (h.status) {
                case "PRESENT": p++; break;
                case "ABSENT": a++; break;
                case "LATE": l++; break;
            }
        }
        List<HistoryItem> sorted = new ArrayList<>(r.history);
        Collections.sort(sorted, (x, y) -> Long.compare(y.timestamp, x.timestamp));
        return new AttendanceSummary(p, a, l, sorted);
    }

    public void addHistory(String courseId, Status status, Date date, String source) {
        Map<String, Record> all = loadAll();
        Record r = all.get(courseId);
        if (r == null) { r = new Record(); all.put(courseId, r); }

        String dateStr = dateFmt.format(date);
        Iterator<HistoryItem> it = r.history.iterator();
        while (it.hasNext()) {
            if (it.next().date.equals(dateStr)) it.remove();
        }

        HistoryItem item = new HistoryItem();
        item.id = UUID.randomUUID().toString();
        item.date = dateStr;
        item.status = status.name();
        item.timestamp = System.currentTimeMillis();
        item.source = source;
        r.history.add(item);
        saveAll(all);
    }

    public void removeHistory(String courseId, String historyId) {
        Map<String, Record> all = loadAll();
        Record r = all.get(courseId);
        if (r == null) return;
        Iterator<HistoryItem> it = r.history.iterator();
        while (it.hasNext()) {
            if (it.next().id.equals(historyId)) it.remove();
        }
        saveAll(all);
    }
}
