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
    // 他の固定 ID (1001 授業, 4100 同期) と重ならない範囲に置く。
    private static final int ID_BASE = 700_000;
    private static final long LEAD_MS = 2 * 60 * 60_000L;
    private static final String PREF_ACTIVE_IDS = "countdown_active_ids";

    private AssignmentCountdownNotifier() {}

    // 前面の取得と定期同期から同時に呼ばれる。記録の読み書きが交錯すると、出したはずの
    // 通知が記録から漏れ、課題が消えても取り消されなくなる。
    private static final Object LOCK = new Object();

    /** 課題の取得に成功したあとに呼ぶ。対象の課題ごとに 1 通、同じ id で上書きする。 */
    public static void post(@NonNull Context context, @NonNull List<Assignment> assignments) {
        synchronized (LOCK) {
            postLocked(context, assignments);
        }
    }

    /** ログアウトなど、課題の一覧を捨てるときに呼ぶ。出ている通知を全部消す。 */
    public static void clearAll(@NonNull Context context) {
        post(context, java.util.Collections.emptyList());
    }

    private static void postLocked(@NonNull Context context, @NonNull List<Assignment> assignments) {
        Context ctx = context.getApplicationContext();
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        // 無効化されているときは新規に出さないが、既に出ている分を消す処理は続ける
        boolean enabled = prefs.isAssignmentNotifyEnabled()
                && AppConfigService.getInstance().isFeatureEnabled("live_activity_enabled");
        if (!enabled) assignments = java.util.Collections.emptyList();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.countdown_channel_name), NotificationManager.IMPORTANCE_LOW));

        // 前回出した通知のうち、今回の一覧に無いもの (提出済み・削除・締切延長) は消す。
        // 消さないと、もう関係ない課題のカウントダウンが最初の締切まで残り続ける。
        android.content.SharedPreferences sp = ctx.getSharedPreferences("countdown_notifier", Context.MODE_PRIVATE);
        java.util.Set<String> previous = new java.util.HashSet<>(sp.getStringSet(PREF_ACTIVE_IDS, java.util.Collections.emptySet()));
        java.util.Set<String> active = new java.util.HashSet<>();

        long now = System.currentTimeMillis();
        for (Assignment a : assignments) {
            long dueMs = a.getDueDate() * 1000L;
            long remaining = dueMs - now;
            if (remaining <= 0 || remaining > LEAD_MS) continue;
            active.add(String.valueOf(a.getId()));

            String url = MoodleService.currentBaseUrl() + "/mod/assign/view.php?id=" + a.getId();
            Intent open = new Intent(ctx, InAppBrowserActivity.class)
                    .putExtra(InAppBrowserActivity.EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, ID_BASE + a.getId(), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notification)
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

        for (String id : previous) {
            if (!active.contains(id)) {
                try {
                    NotificationManagerCompat.from(ctx).cancel(ID_BASE + Integer.parseInt(id));
                } catch (NumberFormatException ignored) {
                    // 壊れた記録は捨てるだけ
                }
            }
        }
        sp.edit().putStringSet(PREF_ACTIVE_IDS, active).apply();
    }
}
