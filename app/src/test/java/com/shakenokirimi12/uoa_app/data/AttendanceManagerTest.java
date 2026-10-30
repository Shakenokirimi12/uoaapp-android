package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class AttendanceManagerTest {
    private static final class MapStore implements AttendanceManager.Store {
        final Map<String, String> values = new HashMap<>();
        @Nullable @Override public String get(@NonNull String key) { return values.get(key); }
        @Override public void put(@NonNull String key, @NonNull String value, @Nullable String removeKey) {
            values.put(key, value);
            if (removeKey != null) values.remove(removeKey);
        }
        @NonNull @Override public Set<String> keys() { return new HashSet<>(values.keySet()); }
    }

    /** 記録はアカウントごと。切り替えても前のアカウントの記録は消えず、戻れば見える。 */
    @Test
    public void recordsAreKeptPerAccount() {
        MapStore store = new MapStore();
        AtomicReference<String> user = new AtomicReference<>("s1300001");
        AttendanceManager attendance = new AttendanceManager(store, user::get);

        attendance.addHistory("101", AttendanceManager.Status.PRESENT, new Date(), "manual");
        assertEquals(1, attendance.getAttendance("101").present);

        user.set("s1300002");
        assertEquals(0, attendance.getAttendance("101").history.size());
        attendance.addHistory("101", AttendanceManager.Status.ABSENT, new Date(), "manual");

        user.set("s1300001");
        assertEquals(1, attendance.getAttendance("101").present);
        assertEquals(0, attendance.getAttendance("101").absent);
    }

    /**
     * 分ける前の記録は、ログインしていない間はそのまま残り、次にログインしたアカウントに移る。移す先に記録が
     * あっても、どちらも残る。
     */
    @Test
    public void legacyRecordsMoveToTheNextLoggedInAccount() {
        MapStore store = new MapStore();
        AtomicReference<String> user = new AtomicReference<>("");
        // 前の版が書いた形 ("data" に科目 ID ごと)。
        store.values.put("data", "{\"101\":{\"history\":[{\"id\":\"old\",\"date\":\"2026-10-01\","
                + "\"status\":\"PRESENT\",\"timestamp\":1,\"source\":\"manual\"}]}}");
        store.values.put("data@s1300001", "{\"101\":{\"history\":[{\"id\":\"new\",\"date\":\"2026-10-02\","
                + "\"status\":\"LATE\",\"timestamp\":2,\"source\":\"manual\"}]}}");
        AttendanceManager attendance = new AttendanceManager(store, user::get);

        attendance.migrateLegacyRecords();
        assertEquals(1, attendance.getAttendance("101").present);

        user.set("s1300001");
        attendance.migrateLegacyRecords();
        assertNull(store.values.get("data"));
        AttendanceManager.AttendanceSummary summary = attendance.getAttendance("101");
        assertEquals(1, summary.present);
        assertEquals(1, summary.late);
    }
}
