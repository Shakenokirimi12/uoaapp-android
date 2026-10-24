package com.shakenokirimi12.uoa_app.services.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.services.AuthorizationContext;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.services.AuthErrors;
import com.shakenokirimi12.uoa_app.services.CampusSquareService;
import com.shakenokirimi12.uoa_app.services.ForceUpdateGate;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;

import java.util.List;
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
    /**
     * 以前の「同期結果の通知」のチャンネル。通知は「ログインが必要」だけを残して消した (2026-10-22)。
     * 端末の通知設定に空のチャンネルが残らないよう、起動時に消す (UoaApplication)。
     */
    public static final String REMOVED_CHANNEL_ID = "background_sync";
    private static final long STEP_TIMEOUT_SEC = 90;
    /** 同期の前に設定を取り直すときの上限。取れなくても前に取れた条件で判定できるので短くする。 */
    static final long CONFIG_TIMEOUT_MS = 10_000L;

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
            // アプリを開かない日が続いても、前日までの利用状況を送る。失敗しても同期は続ける (例外は投げない)。
            com.shakenokirimi12.uoa_app.services.usage.UsageStats.sendIfDue(getApplicationContext());
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

        // 強制アップデートの対象になった版は、裏で大学のサーバーへ通信しない。通信の前に設定を取り直し
        // (取れなければ前に取れた条件で) 判定する。あわせて killswitch もこの回の同期に効く
        // (バックグラウンドだけで起きたプロセスは、これまで設定を一度も取っていなかった)。
        AppConfigService.getInstance().refreshBlocking(ctx, CONFIG_TIMEOUT_MS);
        if (ForceUpdateGate.getInstance(ctx).isBlocked()) {
            Log.w(TAG, "Skipping sync: this version must be updated");
            return Result.success();
        }

        String user = prefs.getUsername();
        String pass = prefs.getPassword();
        DataCache cache = DataCache.getInstance(ctx);
        boolean anyFailure = false;
        // 保存済みセッションが切れていて、OTP 付きの新規ログインが要る。失敗とは数えない
        // (バックグラウンドからはログインしない決まりなので、待つのが正常)。
        boolean loginRequired = false;

        // 1. Moodle
        if (AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            AtomicReference<String> loginError = new AtomicReference<>();
            if (await(latch -> moodle.ensureLoggedIn(user, pass, AuthorizationContext.BACKGROUND, new ServiceCallback<Boolean>() {
                @Override public void onSuccess(Boolean r) { latch.countDown(); }
                @Override public void onError(String m) { loginError.set(m); latch.countDown(); }
            }))) {
                String err = loginError.get();
                if (err == null) {
                    AtomicReference<List<Assignment>> fetched = new AtomicReference<>();
                    AtomicReference<String> fetchError = new AtomicReference<>();
                    await(latch -> moodle.fetchAssignments(AuthorizationContext.BACKGROUND, new ServiceCallback<List<Assignment>>() {
                        @Override public void onSuccess(List<Assignment> r) { fetched.set(r); latch.countDown(); }
                        @Override public void onError(String m) { fetchError.set(m); latch.countDown(); }
                    }));
                    List<Assignment> assignments = fetched.get();
                    if (assignments != null) {
                        cache.saveAssignments(assignments);
                        com.shakenokirimi12.uoa_app.services.push.AssignmentReminderSync.sync(ctx, assignments);
                        com.shakenokirimi12.uoa_app.services.notification.AssignmentCountdownNotifier.post(ctx, assignments);
                    } else if (AuthErrors.isInteractiveLoginRequired(fetchError.get())) {
                        loginRequired = true;
                    } else {
                        anyFailure = true;
                    }
                } else if (AuthErrors.isInteractiveLoginRequired(err)) {
                    loginRequired = true;
                } else {
                    anyFailure = true;
                    if (MoodleService.isInvalidCredentialsError(err)) {
                        prefs.setCredentialsInvalid(true);
                        Log.w(TAG, "Credentials rejected; disabling automatic sync until password is re-entered");
                        // 同じ資格情報で CampusSquare も試すと試行回数が倍になるだけなので、ここで止める。
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
            await(latch -> campusSquare.fetchCalendarEvents(user, pass, AuthorizationContext.BACKGROUND, new ServiceCallback<List<CalendarEvent>>() {
                @Override public void onSuccess(List<CalendarEvent> r) { fetched.set(r); latch.countDown(); }
                @Override public void onError(String m) { csError.set(m); latch.countDown(); }
            }));
            if (fetched.get() != null) {
                cache.saveEvents(fetched.get());
                com.shakenokirimi12.uoa_app.widget.ClassScheduleWidgetProvider.refresh(ctx);
            } else if (AuthErrors.isInteractiveLoginRequired(csError.get())) {
                loginRequired = true;
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

        boolean success = !anyFailure && !loginRequired;
        if (success) prefs.setLastSync(System.currentTimeMillis());
        if (loginRequired) {
            LoginRequiredNotice.postAtMostOncePerDay(ctx);
        } else if (success) {
            // ログインし直して同期が通ったら、前に出した「ログインが必要です」は役目を終えている。
            LoginRequiredNotice.cancel(ctx);
        }
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
}
