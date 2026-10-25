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
import com.google.android.gms.location.GeofenceStatusCodes;
import com.google.android.gms.location.LocationServices;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.AttendanceManager;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;
import com.shakenokirimi12.uoa_app.services.attendance.PresenceEvidence;
import com.shakenokirimi12.uoa_app.services.attendance.PresenceEvidenceLog;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LocationGeofenceService {

    private static final String TAG = "LocationGeofence";
    private static final String GEOFENCE_ID = "university-campus";
    private static final long COOLDOWN_MS = 30 * 60 * 1000;

    // キャンパスの中心と半径。iOS (Constants.Geofencing) と同じ値にする。以前は「同じ値」と書きながら
    // 37.5234 / 139.9388 / 200 m で、iOS (400 m) より狭く、キャンパスの端の建物が外れていた。
    private static final double CAMPUS_LAT = 37.5235;
    private static final double CAMPUS_LNG = 139.9395;
    private static final float CAMPUS_RADIUS_M = 400;

    /** 自動出席が ON のときだけ、キャンパスのジオフェンスを登録し直す。 */
    public static void startIfEnabled(Context ctx) {
        if (!PreferenceManager.getInstance(ctx).isAutoAttendanceEnabled()) return;
        startCampusGeofence(ctx);
    }

    /** @return ジオフェンスの登録を要求したら true。killswitch や権限不足で何もしなかったら false。 */
    public static boolean startCampusGeofence(Context ctx) {
        return startGeofencing(ctx, CAMPUS_LAT, CAMPUS_LNG, CAMPUS_RADIUS_M);
    }

    /** @return ジオフェンスの登録を要求したら true。killswitch や権限不足で何もしなかったら false。 */
    private static boolean startGeofencing(Context ctx, double lat, double lng, float radius) {
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
                // 出た通知も受け、登録したときの状態 (中か外か) も受ける。出入りの両方が分かっていないと、
                // 授業の間ずっと外にいたと言えない (自動の欠席)。
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                .build();

        GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER | GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofence(geofence)
                .build();

        Context app = ctx.getApplicationContext();
        client.addGeofences(request, getGeofencePendingIntent(ctx))
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "Geofence added");
                    recordRegistration(app, true);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Geofence add failed", e);
                    recordRegistration(app, false);
                });
        return true;
    }

    /** 登録の結果を自動の欠席の根拠に残す。残せなくても登録そのものは止めない。 */
    private static void recordRegistration(Context ctx, boolean registered) {
        try {
            PresenceEvidenceLog.setRegistered(ctx, registered);
            PresenceEvidenceLog.checkpoint(ctx);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not record the geofence registration", e);
        }
    }

    public static void stopGeofencing(Context ctx) {
        GeofencingClient client = LocationServices.getGeofencingClient(ctx);
        client.removeGeofences(getGeofencePendingIntent(ctx));
        PresenceEvidenceLog.setRegistered(ctx, false);
        PresenceEvidenceLog.checkpoint(ctx);
    }

    /**
     * 登録したジオフェンスの通知の宛先がまだ生きているか。強制停止すると、プロセスが落ちていても宛先は消え、
     * 終了の理由も残らない (落ちている間に停止されたとき)。宛先が無ければ、通知は届いていなかった。
     */
    public static boolean hasLivePendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, GeofenceReceiver.class);
        return PendingIntent.getBroadcast(ctx, 0, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_MUTABLE) != null;
    }

    private static PendingIntent getGeofencePendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, GeofenceReceiver.class);
        return PendingIntent.getBroadcast(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    /**
     * 端末の再起動とアプリの更新で、登録したジオフェンスは OS に消される (Google Play 開発者サービスの仕様)。
     * これまではアプリを開くまで登録し直さず、再起動した日は自動出席が動いていなかった。
     */
    public static class RestoreReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
            Log.d(TAG, "Restoring geofence after " + action);
            startIfEnabled(context.getApplicationContext());
        }
    }

    public static class GeofenceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            GeofencingEvent event = GeofencingEvent.fromIntent(intent);
            if (event == null) return;
            if (event.hasError()) {
                // 位置情報が切られると、登録したジオフェンスは消える (登録し直すまで監視されない)。
                if (event.getErrorCode() == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                    PresenceEvidenceLog.setRegistered(context, false);
                }
                PresenceEvidenceLog.append(context, PresenceEvidence.Kind.MONITORING_LOST, null, System.currentTimeMillis());
                return;
            }

            int transition = event.getGeofenceTransition();
            if (transition == Geofence.GEOFENCE_TRANSITION_ENTER || transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                // 登録したときの状態の通知 (INITIAL_TRIGGER) と境界の通過を見分けられないので、通過とは記録しない。
                PresenceEvidenceLog.append(context,
                        transition == Geofence.GEOFENCE_TRANSITION_ENTER ? PresenceEvidence.Kind.INSIDE : PresenceEvidence.Kind.OUTSIDE,
                        false, observedAt(event));
            }
            if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                Log.d(TAG, "Entered university geofence");
                checkAutoAttendance(context);
            }
            // 欠席は授業の後に状態を確かめてから決める。状態が届いたここで判定し直す。
            com.shakenokirimi12.uoa_app.services.attendance.AutoAbsenceRunner.evaluateAsync(context);
        }
    }

    /**
     * 出入りした時刻。通知は遅れて届くことがあるので、通知のもとになった位置の時刻を使う
     * (この 10 分以内で、今より前のときだけ。古い位置や、端末の時計がずれた位置は使わない)。
     */
    private static long observedAt(GeofencingEvent event) {
        long now = System.currentTimeMillis();
        android.location.Location loc = event.getTriggeringLocation();
        if (loc == null) return now;
        long t = loc.getTime();
        return (t > 0 && t <= now && now - t <= 10 * 60_000L) ? t : now;
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
            com.shakenokirimi12.uoa_app.services.usage.UsageStats.record(ctx,
                    com.shakenokirimi12.uoa_app.services.usage.UsageFeature.AUTO_ATTENDANCE);
            sp.edit().putLong("last_geofence_time", now).apply();
            notifyRegistered(ctx, matched);
            return;
        }
        Log.d(TAG, "No class to register now");
    }

    private static void notifyRegistered(Context ctx, MoodleCourse matched) {
        try {
            // ClassNotificationService が一度も起動していない端末ではチャンネルが無く、通知が黙って落ちる。
            // 定義は NotificationChannels の 1 か所だけ (既にあれば何もしない)。
            String channel = com.shakenokirimi12.uoa_app.services.notification.NotificationChannels.ensureClassOngoing(ctx);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, channel)
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
