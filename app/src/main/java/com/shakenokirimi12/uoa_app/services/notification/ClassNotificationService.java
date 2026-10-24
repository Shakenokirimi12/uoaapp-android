package com.shakenokirimi12.uoa_app.services.notification;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.AppConfigService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClassNotificationService extends Service {

    private static final String TAG = "ClassNotification";
    public static final String CHANNEL_ID = NotificationChannels.CLASS_ONGOING;
    private static final int NOTIFICATION_ID = 1001;
    private static final long UPDATE_INTERVAL = 15_000;

    private static final String ACTION_UPDATE = "com.shakenokirimi12.uoa_app.UPDATE_EVENTS";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<GroupedClass> groupedClasses = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.JAPANESE);
    private boolean running = false;

    /**
     * 時間割 (何日分でもよい) から今日の授業を選び、あれば常駐通知を出す。設定で切っている、または
     * killswitch (live_activity_enabled) で止めているときは出さず、出ていれば消す。
     * 前面にいるときに呼ぶこと (バックグラウンドからは前景サービスを起動できない)。
     */
    public static void startForToday(Context context, List<CalendarEvent> events) {
        if (!PreferenceManager.getInstance(context).isClassNotificationEnabled()
                || !AppConfigService.getInstance().isFeatureEnabled("live_activity_enabled")) {
            stop(context);
            return;
        }
        if (events == null) return;
        Calendar todayStart = Calendar.getInstance();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        todayStart.set(Calendar.SECOND, 0);
        todayStart.set(Calendar.MILLISECOND, 0);
        long start = todayStart.getTimeInMillis();
        todayStart.add(Calendar.DAY_OF_YEAR, 1);
        long end = todayStart.getTimeInMillis();

        List<CalendarEvent> todayClasses = new ArrayList<>();
        boolean anyLeft = false;
        long now = System.currentTimeMillis();
        for (CalendarEvent e : events) {
            if (e.getDtstart() == null) continue;
            long t = e.getDtstart().getTime();
            if (t >= start && t < end) {
                todayClasses.add(e);
                if (e.getDtend() != null && e.getDtend().getTime() > now) anyLeft = true;
            }
        }
        // 今日の授業が全部終わっていたら起動しない。起動すると startForeground が要るので、
        // 「終わりました」を一瞬出してすぐ消すことになる (ホームを開くたびに)。
        if (anyLeft) updateEvents(context, todayClasses);
    }

    public static void updateEvents(Context context, List<CalendarEvent> events) {
        Intent intent = new Intent(context, ClassNotificationService.class);
        intent.setAction(ACTION_UPDATE);
        ClassNotificationService.pendingEvents = events;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException e) {
            // 取得の完了がアプリを裏に回した後になると、ここはバックグラウンドからの起動になり、
            // Android 12 以降は ForegroundServiceStartNotAllowedException (IllegalStateException の子) で
            // 落ちていた。常駐通知は次に前面で取得したときに出せばよいので、今回は諦める。
            pendingEvents = null;
            Log.w(TAG, "Cannot start the class notification from the background: " + e.getMessage());
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

        // startForegroundService で起こされた以上、毎回 startForeground を呼ぶ (既に前面でも、呼ばないと
        // 5 秒後に ForegroundServiceDidNotStartInTimeException で落ちる)。
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (IllegalStateException e) {
            // 起動を頼んでからここまでの間にアプリが裏に回った (Android 12 以降で拒否される)。
            Log.w(TAG, "startForeground rejected: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (getCurrentClass() == null && getNextClass() == null) {
            // 今日の授業がもう残っていない。出し続ける理由が無い。
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            scheduleUpdate();
        }

        // プロセスが落ちたあと OS に再起動させない。再起動された側は授業の一覧を持っておらず、
        // 授業がある日でも「今日の授業はありません」と出していた。次に前面で取得したときに出し直す。
        return START_NOT_STICKY;
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
                .setSmallIcon(R.drawable.ic_stat_calendar)
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
            builder.setContentTitle("今日の授業は終わりました")
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
        NotificationChannels.ensureClassOngoing(this);
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
