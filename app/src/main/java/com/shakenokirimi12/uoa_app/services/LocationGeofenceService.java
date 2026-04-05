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
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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

    public static void startGeofencing(Context ctx, double lat, double lng, float radius) {
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            return;
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

    private static void checkAutoAttendance(Context ctx) {
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        if (!prefs.isAutoAttendanceEnabled()) return;

        SharedPreferences sp = ctx.getSharedPreferences("geofence", Context.MODE_PRIVATE);
        long lastNotified = sp.getLong("last_geofence_time", 0);
        long now = System.currentTimeMillis();
        if (lastNotified > 0 && (now - lastNotified) < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active");
            return;
        }

        Gson gson = new Gson();
        SharedPreferences dataPrefs = ctx.getSharedPreferences("data_cache", Context.MODE_PRIVATE);

        String eventsJson = dataPrefs.getString("events", null);
        String coursesJson = dataPrefs.getString("courses", null);
        if (eventsJson == null || coursesJson == null) return;

        List<CalendarEvent> events;
        List<MoodleCourse> courses;
        try {
            events = gson.fromJson(eventsJson, new TypeToken<List<CalendarEvent>>(){}.getType());
            courses = gson.fromJson(coursesJson, new TypeToken<List<MoodleCourse>>(){}.getType());
        } catch (Exception e) { return; }

        if (events == null || courses == null) return;

        long nowSec = now / 1000;
        CalendarEvent currentClass = null;
        for (CalendarEvent ev : events) {
            long startSec = ev.getDtstart().getTime() / 1000;
            long windowStart = startSec - 15 * 60;
            long windowEnd = startSec + 90 * 60;
            if (nowSec >= windowStart && nowSec <= windowEnd) {
                currentClass = ev;
                break;
            }
        }
        if (currentClass == null) return;

        String eventNorm = normalize(currentClass.getSummary());
        MoodleCourse matched = null;
        for (MoodleCourse c : courses) {
            String cNorm = normalize(c.getFullname());
            String sNorm = normalize(c.getShortname());
            if (eventNorm.contains(cNorm) || eventNorm.contains(sNorm) || cNorm.contains(eventNorm)) {
                matched = c;
                break;
            }
        }
        if (matched == null) return;

        AttendanceManager.getInstance(ctx).addHistory(
                String.valueOf(matched.getId()),
                AttendanceManager.Status.PRESENT,
                new Date(),
                "auto"
        );

        sp.edit().putLong("last_geofence_time", now).apply();

        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, "class_ongoing")
                    .setSmallIcon(R.drawable.ic_calendar)
                    .setContentTitle("自動出席登録完了")
                    .setContentText(matched.getFullname() + " の出席を自動登録しました。")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);

            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(ctx).notify(2001, builder.build());
            }
        } catch (Exception ignored) {}
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
