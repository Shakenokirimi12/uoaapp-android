package com.shakenokirimi12.uoa_app.services.notification;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenu;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.services.ForceUpdateGate;
import com.shakenokirimi12.uoa_app.services.GakushokuService;

import java.util.concurrent.TimeUnit;

/**
 * 設定した時刻に、その日の学食メニューを 1 回通知する。メニューが無い日は何も出さない。
 *
 * 正確なアラーム (SCHEDULE_EXACT_ALARM) は使わない。Android 14 以降は既定で許可されず、ユーザーに
 * 特別な許可を求めることになる。昼のメニュー通知は数分ずれても困らないので、WorkManager の
 * 1 回限りの仕事を「次の通知時刻まで遅らせて」積み、実行のたびに翌日分を積み直す。
 * WorkManager は再起動後も仕事を持ち越すので、BOOT_COMPLETED の受信も要らない。
 * 実行時にメニューを取りに行き、失敗したら手元のキャッシュ (以前に取得した本物のメニュー) を使う。
 * どちらにもその日が無ければ通知しない。
 */
public class LunchMenuWorker extends Worker {
    private static final String TAG = "LunchMenuWorker";
    private static final String WORK_NAME = "lunch_menu_notify";
    private static final String KEY_SCHEDULED_FOR = "scheduled_for";
    private static final int NOTIFICATION_ID = 3001;
    /** 取得に失敗したとき、これより新しいキャッシュだけを使う。 */
    private static final long CACHE_USABLE_MS = 14L * 24 * 60 * 60 * 1000;
    /** 通知の前に設定を取り直すときの上限。 */
    private static final long CONFIG_TIMEOUT_MS = 10_000L;

    public LunchMenuWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** 設定を変えたとき。予約を作り直す (OFF なら消す)。 */
    public static void reschedule(@NonNull Context context) {
        schedule(context, ExistingWorkPolicy.REPLACE);
    }

    /** 起動時。既に予約があればそのまま (毎回積み直して時刻をずらさない)。 */
    public static void ensureScheduled(@NonNull Context context) {
        schedule(context, ExistingWorkPolicy.KEEP);
    }

    private static void schedule(@NonNull Context context, @NonNull ExistingWorkPolicy policy) {
        PreferenceManager prefs = PreferenceManager.getInstance(context);
        WorkManager wm = WorkManager.getInstance(context);
        if (!prefs.isLunchNotifyEnabled()) {
            wm.cancelUniqueWork(WORK_NAME);
            return;
        }
        OneTimeWorkRequest next = nextRequest(prefs);
        if (next == null) {
            wm.cancelUniqueWork(WORK_NAME);
            return;
        }
        wm.enqueueUniqueWork(WORK_NAME, policy, next);
    }

    private static OneTimeWorkRequest nextRequest(@NonNull PreferenceManager prefs) {
        long now = System.currentTimeMillis();
        long at = LunchMenuPlan.nextTriggerAt(now, prefs.getLunchNotifyTime());
        if (at < 0) return null;
        return new OneTimeWorkRequest.Builder(LunchMenuWorker.class)
                .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
                .setInputData(new Data.Builder().putLong(KEY_SCHEDULED_FOR, at).build())
                .build();
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        try {
            // 強制アップデートの対象になった版は、裏で通信しない (SyncWorker と同じ)。翌日分の予約は残す。
            boolean blocked = false;
            if (prefs.isLunchNotifyEnabled()) {
                AppConfigService.getInstance().refreshBlocking(ctx, CONFIG_TIMEOUT_MS);
                blocked = ForceUpdateGate.getInstance(ctx).isBlocked();
                if (blocked) Log.w(TAG, "Skipping lunch menu: this version must be updated");
            }
            if (prefs.isLunchNotifyEnabled() && !blocked
                    && AppConfigService.getInstance().isFeatureEnabled("gakushoku_menu_enabled")) {
                long scheduledFor = getInputData().getLong(KEY_SCHEDULED_FOR, System.currentTimeMillis());
                LunchMenuPlan.Content content =
                        LunchMenuPlan.decide(loadMenu(ctx), scheduledFor, System.currentTimeMillis());
                if (content != null) post(ctx, content);
            }
        } finally {
            // 翌日分。この仕事の後ろにつなぐ (APPEND_OR_REPLACE)。REPLACE だと実行中の自分が取り消される。
            // 実行中に設定画面から予約し直された (REPLACE で自分が止められた) ときは、新しい予約が
            // 翌日分を持っているので積まない。積むと以降ずっと 2 本ずつ走る。
            if (prefs.isLunchNotifyEnabled() && !isStopped()) {
                OneTimeWorkRequest next = nextRequest(prefs);
                if (next != null) {
                    WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, next);
                }
            }
        }
        return Result.success();
    }

    /** 最新を取りに行き、取れなければ以前に取得したもの。どちらも無ければ null (通知しない)。 */
    private static GakushokuMenu loadMenu(@NonNull Context ctx) {
        DataCache cache = DataCache.getInstance(ctx);
        try {
            GakushokuMenu fresh = new GakushokuService().fetchMenuBlocking();
            cache.saveMenu(fresh);
            return fresh;
        } catch (Exception e) {
            // メニューの日付は月日だけ (年が無い)。古いキャッシュだと去年の同じ日の献立を「今日」として
            // 出しうるので、掲載期間 (2 週間ほど) より古いものは使わない。
            long fetchedAt = cache.fetchedAt(DataCache.Dataset.MENU);
            if (fetchedAt <= 0 || System.currentTimeMillis() - fetchedAt > CACHE_USABLE_MS) {
                Log.w(TAG, "menu fetch failed and no recent cache; not notifying", e);
                return null;
            }
            Log.w(TAG, "menu fetch failed; using the cached menu", e);
            return cache.loadMenu();
        }
    }

    private static void post(@NonNull Context ctx, @NonNull LunchMenuPlan.Content content) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping lunch notification");
            return;
        }
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NotificationChannels.ensureLunchMenu(ctx))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(content.title)
                .setContentText(content.body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content.body))
                .setContentIntent(pi)
                .setAutoCancel(true);
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, b.build());
    }
}
