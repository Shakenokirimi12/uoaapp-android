package com.shakenokirimi12.uoa_app.services.sync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.services.CampusSquareService;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 定期的にバックグラウンドで Moodle と CampusSquare を取り直す。
 *
 * これまで設定画面に同期間隔の項目はあったが、裏で動くものが何も無かった。
 * アプリを開かない限りデータもログインセッションも更新されず、新しい課題は
 * 次にアプリを開くまで一切見えなかった。iOS の BackgroundFetchService に相当する。
 *
 * サービスはコールバック式なので、ここでは latch で同期的に待つ。
 */
public class SyncWorker extends Worker {
    private static final String TAG = "SyncWorker";
    static final String CHANNEL_ID = "background_sync";
    private static final int NOTIFICATION_ID = 4100;
    private static final long STEP_TIMEOUT_SEC = 90;

    // サービスは内部に単一スレッドの executor を持ち、shutdown する口が無い。
    // 実行のたびに new すると 15 分ごとにスレッドが増え続けるので、プロセスで 1 つを使い回す。
    private static final MoodleService moodle = new MoodleService();
    private static final CampusSquareService campusSquare = new CampusSquareService();

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    // 定期実行と手動実行が重なったとき、後から来た方は何もせずに終える。両方が同じ
    // 単一スレッドの executor にタスクを積むと、片方の await が 90 秒のタイムアウトに達して
    // 「同期に失敗」と誤って通知しうる。ロックで待たせる形にしないのは、待ち時間も
    // WorkManager の実行時間枠 (10 分) に計上され、最悪で強制停止と再試行を招くため。
    // 走っている方が同じ内容を取りに行っているので、譲って問題ない。
    private static final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);

    @NonNull
    @Override
    public Result doWork() {
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "Another sync is in progress; skipping");
            return Result.success();
        }
        try {
            return doWorkSerialized();
        } finally {
            running.set(false);
        }
    }

    private Result doWorkSerialized() {
        Context ctx = getApplicationContext();
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);

        if (!prefs.hasCredentials()) return Result.success();

        // ID/PW 誤りが確定している間は試みない。ここを止めないと、誤ったパスワードで
        // 定期的にログインを試行し続け、大学側でアカウントがロックされうる。
        if (prefs.isCredentialsInvalid()) {
            Log.w(TAG, "Skipping sync: credentials marked invalid");
            return Result.success();
        }

        String user = prefs.getUsername();
        String pass = prefs.getPassword();
        DataCache cache = DataCache.getInstance(ctx);
        boolean anyFailure = false;
        boolean changed = false;

        // 1. Moodle
        if (AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            AtomicReference<String> loginError = new AtomicReference<>();
            if (await(latch -> moodle.ensureLoggedIn(user, pass, false, new ServiceCallback<Boolean>() {
                @Override public void onSuccess(Boolean r) { latch.countDown(); }
                @Override public void onError(String m) { loginError.set(m); latch.countDown(); }
            }))) {
                String err = loginError.get();
                if (err == null) {
                    AtomicReference<List<Assignment>> fetched = new AtomicReference<>();
                    await(latch -> moodle.fetchAssignments(new ServiceCallback<List<Assignment>>() {
                        @Override public void onSuccess(List<Assignment> r) { fetched.set(r); latch.countDown(); }
                        @Override public void onError(String m) { latch.countDown(); }
                    }));
                    List<Assignment> assignments = fetched.get();
                    if (assignments != null) {
                        changed |= differs(cache.loadAssignments(), assignments);
                        cache.saveAssignments(assignments);
                        com.shakenokirimi12.uoa_app.services.push.AssignmentReminderSync.sync(ctx, assignments);
                        com.shakenokirimi12.uoa_app.services.notification.AssignmentCountdownNotifier.post(ctx, assignments);
                    } else {
                        anyFailure = true;
                    }
                } else {
                    anyFailure = true;
                    if (MoodleService.isInvalidCredentialsError(err)) {
                        prefs.setCredentialsInvalid(true);
                        Log.w(TAG, "Credentials rejected; disabling automatic sync until password is re-entered");
                        // 同じ資格情報で CampusSquare も試すと試行回数が倍になるだけなので、ここで止める。
                        notifyIfEnabled(ctx, prefs, false, false, true);
                        return Result.success();
                    }
                }
            } else {
                anyFailure = true;
            }
        }

        // 2. CampusSquare のカレンダー (時間割)
        if (AppConfigService.getInstance().isFeatureEnabled("campussquare_calendar_enabled")) {
            AtomicReference<List<CalendarEvent>> fetched = new AtomicReference<>();
            AtomicReference<String> csError = new AtomicReference<>();
            await(latch -> campusSquare.fetchCalendarEvents(user, pass, false, new ServiceCallback<List<CalendarEvent>>() {
                @Override public void onSuccess(List<CalendarEvent> r) { fetched.set(r); latch.countDown(); }
                @Override public void onError(String m) { csError.set(m); latch.countDown(); }
            }));
            if (fetched.get() != null) {
                cache.saveEvents(fetched.get());
                com.shakenokirimi12.uoa_app.widget.ClassScheduleWidgetProvider.refresh(ctx);
            } else {
                anyFailure = true;
                // CampusSquare 側だけで ID/PW 誤りが判明することもある (Moodle が killswitch で
                // 止まっている、または片方だけ認証方式が変わった場合)。
                if (csError.get() != null && com.shakenokirimi12.uoa_app.services.AuthErrors.isInvalidCredentials(csError.get())) {
                    prefs.setCredentialsInvalid(true);
                    Log.w(TAG, "CampusSquare rejected credentials; disabling automatic sync");
                }
            }
        }

        if (!anyFailure) prefs.setLastSync(System.currentTimeMillis());
        notifyIfEnabled(ctx, prefs, changed, !anyFailure, anyFailure);
        // 失敗は次回の定期実行に任せる。retry にすると指数バックオフで短時間に再ログインを重ねる。
        return Result.success();
    }

    private interface Step { void run(CountDownLatch latch); }

    /** コールバック式のサービス呼び出しを待つ。タイムアウトしたら false。 */
    private static boolean await(Step step) {
        CountDownLatch latch = new CountDownLatch(1);
        step.run(latch);
        try {
            return latch.await(STEP_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 一覧の増減だけでなく、締切や課題名の変更も「変更あり」に含める。 */
    private static boolean differs(List<Assignment> before, List<Assignment> after) {
        if (before == null) return !after.isEmpty();
        Set<String> a = new HashSet<>();
        for (Assignment x : before) a.add(x.getId() + "|" + x.getDueDate() + "|" + x.getName());
        Set<String> b = new HashSet<>();
        for (Assignment x : after) b.add(x.getId() + "|" + x.getDueDate() + "|" + x.getName());
        return !a.equals(b);
    }

    /** 設定画面の「バックグラウンド通知」の項目に従って結果を通知する。 */
    private static void notifyIfEnabled(Context ctx, PreferenceManager prefs,
                                        boolean changed, boolean success, boolean failure) {
        // 4 つのトグルはそれぞれ独立に意味を持たせる。具体的な文面 (変更あり / 変更なし) が
        // 出せるならそちらを優先し、それらが OFF でも「同期成功」が ON なら成功を伝える。
        String text;
        if (failure) {
            if (!prefs.isBgNotifyFailure()) return;
            text = prefs.isCredentialsInvalid()
                    ? ctx.getString(R.string.sync_notify_credentials_invalid)
                    : ctx.getString(R.string.sync_notify_failure);
        } else if (changed && prefs.isBgNotifyChange()) {
            text = ctx.getString(R.string.sync_notify_changed);
        } else if (!changed && prefs.isBgNotifyNoChange()) {
            text = ctx.getString(R.string.sync_notify_no_change);
        } else if (success && prefs.isBgNotifySuccess()) {
            text = ctx.getString(R.string.sync_notify_success);
        } else {
            return;
        }

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS 未許可。通知は出せないが同期自体は済んでいる。
        }
    }
}
