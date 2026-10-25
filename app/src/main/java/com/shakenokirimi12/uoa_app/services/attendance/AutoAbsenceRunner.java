package com.shakenokirimi12.uoa_app.services.attendance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.AttendanceManager;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;
import com.shakenokirimi12.uoa_app.services.AppConfigService;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 終わった授業に、来なかったと言えるものがあれば「欠席？（要確認）」を付け、出席したかを通知で尋ねる。
 * 位置情報だけでは欠席を証明できないので、利用者が選ぶまで欠席にはしない。時間割を保存した後・同期の後・
 * ジオフェンスの状態が届いたときに呼ぶ。何度呼んでも同じ結果になる (付けた分を覚え、その日に記録がある科目には付けない)。
 */
public final class AutoAbsenceRunner {
    private static final String TAG = "AutoAbsence";
    private static final String PREF_NAME = "auto_absence";
    private static final String KEY_MARKED = "marked_v1";
    private static final Gson gson = new Gson();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AutoAbsenceRunner() {}

    /** 画面のスレッドから呼ぶとき。 */
    public static void evaluateAsync(@NonNull Context ctx) {
        Context app = ctx.getApplicationContext();
        executor.execute(() -> evaluate(app));
    }

    public static void evaluate(@NonNull Context ctx) {
        try {
            evaluateUnchecked(ctx);
        } catch (RuntimeException e) {
            // 欠席を付けられなかっただけ。画面のスレッドや同期の結果まで巻き込まない。
            Log.e(TAG, "Auto absence evaluation failed", e);
        }
    }

    private static void evaluateUnchecked(@NonNull Context ctx) {
        // 前の終了の理由を読めない版では、強制停止で監視が止まっていたかを確かめられない。
        if (Build.VERSION.SDK_INT < 30) return;
        if (!PreferenceManager.getInstance(ctx).isAutoAttendanceEnabled()) return;
        if (!AppConfigService.getInstance().isFeatureEnabled("auto_attendance_enabled")) return;

        // 今の監視の状態を残してから判定する (授業の後の確認として使う)。
        PresenceEvidenceLog.checkpoint(ctx);

        DataCache cache = DataCache.getInstance(ctx);
        List<CalendarEvent> events = cache.loadEvents();
        List<MoodleCourse> moodleCourses = cache.loadCourses();
        if (events == null || moodleCourses == null) return;
        List<AutoAbsencePlanner.Course> courses = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (MoodleCourse c : moodleCourses) {
            courses.add(new AutoAbsencePlanner.Course(String.valueOf(c.getId()), c.getFullname(), c.getShortname()));
            names.put(String.valueOf(c.getId()), c.getFullname() != null ? c.getFullname() : "");
        }

        long now = System.currentTimeMillis();
        AttendanceManager attendance = AttendanceManager.getInstance(ctx);
        synchronized (AutoAbsenceRunner.class) {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            Map<String, Long> marked = loadMarked(sp, now);
            // 読めなければ付けない。付けた分が分からないと、消された欠席を付け直してしまう。
            if (marked == null) return;
            List<AutoAbsencePlanner.Mark> marks = AutoAbsencePlanner.plan(
                    events, courses, cache.eventsWithStatusFetchedAt(), attendance.recordedDates(),
                    marked.keySet(), PresenceEvidenceLog.entries(ctx), now, TimeZone.getDefault());
            for (AutoAbsencePlanner.Mark mark : marks) {
                String historyId = attendance.addUnconfirmedAbsenceIfUnrecorded(mark.courseId, mark.date, mark.lastClassEnd);
                if (historyId == null) continue;
                marked.put(mark.key(), now);
                Log.d(TAG, "Marked unconfirmed absence for course " + mark.courseId + " on " + mark.date);
                String name = names.get(mark.courseId);
                AttendanceConfirmation.ask(ctx, mark.courseId, historyId, name != null ? name : "", mark.key());
            }
            sp.edit().putString(KEY_MARKED, gson.toJson(marked)).apply();
        }
    }

    /** 前に付けた分。判定の範囲より古いものは、付け直されることが無いので捨てる。 */
    @androidx.annotation.Nullable
    private static Map<String, Long> loadMarked(SharedPreferences sp, long now) {
        Map<String, Long> out = new HashMap<>();
        String json = sp.getString(KEY_MARKED, null);
        if (json == null) return out;
        try {
            Type type = new TypeToken<Map<String, Long>>() {}.getType();
            Map<String, Long> all = gson.fromJson(json, type);
            if (all == null) return null;
            long cutoff = now - PresenceEvidenceLog.RETENTION_MS;
            for (Map.Entry<String, Long> e : all.entrySet()) {
                if (e.getValue() != null && e.getValue() >= cutoff) out.put(e.getKey(), e.getValue());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read marked absences", e);
            return null;
        }
        return out;
    }
}
