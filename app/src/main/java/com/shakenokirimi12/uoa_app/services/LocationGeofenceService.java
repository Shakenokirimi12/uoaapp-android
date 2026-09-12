package com.shakenokirimi12.uoa_app.services;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingEvent;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.AttendanceManager;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LocationGeofenceService {

    private static final String TAG = "LocationGeofence";
    private static final String GEOFENCE_ID = "university-campus";
    private static final long COOLDOWN_MS = 30 * 60 * 1000;

    /** @return ジオフェンスの登録を要求したら true。killswitch や権限不足で何もしなかったら false。 */
    public static boolean startGeofencing(Context ctx, double lat, double lng, float radius) {
        // 出席登録の仕様変更などで誤登録が起きうるとき、リモートから止めるための killswitch。
        if (!AppConfigService.getInstance().isFeatureEnabled("auto_attendance_enabled")) {
            Log.w(TAG, "Auto attendance disabled by remote flag");
            return false;
        }
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            return false;
        }

        GeofencingClient client = LocationServices.getGeofencingClient(ctx);

        Geofence geofence = new Geofence.Builder()
                .setRequestId(GEOFENCE_ID)
                .setCircularRegion(lat, lng, radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build();

        GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build();

        client.addGeofences(request, getGeofencePendingIntent(ctx))
                .addOnSuccessListener(v -> Log.d(TAG, "Geofence added"))
                .addOnFailureListener(e -> Log.e(TAG, "Geofence add failed", e));
        return true;
    }

    public static void stopGeofencing(Context ctx) {
        GeofencingClient client = LocationServices.getGeofencingClient(ctx);
        client.removeGeofences(getGeofencePendingIntent(ctx));
    }

    private static PendingIntent getGeofencePendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, GeofenceReceiver.class);
        return PendingIntent.getBroadcast(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    public static class GeofenceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            GeofencingEvent event = GeofencingEvent.fromIntent(intent);
            if (event == null || event.hasError()) return;

            if (event.getGeofenceTransition() == Geofence.GEOFENCE_TRANSITION_ENTER) {
                Log.d(TAG, "Entered university geofence");
                checkAutoAttendance(context);
            }
        }
    }

    /**
     * ジオフェンスに入ったときに、今の授業を特定して出席を登録する。
     *
     * 以前は "data_cache" という名前の SharedPreferences を読んでいたが、DataCache が書くのは
     * "uoa_data_cache" で、常に空のまま即 return していた (= 自動出席が一度も動いていなかった)。
     * DataCache を直接読む。
     *
     * 候補の選び方は iOS の checkAutoAttendance と同じ: 開始 15 分前〜終了までを対象にし、
     * 進行中の授業を優先 (進行中が複数なら遅く始まった方)、次に直近で始まる授業。
     * 今日の記録が既にある科目は飛ばす (重複登録と手動記録の上書きを防ぐ)。
     */
    static void checkAutoAttendance(Context ctx) {
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        if (!prefs.isAutoAttendanceEnabled()) return;
        if (!AppConfigService.getInstance().isFeatureEnabled("auto_attendance_enabled")) return;

        SharedPreferences sp = ctx.getSharedPreferences("geofence", Context.MODE_PRIVATE);
        long lastNotified = sp.getLong("last_geofence_time", 0);
        long now = System.currentTimeMillis();
        if (lastNotified > 0 && (now - lastNotified) < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active");
            return;
        }

        com.shakenokirimi12.uoa_app.data.DataCache cache = com.shakenokirimi12.uoa_app.data.DataCache.getInstance(ctx);
        List<CalendarEvent> events = cache.loadEvents();
        List<MoodleCourse> courses = cache.loadCourses();
        if (events == null || courses == null || events.isEmpty() || courses.isEmpty()) {
            Log.d(TAG, "No cached events/courses");
            return;
        }

        List<CalendarEvent> candidates = new java.util.ArrayList<>();
        for (CalendarEvent ev : events) {
            if (ev.getDtstart() == null || ev.getDtend() == null) continue;
            long start = ev.getDtstart().getTime();
            long end = ev.getDtend().getTime();
            if (now >= start - 15 * 60_000L && now <= end) candidates.add(ev);
        }
        candidates.sort((a, b) -> {
            boolean aIn = now >= a.getDtstart().getTime();
            boolean bIn = now >= b.getDtstart().getTime();
            if (aIn != bIn) return aIn ? -1 : 1;
            return aIn ? b.getDtstart().compareTo(a.getDtstart()) : a.getDtstart().compareTo(b.getDtstart());
        });

        AttendanceManager attendance = AttendanceManager.getInstance(ctx);
        for (CalendarEvent ev : candidates) {
            String eventNorm = normalize(ev.getSummary());
            MoodleCourse matched = null;
            for (MoodleCourse c : courses) {
                String cNorm = normalize(c.getFullname());
                String sNorm = normalize(c.getShortname());
                if (cNorm.isEmpty() && sNorm.isEmpty()) continue;
                if ((!cNorm.isEmpty() && (eventNorm.contains(cNorm) || cNorm.contains(eventNorm)))
                        || (!sNorm.isEmpty() && eventNorm.contains(sNorm))) {
                    matched = c;
                    break;
                }
            }
            if (matched == null) continue;
            String courseId = String.valueOf(matched.getId());
            if (attendance.hasRecordToday(courseId)) {
                Log.d(TAG, "Already recorded today: " + courseId);
                continue;
            }

            attendance.addHistory(courseId, AttendanceManager.Status.PRESENT, new Date(), "auto");
            sp.edit().putLong("last_geofence_time", now).apply();
            notifyRegistered(ctx, matched);
            return;
        }
        Log.d(TAG, "No class to register now");
    }

    private static void notifyRegistered(Context ctx, MoodleCourse matched) {
        try {
            // チャンネルは ClassNotificationService が作るが、それが一度も起動していない端末では
            // 存在せず通知が黙って落ちる。ここでも作る (既にあれば何もしない)。
            android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    "class_ongoing", "授業", android.app.NotificationManager.IMPORTANCE_LOW));
            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, "class_ongoing")
                    .setSmallIcon(R.drawable.ic_stat_calendar)
                    .setContentTitle("自動出席登録完了")
                    .setContentText(matched.getFullname() + " の出席を自動登録しました。")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);

            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(ctx).notify(2001, builder.build());
            }
        } catch (Exception e) {
            Log.w(TAG, "notify failed: " + e.getMessage());
        }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
