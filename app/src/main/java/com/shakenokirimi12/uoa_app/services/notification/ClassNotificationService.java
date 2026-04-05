package com.shakenokirimi12.uoa_app.services.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClassNotificationService extends Service {

    public static final String CHANNEL_ID = "class_ongoing";
    private static final int NOTIFICATION_ID = 1001;
    private static final long UPDATE_INTERVAL = 15_000;

    private static final String ACTION_UPDATE = "com.shakenokirimi12.uoa_app.UPDATE_EVENTS";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<GroupedClass> groupedClasses = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.JAPANESE);
    private boolean running = false;

    public static void updateEvents(Context context, List<CalendarEvent> events) {
        Intent intent = new Intent(context, ClassNotificationService.class);
        intent.setAction(ACTION_UPDATE);
        ClassNotificationService.pendingEvents = events;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ClassNotificationService.class));
    }

    private static volatile List<CalendarEvent> pendingEvents;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            List<CalendarEvent> events = pendingEvents;
            if (events != null) {
                pendingEvents = null;
                groupedClasses.clear();
                groupedClasses.addAll(GroupedClass.groupBySubject(events));
            }
        }

        if (!running) {
            running = true;
            startForeground(NOTIFICATION_ID, buildNotification());
            scheduleUpdate();
        } else {
            updateNotification();
        }

        return START_STICKY;
    }

    private void scheduleUpdate() {
        handler.postDelayed(() -> {
            if (!running) return;

            GroupedClass current = getCurrentClass();
            GroupedClass next = getNextClass();

            if (current == null && next == null) {
                stopSelf();
                return;
            }

            updateNotification();
            scheduleUpdate();
        }, UPDATE_INTERVAL);
    }

    private void updateNotification() {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mgr.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE);

        GroupedClass current = getCurrentClass();
        GroupedClass next = getNextClass();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_calendar)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (current != null) {
            long remainMs = current.getDtend().getTime() - System.currentTimeMillis();
            long remainMin = Math.max(0, remainMs / 60_000);

            String periodStr = current.formatPeriods();
            String subtitle = timeFmt.format(current.getDtstart()) + " - " + timeFmt.format(current.getDtend());
            if (!periodStr.isEmpty()) subtitle = periodStr + "  " + subtitle;

            builder.setContentTitle(current.getSummary())
                    .setContentText(String.format(Locale.JAPANESE,
                            "%s  残り %d分", current.getLocation(), remainMin))
                    .setSubText(subtitle)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(current.getDtend().getTime());
        } else if (next != null) {
            long untilMs = next.getDtstart().getTime() - System.currentTimeMillis();
            long untilMin = Math.max(0, untilMs / 60_000);

            String periodStr = next.formatPeriods();
            String subtitle = timeFmt.format(next.getDtstart()) + " - " + timeFmt.format(next.getDtend());
            if (!periodStr.isEmpty()) subtitle = periodStr + "  " + subtitle;

            builder.setContentTitle("次の授業: " + next.getSummary())
                    .setContentText(String.format(Locale.JAPANESE,
                            "%s  %d分後", next.getLocation(), untilMin))
                    .setSubText(subtitle);
        } else {
            builder.setContentTitle("今日の授業はありません")
                    .setContentText("");
        }

        return builder.build();
    }

    private GroupedClass getCurrentClass() {
        Date now = new Date();
        for (GroupedClass g : groupedClasses) {
            if (g.isActiveAt(now)) return g;
        }
        return null;
    }

    private GroupedClass getNextClass() {
        Date now = new Date();
        GroupedClass nearest = null;
        for (GroupedClass g : groupedClasses) {
            if (g.getDtstart() != null && g.getDtstart().after(now)) {
                if (nearest == null || g.getDtstart().before(nearest.getDtstart())) {
                    nearest = g;
                }
            }
        }
        return nearest;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "授業通知", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("授業中に常駐通知を表示します");
            channel.setShowBadge(false);
            NotificationManager mgr = getSystemService(NotificationManager.class);
            mgr.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
