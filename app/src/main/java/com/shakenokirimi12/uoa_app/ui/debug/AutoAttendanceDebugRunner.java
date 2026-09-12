package com.shakenokirimi12.uoa_app.ui.debug;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.LocationServices;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;
import com.shakenokirimi12.uoa_app.services.LocationGeofenceService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 自動出席のテスト。関数を直接呼ぶのではなく、実際に自動出席が動く条件を作る
 * (iOS の AutoAttendanceDebugRunner と同じ考え方):
 *   1. 「今」進行中のテスト用の授業と、それに対応するテスト用の科目をキャッシュに足す
 *   2. クールダウンを解除し、自動出席を ON にする
 *   3. 現在地を中心にジオフェンスを張り直す (INITIAL_TRIGGER_ENTER で即座に発火する)
 * これで GeofenceReceiver -> checkAutoAttendance の本物の経路が走る。
 * 一定時間後に足したデータと設定を元に戻し、本来のジオフェンスを張り直す。
 */
public final class AutoAttendanceDebugRunner {
    private static final int TEST_COURSE_ID = 999_001;
    private static final String TEST_NAME = "自動出席テスト科目";
    private static final long CLEANUP_DELAY_MS = 30_000;

    public interface Callback { void onMessage(String message); }

    private static final String PREF = "auto_attendance_debug";
    private static final String KEY_IN_PROGRESS = "in_progress";
    private static final String KEY_WAS_ENABLED = "was_enabled";
    private static final String KEY_LAST_COOLDOWN = "last_cooldown";

    private AutoAttendanceDebugRunner() {}

    /**
     * 起動時に呼ぶ。テスト中にプロセスが死ぬと 30 秒後の片付けが走らず、自動出席が ON のまま
     * テスト用の授業と科目がキャッシュに残る。記録が残っていれば起動時に片付ける。
     */
    public static void cleanupIfStale(@NonNull Context context) {
        Context ctx = context.getApplicationContext();
        SharedPreferences st = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!st.getBoolean(KEY_IN_PROGRESS, false)) return;
        restore(ctx);
    }

    public static void run(@NonNull Context context, @NonNull Callback cb) {
        Context ctx = context.getApplicationContext();
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            cb.onMessage("位置情報の許可がありません。設定から許可してから再実行してください。");
            return;
        }

        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        DataCache cache = DataCache.getInstance(ctx);
        SharedPreferences geo = ctx.getSharedPreferences("geofence", Context.MODE_PRIVATE);

        SharedPreferences st = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (st.getBoolean(KEY_IN_PROGRESS, false)) {
            cb.onMessage("前回のテストが片付いていません。先に片付けます。");
            restore(ctx);
        }

        // 元に戻すための記録は永続化する。プロセスが死んでも起動時に片付けられるように。
        st.edit()
                .putBoolean(KEY_IN_PROGRESS, true)
                .putBoolean(KEY_WAS_ENABLED, prefs.isAutoAttendanceEnabled())
                .putLong(KEY_LAST_COOLDOWN, geo.getLong("last_geofence_time", 0))
                .apply();

        // 1. テスト用の授業 (5 分前に開始、85 分後に終了) と科目を「足す」。
        //    片付けはスナップショットへの復元ではなく、足した分だけを取り除く。
        //    復元にすると、テスト中に本物の同期が書いた新しいデータを古い内容で上書きする。
        List<CalendarEvent> events = new ArrayList<>(cache.loadEvents());
        long now = System.currentTimeMillis();
        events.add(new CalendarEvent(TEST_NAME, "テスト教室",
                new Date(now - 5 * 60_000L), new Date(now + 85 * 60_000L)));
        List<MoodleCourse> courses = new ArrayList<>(cache.loadCourses());
        courses.add(new MoodleCourse(TEST_COURSE_ID, "TEST", TEST_NAME));
        cache.saveEvents(events);
        cache.saveCourses(courses);

        // 2. クールダウン解除 + ON
        geo.edit().remove("last_geofence_time").apply();
        prefs.setAutoAttendanceEnabled(true);

        // 3. 現在地でジオフェンスを張り直す
        LocationServices.getFusedLocationProviderClient(ctx).getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        restore(ctx);
                        cb.onMessage("現在地が取れませんでした。地図アプリ等で一度位置を取得してから再実行してください。");
                        return;
                    }
                    LocationGeofenceService.stopGeofencing(ctx);
                    boolean registered = LocationGeofenceService.startGeofencing(
                            ctx, location.getLatitude(), location.getLongitude(), 200);
                    if (!registered) {
                        restore(ctx);
                        cb.onMessage("ジオフェンスを登録できませんでした。killswitch (auto_attendance_enabled) が"
                                + "false か、位置情報の許可が足りません。");
                        return;
                    }
                    cb.onMessage("テスト条件を作りました。数秒後に「自動出席登録完了」の通知が出れば成功です。"
                            + "本物の授業が進行中ならそちらが優先されることがあります。"
                            + "30 秒後にテスト用データを片付けます。");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        restore(ctx);
                        cb.onMessage("テスト用データを片付け、元のジオフェンスに戻しました。");
                    }, CLEANUP_DELAY_MS);
                })
                .addOnFailureListener(e -> {
                    restore(ctx);
                    cb.onMessage("現在地の取得に失敗: " + e.getMessage());
                });
    }

    /** 足したテスト用データと設定変更だけを取り除き、元のジオフェンスに戻す。 */
    private static void restore(Context ctx) {
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        DataCache cache = DataCache.getInstance(ctx);
        SharedPreferences geo = ctx.getSharedPreferences("geofence", Context.MODE_PRIVATE);
        SharedPreferences st = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean wasEnabled = st.getBoolean(KEY_WAS_ENABLED, false);
        long lastCooldown = st.getLong(KEY_LAST_COOLDOWN, 0);

        List<CalendarEvent> events = new ArrayList<>(cache.loadEvents());
        events.removeIf(e -> TEST_NAME.equals(e.getSummary()));
        cache.saveEvents(events);
        List<MoodleCourse> courses = new ArrayList<>(cache.loadCourses());
        courses.removeIf(c -> c.getId() == TEST_COURSE_ID);
        cache.saveCourses(courses);

        // テストで登録された出席記録は消す (本物の出席簿に混ぜない)
        com.shakenokirimi12.uoa_app.data.AttendanceManager.getInstance(ctx).removeToday(String.valueOf(TEST_COURSE_ID));
        if (lastCooldown > 0) geo.edit().putLong("last_geofence_time", lastCooldown).apply();
        else geo.edit().remove("last_geofence_time").apply();
        prefs.setAutoAttendanceEnabled(wasEnabled);
        LocationGeofenceService.stopGeofencing(ctx);
        if (wasEnabled) {
            LocationGeofenceService.startGeofencing(ctx, 37.5234, 139.9388, 200);
        }
        st.edit().clear().apply();
    }
}
