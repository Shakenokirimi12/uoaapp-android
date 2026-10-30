package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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
        // versionCode 7 のユーザー名そのままのキーも、accountId のキーへ移っている。
        assertNull(store.values.get("data@s1300001"));
        AttendanceManager.AttendanceSummary summary = attendance.getAttendance("101");
        assertEquals(1, summary.present);
        assertEquals(1, summary.late);
    }

    /** キーにユーザー名を書かない。accountId は SHA-256 の先頭 16 バイト (iOS と同じ値)。 */
    @Test
    public void keysDoNotContainTheUsername() {
        MapStore store = new MapStore();
        AttendanceManager attendance = new AttendanceManager(store, () -> " s1300001 ");
        attendance.addHistory("101", AttendanceManager.Status.PRESENT, new Date(), "manual");
        for (String key : store.values.keySet()) assertFalse(key, key.contains("s1300001"));
        // printf s1300001 | shasum -a 256 の先頭 32 文字。iOS の AttendanceOwner.keySuffix と同じ。
        assertEquals("916500d31be2273bce7d0f0b51de8cb0", AttendanceManager.accountId("s1300001"));
        assertEquals("data@916500d31be2273bce7d0f0b51de8cb0", AttendanceManager.keyFor("s1300001"));
        assertTrue(AttendanceManager.isAccountId(AttendanceManager.accountId("s1300001")));
        assertTrue(store.values.containsKey(AttendanceManager.keyFor("s1300001")));
    }

    /**
     * versionCode 7 のキーの移行は、足した記録の書き込みと元のキーの削除が 1 回。削除が無かった (止まった) ように
     * 元のキーを戻してもう一度移しても、同じ id の記録は二重にならない。
     */
    @Test
    public void rawKeyMigrationIsIdempotent() {
        MapStore store = new MapStore();
        String raw = "{\"101\":{\"history\":[{\"id\":\"a\",\"date\":\"2026-10-01\","
                + "\"status\":\"PRESENT\",\"timestamp\":1,\"source\":\"manual\"}]}}";
        store.values.put("data@s1300001", raw);
        new AttendanceManager(store, () -> "s1300001").migrateLegacyRecords();
        store.values.put("data@s1300001", raw);
        AttendanceManager again = new AttendanceManager(store, () -> "s1300001");
        again.migrateLegacyRecords();
        assertNull(store.values.get("data@s1300001"));
        assertEquals(1, again.getAttendance("101").present);
    }

    /** 合わせた結果、同じ日の記録が 2 つになったら後に付けた方を残す (同じ授業を 2 回数えない)。 */
    @Test
    public void mergeKeepsOneRecordPerDate() {
        MapStore store = new MapStore();
        store.values.put("data", "{\"101\":{\"history\":[{\"id\":\"old\",\"date\":\"2026-10-01\","
                + "\"status\":\"ABSENT\",\"timestamp\":1,\"source\":\"manual\"}]}}");
        store.values.put(AttendanceManager.keyFor("s1300001"), "{\"101\":{\"history\":[{\"id\":\"new\","
                + "\"date\":\"2026-10-01\",\"status\":\"PRESENT\",\"timestamp\":2,\"source\":\"manual\"}]}}");
        AttendanceManager attendance = new AttendanceManager(store, () -> "s1300001");
        AttendanceManager.AttendanceSummary summary = attendance.getAttendance("101");
        assertEquals(1, summary.history.size());
        assertEquals(1, summary.present);
        assertEquals(0, summary.absent);
    }

    /** 同じ日なら、後から付いた「欠席？（要確認）」より確かめた記録を残す。 */
    @Test
    public void mergePrefersAConfirmedRecordOverAnUnconfirmedAbsence() {
        MapStore store = new MapStore();
        store.values.put(AttendanceManager.keyFor("s1300001"), "{\"101\":{\"history\":[{\"id\":\"mine\","
                + "\"date\":\"2026-10-01\",\"status\":\"PRESENT\",\"timestamp\":1,\"source\":\"manual\"}]}}");
        AttendanceManager attendance = new AttendanceManager(store, () -> "s1300001");
        attendance.importRecords("s1300001", "{\"101\":{\"history\":[{\"id\":\"auto\",\"date\":\"2026-10-01\","
                + "\"status\":\"ABSENT_UNCONFIRMED\",\"timestamp\":2,\"source\":\"auto\"}]}}");
        AttendanceManager.AttendanceSummary summary = attendance.getAttendance("101");
        assertEquals(1, summary.history.size());
        assertEquals("PRESENT", summary.history.get(0).status);
    }
}
