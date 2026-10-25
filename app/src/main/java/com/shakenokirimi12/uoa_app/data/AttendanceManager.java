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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AttendanceManager {
    private static final String PREF_NAME = "attendance_data_v2";
    private static final String KEY_DATA = "data";

    private static AttendanceManager instance;
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    /**
     * ABSENT_UNCONFIRMED は自動で付けた「欠席？（要確認）」。位置情報では来なかったように見えたが、それだけでは
     * 欠席と言い切れない (通知の取りこぼし・ジオフェンスの外の建物)。利用者が選ぶまで、どの集計にも入れない。
     */
    public enum Status { PRESENT, ABSENT, LATE, ABSENT_UNCONFIRMED }

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

    // 画面 (手動の記録) と自動出席・自動の欠席 (別スレッド) が同じ保存先を読み書きする。読んで書き戻すまでを
    // ひとまとまりにしないと、片方の書き込みが消える。public の読み書きは synchronized にする。

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

    /** その科目に今日の記録が既にあるか。自動出席の重複登録と、手動記録の上書きを防ぐ。 */
    public synchronized boolean hasRecordToday(String courseId) {
        Record r = loadAll().get(courseId);
        if (r == null) return false;
        String today = dateFmt.format(new Date());
        for (HistoryItem h : r.history) if (today.equals(h.date)) return true;
        return false;
    }

    /** 今日の記録を消す (デバッグのテスト科目の後始末用)。 */
    public synchronized void removeToday(String courseId) {
        Map<String, Record> all = loadAll();
        Record r = all.get(courseId);
        if (r == null) return;
        String today = dateFmt.format(new Date());
        r.history.removeIf(h -> today.equals(h.date));
        if (r.history.isEmpty()) all.remove(courseId);
        saveAll(all);
    }

    public synchronized AttendanceSummary getAttendance(String courseId) {
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

    public synchronized void addHistory(String courseId, Status status, Date date, String source) {
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

    /** 記録の状態を手で変える (自動の欠席を出席に直す、など)。手で変えたものは手動の記録になる。 */
    public synchronized void updateStatus(String courseId, String historyId, Status status) {
        Map<String, Record> all = loadAll();
        Record r = all.get(courseId);
        if (r == null) return;
        for (HistoryItem h : r.history) {
            if (h.id.equals(historyId)) {
                h.status = status.name();
                h.source = "manual";
            }
        }
        saveAll(all);
    }

    /** 科目ごとの、記録 (手動・自動) がある日付。 */
    public synchronized Map<String, Set<String>> recordedDates() {
        Map<String, Set<String>> out = new HashMap<>();
        for (Map.Entry<String, Record> e : loadAll().entrySet()) {
            Set<String> dates = new HashSet<>();
            for (HistoryItem h : e.getValue().history) dates.add(h.date);
            out.put(e.getKey(), dates);
        }
        return out;
    }

    /**
     * 「欠席？（要確認）」を付ける。その日に記録が既にあれば何もしない (手動の記録を上書きしない)。
     * @param classEnd 授業の終わり。履歴はこの順に並ぶので、付けた時刻ではなく授業の時刻にする。
     * @return 付けた記録の id。付けなかったら null。
     */
    @androidx.annotation.Nullable
    public synchronized String addUnconfirmedAbsenceIfUnrecorded(String courseId, String date, long classEnd) {
        Map<String, Record> all = loadAll();
        Record r = all.get(courseId);
        if (r == null) { r = new Record(); all.put(courseId, r); }
        for (HistoryItem h : r.history) if (date.equals(h.date)) return null;
        HistoryItem item = new HistoryItem();
        item.id = UUID.randomUUID().toString();
        item.date = date;
        item.status = Status.ABSENT_UNCONFIRMED.name();
        item.timestamp = classEnd;
        item.source = "auto";
        r.history.add(item);
        saveAll(all);
        return item.id;
    }

    public synchronized void removeHistory(String courseId, String historyId) {
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
