package com.shakenokirimi12.uoa_app.services.notification;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 授業中・授業前の常駐通知 (Android 16 のライブアップデート)。
 *
 * 以前はフォアグラウンドサービスが 15 秒ごとに通知を作り直していた。残り時間の表示は
 * 通知側のクロノメーターが自分で進めてくれるので、常駐するのはやめ、授業の切り替わりに
 * AlarmManager で起きて作り直すだけにした。FGS を捨てたことで「実行中のアプリ」
 * (タスクマネージャー) に載らなくなり、FOREGROUND_SERVICE_SPECIAL_USE も外せる。
 *
 * ライブアップデートの要件は setOngoing / contentTitle / setRequestPromotedOngoing と
 * 対応スタイル (ここでは ProgressStyle)、それに POST_PROMOTED_NOTIFICATIONS の宣言。
 */
public final class ClassLiveUpdate {

    private static final String TAG = "ClassLiveUpdate";
    public static final String CHANNEL_ID = "class_ongoing";
    private static final int NOTIFICATION_ID = 1001;
    private static final int ALARM_REQUEST_CODE = 1001;

    /** 授業前の通知を出し始めるのは開始の 30 分前から。それより早くは出さない。 */
    private static final long LEAD_MS = 30 * 60_000L;

    /** 境界ちょうどに起きると、まだ切り替わっていないと判定しうるので少し後ろにずらす。 */
    private static final long ALARM_MARGIN_MS = 1_000L;

    private ClassLiveUpdate() {}

    /**
     * 今の時刻に合った通知を出し直し、次の切り替わり時刻にアラームを仕掛ける。
     * アプリを開いたとき、バックグラウンド同期で時間割を取り直したとき、
     * そして仕掛けたアラームが鳴ったときに呼ばれる。
     */
    public static void refresh(@NonNull Context context) {
        Context ctx = context.getApplicationContext();
        List<GroupedClass> classes = todayClasses(ctx);
        long now = System.currentTimeMillis();

        // 開始時刻順に並んでいるので、まだ終わっていない最初の授業が「今の授業」か「次の授業」。
        GroupedClass current = null;
        GroupedClass next = null;
        for (GroupedClass g : classes) {
            if (g.getDtstart() == null || g.getDtend() == null) continue;
            if (now >= g.getDtend().getTime()) continue;
            if (now >= g.getDtstart().getTime()) current = g; else next = g;
            break;
        }

        long boundary;
        if (current != null) {
            long start = current.getDtstart().getTime();
            long end = current.getDtend().getTime();
            post(ctx, build(ctx, current.getSummary(), current.getLocation(), subText(current),
                    end, percent(now - start, end - start)));
            boundary = end;
        } else if (next != null && next.getDtstart().getTime() - now <= LEAD_MS) {
            long start = next.getDtstart().getTime();
            post(ctx, build(ctx, ctx.getString(R.string.class_notify_next_prefix, next.getSummary()),
                    next.getLocation(), subText(next),
                    start, percent(LEAD_MS - (start - now), LEAD_MS)));
            boundary = start;
        } else if (next != null) {
            // まだ 30 分より先。通知は出さず、30 分前に起きる。
            cancelNotification(ctx);
            boundary = next.getDtstart().getTime() - LEAD_MS;
        } else {
            // 今日はもう授業がない。次にアプリを開くか同期が走ったときに組み直す。
            cancelNotification(ctx);
            cancelAlarm(ctx);
            return;
        }

        scheduleAt(ctx, boundary + ALARM_MARGIN_MS);
    }

    /** 通知とアラームの両方を取り下げる。 */
    public static void stop(@NonNull Context context) {
        Context ctx = context.getApplicationContext();
        cancelNotification(ctx);
        cancelAlarm(ctx);
    }

    // --- 通知 ---

    private static Notification build(Context ctx, String title, String text, String subText,
                                      long countdownTo, int progress) {
        createChannel(ctx);

        Intent tap = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, tap, PendingIntent.FLAG_IMMUTABLE);

        // バーは 1 本の帯として現在地だけを示す。節目でしか作り直さないので途中では動かない。
        NotificationCompat.ProgressStyle style = new NotificationCompat.ProgressStyle()
                .setProgressSegments(Collections.singletonList(
                        new NotificationCompat.ProgressStyle.Segment(100)))
                .setProgress(progress);

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_calendar)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(subText)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                // 残り時間はクロノメーターが自分で進める。ステータスバーのチップにもこれが出るので、
                // 15 秒ごとに作り直さなくても表示は古くならない。
                .setWhen(countdownTo)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setRequestPromotedOngoing(true)
                // 時計には Wear アプリ (wear/…/ClassOngoingActivity) が自前で出す。転送されると二重になる。
                .setLocalOnly(true)
                .build();
    }

    private static void post(Context ctx, Notification notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS 未許可。
            Log.w(TAG, "notify failed: " + e.getMessage());
        }
    }

    private static void cancelNotification(Context ctx) {
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
    }

    private static void createChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        // IMPORTANCE_MIN だとライブアップデートの対象外になる。LOW は対象。
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.class_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(ctx.getString(R.string.class_channel_description));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    // --- アラーム ---

    private static void scheduleAt(Context ctx, long triggerAtMillis) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        // 正確なアラームは Android 14 以降、既定では許可されない。表示の切り替わりが数分ずれても
        // クロノメーターが残り時間を示し続けるので、非正確で足りる。
        am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmIntent(ctx));
    }

    private static void cancelAlarm(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarmIntent(ctx));
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 授業の切り替わりに起きて、通知を組み直す。 */
    public static class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh(context);
        }
    }

    // --- 時間割 ---

    /** キャッシュ済みの時間割から今日の分だけを取り出して科目ごとにまとめる。 */
    private static List<GroupedClass> todayClasses(Context ctx) {
        List<CalendarEvent> events = DataCache.getInstance(ctx).loadEvents();
        if (events == null || events.isEmpty()) return Collections.emptyList();

        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long start = day.getTimeInMillis();
        day.add(Calendar.DAY_OF_YEAR, 1);
        long end = day.getTimeInMillis();

        List<CalendarEvent> today = new ArrayList<>();
        for (CalendarEvent e : events) {
            if (e.getDtstart() == null) continue;
            long t = e.getDtstart().getTime();
            if (t >= start && t < end) today.add(e);
        }
        return GroupedClass.groupBySubject(today);
    }

    /** 「2限  10:50 - 12:30」。限が取れない授業では時刻だけ。 */
    private static String subText(GroupedClass g) {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.JAPANESE);
        String time = fmt.format(g.getDtstart()) + " - " + fmt.format(g.getDtend());
        String periods = g.formatPeriods();
        return periods.isEmpty() ? time : periods + "  " + time;
    }

    private static int percent(long elapsed, long total) {
        if (total <= 0) return 0;
        return (int) Math.max(0, Math.min(100, elapsed * 100 / total));
    }
}
