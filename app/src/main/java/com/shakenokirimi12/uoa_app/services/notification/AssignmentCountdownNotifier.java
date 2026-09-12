package com.shakenokirimi12.uoa_app.services.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.ui.browser.InAppBrowserActivity;

import java.util.List;

/**
 * 締切が近い課題の残り時間を通知に出す (iOS の課題 Live Activity に相当)。
 *
 * 通知のクロノメーター (setChronometerCountDown) が残り時間を自分で刻むので、
 * サービスを常駐させて更新し続ける必要が無い。setTimeoutAfter で締切に自動で消えるため、
 * iOS で起きた「期限を過ぎて残り時間がマイナスに見える」も起きない。
 *
 * 出し始めるのは締切 2 時間前 (iOS と同じ)。実際の表示開始は次の同期 (最短 15 分間隔) に
 * 依存するので、最悪 1 時間ほど遅れる。正確に 2 時間前に出したければサーバーからの
 * push で起動する形にする必要があるが、期限通知そのものは別途 push で届くので、
 * ここは表示の補助と割り切っている。
 */
public final class AssignmentCountdownNotifier {
    public static final String CHANNEL_ID = "assignment_countdown";
    private static final int ID_BASE = 5000;
    private static final long LEAD_MS = 2 * 60 * 60_000L;

    private AssignmentCountdownNotifier() {}

    /** 課題の取得に成功したあとに呼ぶ。対象の課題ごとに 1 通、同じ id で上書きする。 */
    public static void post(@NonNull Context context, @NonNull List<Assignment> assignments) {
        Context ctx = context.getApplicationContext();
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        if (!prefs.isAssignmentNotifyEnabled()) return;
        if (!AppConfigService.getInstance().isFeatureEnabled("live_activity_enabled")) return;

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.countdown_channel_name), NotificationManager.IMPORTANCE_LOW));

        long now = System.currentTimeMillis();
        for (Assignment a : assignments) {
            long dueMs = a.getDueDate() * 1000L;
            long remaining = dueMs - now;
            if (remaining <= 0 || remaining > LEAD_MS) continue;

            String url = MoodleService.currentBaseUrl() + "/mod/assign/view.php?id=" + a.getId();
            Intent open = new Intent(ctx, InAppBrowserActivity.class)
                    .putExtra(InAppBrowserActivity.EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, ID_BASE + a.getId(), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(ctx.getString(R.string.countdown_title))
                    .setContentText(a.getName())
                    .setSubText(a.getCourseName())
                    // 締切の時刻を when にして、そこへ向けて数える
                    .setWhen(dueMs)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setShowWhen(true)
                    // 締切で自動的に消す
                    .setTimeoutAfter(remaining)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            try {
                NotificationManagerCompat.from(ctx).notify(ID_BASE + a.getId(), b.build());
            } catch (SecurityException e) {
                // POST_NOTIFICATIONS 未許可
                return;
            }
        }
    }
}
