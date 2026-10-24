package com.shakenokirimi12.uoa_app.services.otprelay;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PC からの依頼の処理。FCM の受信から、すぐ動く (expedited) 仕事として積む。
 *
 * 依頼ごとの承認は無い (2026-10-21 に廃止。iOS と同じ)。その連携で同意している
 * (OtpRelayPairing.hasMailConsent) なら、メールから読むか、PC の画面が認証アプリ方式ならセットアップキーで
 * コードを作って、自動で返す (OtpRelayResponder.route)。同意は連携の確認ダイアログで
 * 連携ごとに得る。同意を記録する前に作った連携はメールを読まずに断り、アプリでの確認を求める
 * (OtpRelayConsentPrompt)。アプリ自身のログインのメール方式とは別。
 *
 * 返せなかったときは、理由を中継サーバ (PC が手入力に戻るため) と通知 (利用者が理由を知るため) の
 * 両方に出す。処理を始めてから OtpRelayTiming.respondDeadline までに答え始めていなければ、見張りが
 * 自分で失敗を返す (依頼が期限の 120 秒まで pending に残らないように)。
 */
public class OtpRelayWorker extends Worker {
    private static final String TAG = "OtpRelay";

    static final String MODE_INCOMING = "incoming";
    static final String MODE_REREGISTER = "reregister";

    private static final String K_MODE = "mode";
    private static final String K_PAIRING = "pairingId";
    private static final String K_REQUEST = "requestId";
    private static final String K_REQUESTED_AT = "requestedAt";
    private static final String K_EXPIRES_AT = "expiresAt";
    private static final String K_DELIVERED_AT = "deliveredAt";
    private static final String K_TOKEN = "token";

    /** 処理中の依頼。止められたときに IMAP の接続を閉じるために持つ。 */
    @Nullable private volatile OtpRelayResponder.Attempt currentAttempt;

    public OtpRelayWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * 仕事が止められた。メールの取得をやめ、IMAP の接続を LOGOUT して閉じる (通信は別のスレッドで行う)。
     * 止められたプロセスは凍結されることがあり、開いたままの接続はサーバ側でセッションとして残り続けて、
     * 次の依頼の LOGIN を同時接続数の上限で断らせる。取得の本体は失敗として中継サーバに返す (PC は手入力に戻る)。
     */
    @Override
    public void onStopped() {
        super.onStopped();
        OtpRelayResponder.Attempt attempt = currentAttempt;
        if (attempt != null) attempt.imap.abort();
    }

    /** FCM で依頼が届いた。同じ依頼が二重に届いても 1 回だけ処理する (KEEP)。 */
    public static void onIncoming(@NonNull Context ctx, @NonNull OtpRelayRequest request, long deliveredAt) {
        Data data = new Data.Builder()
                .putString(K_MODE, MODE_INCOMING)
                .putString(K_PAIRING, request.pairingId)
                .putString(K_REQUEST, request.requestId)
                .putLong(K_REQUESTED_AT, request.requestedAt)
                .putLong(K_EXPIRES_AT, request.expiresAt)
                .putLong(K_DELIVERED_AT, deliveredAt)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(OtpRelayWorker.class)
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork("otp_relay_" + request.requestId, ExistingWorkPolicy.KEEP, work);
    }

    /** FCM トークンが変わった。通信できるときに全部の連携を登録し直す。 */
    public static void reregister(@NonNull Context ctx, @NonNull String token) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(OtpRelayWorker.class)
                .setInputData(new Data.Builder().putString(K_MODE, MODE_REREGISTER).putString(K_TOKEN, token).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork("otp_relay_reregister", ExistingWorkPolicy.REPLACE, work);
    }

    @Nullable
    private OtpRelayRequest requestFromInput() {
        String pairingId = getInputData().getString(K_PAIRING);
        String requestId = getInputData().getString(K_REQUEST);
        if (pairingId == null || requestId == null) return null;
        return new OtpRelayRequest(pairingId, requestId,
                getInputData().getLong(K_REQUESTED_AT, 0), getInputData().getLong(K_EXPIRES_AT, 0));
    }

    /**
     * Android 11 以前で expedited が前景サービスとして動くときの表示。ID は結果の通知とずらす
     * (前景サービスが終わるときに WorkManager がこの ID の通知を消すので、結果まで消されないように)。
     */
    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        OtpRelayRequest request = requestFromInput();
        OtpRelayPairing pairing = request != null ? OtpRelayStore.find(request.pairingId) : null;
        String name = pairing != null ? pairing.browserName : "PC のブラウザ";
        int id = (request != null ? OtpRelayNotifications.notificationId(request.requestId) : 0x52000000) + 1;
        return new ForegroundInfo(id, OtpRelayNotifications.working(getApplicationContext(), name));
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String mode = getInputData().getString(K_MODE);
        if (MODE_REREGISTER.equals(mode)) {
            String token = getInputData().getString(K_TOKEN);
            if (token != null && !token.isEmpty()) OtpRelayPairingService.reregisterAll(token);
            return Result.success();
        }
        // 以前の版が積んだ承認・拒否 (mode approve / reject) は、ここでは扱わない。期限 (2 分) のある依頼なので、
        // 更新をまたいで残っていても、送らずに捨てる (拒否を押したものを自動で送らない)。
        if (!MODE_INCOMING.equals(mode)) return Result.success();

        OtpRelayRequest request = requestFromInput();
        if (request == null) return Result.success();
        OtpRelayPairing pairing = OtpRelayStore.find(request.pairingId);
        if (request.expiresAt <= System.currentTimeMillis()) return Result.success();
        // FCM は同じメッセージを再配信することがある。答えた依頼にもう一度答えない
        // (使用済みメールは消してあるので、2 回目は mail-timeout の誤った失敗になる)。
        if (OtpRelayStore.wasHandled(request.requestId)) return Result.success();
        if (pairing == null) {
            // この端末からだけ削除した連携など。鍵が無いので中継サーバには答えられない (PC は期限まで待つ)。
            // 何も出さないと理由が分からないので、連携し直すよう知らせる (iOS の NotificationService と同じ)。
            OtpRelayNotifications.notPaired(ctx, request);
            OtpRelayStore.markHandled(request.requestId);
            return Result.success();
        }

        long deliveredAt = getInputData().getLong(K_DELIVERED_AT, System.currentTimeMillis());
        OtpRelayResponder.Attempt attempt = new OtpRelayResponder.Attempt();
        currentAttempt = attempt;
        // 登録する前に止められていたら、ここで取りやめる (onStopped が見逃した分)。
        if (isStopped()) attempt.imap.abort();
        try {
            handle(ctx, pairing, request, deliveredAt, attempt);
        } finally {
            currentAttempt = null;
            // 答え始めた依頼は、途中で止められても処理済みにする (再実行で二重に答えない)。
            // メールを読んでいる途中で止められたら、onStopped が取得を取りやめ、失敗として中継サーバに返す
            // (依頼は 120 秒で切れ、再実行を待つより PC を手入力に戻す方が早い)。答える前に止められて
            // 何も返せなかったものだけは記録しない (再実行で続きをやる)。
            if (attempt.responded() || !isStopped()) OtpRelayStore.markHandled(request.requestId);
        }
        return Result.success();
    }

    private void handle(Context ctx, OtpRelayPairing pairing, OtpRelayRequest request, long deliveredAt,
                        OtpRelayResponder.Attempt attempt) {
        OtpRelayResponder responder = responder(ctx);
        if (!pairing.hasMailConsent()) {
            // メールは読まずに断る。アプリを開くと確認が出る (通知を押しても出る)。
            OtpRelayResponder.Outcome outcome =
                    responder.fetchAndRespond(pairing, request, deliveredAt, OtpRelayTiming.MAIL_WAIT_LIMIT_MS, attempt);
            if (OtpRelayResponder.CONSENT_REQUIRED.equals(outcome.reason)) {
                OtpRelayNotifications.consentRequired(ctx, pairing, request);
            }
            return;
        }

        showProgress(ctx, pairing, request);
        long startedAt = System.currentTimeMillis();
        long delay = Math.max(0, OtpRelayTiming.respondDeadline(startedAt, request.expiresAt) - startedAt);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> expiry = watchdog.schedule(() -> {
            OtpRelayResponder.ExpiryResult result =
                    responder.respondExpired(pairing, request, attempt, OtpRelayTiming.EXPIRY_POST_TIMEOUT_MS);
            // 取得の処理が先に答え始めていたなら、通知はそちらが出す。
            if (result == OtpRelayResponder.ExpiryResult.NOT_CLAIMED) return;
            OtpRelayStore.markHandled(request.requestId);
            if (result == OtpRelayResponder.ExpiryResult.ALREADY_ANSWERED) OtpRelayNotifications.cancel(ctx, request);
            else if (result == OtpRelayResponder.ExpiryResult.ACCEPTED) OtpRelayNotifications.timedOut(ctx, request);
            else OtpRelayNotifications.unknownResult(ctx, pairing, request);
        }, delay, TimeUnit.MILLISECONDS);
        try {
            OtpRelayResponder.Outcome outcome =
                    responder.fetchAndRespond(pairing, request, deliveredAt, OtpRelayTiming.MAIL_WAIT_LIMIT_MS, attempt);
            if (OtpRelayResponder.DEADLINE_PASSED.equals(outcome.reason)) {
                // 見張りが失敗を返し、通知も出す。送り終わるまで待つ (先に仕事を終えるとプロセスごと止められうる)。
                awaitQuietly(expiry);
            } else {
                expiry.cancel(false);
                report(ctx, pairing, request, outcome);
            }
        } finally {
            watchdog.shutdown();
        }
    }

    /** 見張りが失敗を送り終わるのを待つ。POST の上限 + 余裕で打ち切る。 */
    private static void awaitQuietly(ScheduledFuture<?> future) {
        try {
            future.get(OtpRelayTiming.EXPIRY_POST_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | java.util.concurrent.CancellationException e) {
            Log.w(TAG, "expiry response did not finish: " + e);
        }
    }

    private void showProgress(Context ctx, OtpRelayPairing pairing, OtpRelayRequest request) {
        // Android 11 以前は expedited が前景サービスとして動き、getForegroundInfo の通知が既に出ている。
        // 同じ文面を 2 つ並べない。
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(
                    OtpRelayNotifications.notificationId(request.requestId),
                    OtpRelayNotifications.progress(ctx, pairing.browserName));
        } catch (SecurityException e) {
            Log.w(TAG, "progress notification not shown: " + e);
        }
    }

    private static void report(Context ctx, OtpRelayPairing pairing, OtpRelayRequest request,
                               OtpRelayResponder.Outcome outcome) {
        if (outcome.kind == OtpRelayResponder.Kind.SENT) {
            OtpRelayNotifications.sent(ctx, pairing, request);
        } else if (OtpRelayResponder.ALREADY_ANSWERED.equals(outcome.reason)) {
            // 前の実行の結果を上書きしない。この実行で出した「読んでいます」だけを消す。
            OtpRelayNotifications.cancel(ctx, request);
        } else {
            OtpRelayNotifications.failed(ctx, request, outcome.reason);
        }
    }

    @NonNull
    static OtpRelayResponder responder(@NonNull Context ctx) {
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        return new OtpRelayResponder(
                new OtpRelayApi(),
                new OtpRelayResponder.MailSource() {
                    @NonNull
                    @Override
                    public ImapOtpFetcher.OtpMail fetch(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                                                        long since, @NonNull ImapOtpFetcher.Progress progress)
                            throws Exception {
                        return new ImapOtpFetcher(progress).fetchOtpMail(uid, pass, timeoutSeconds, since);
                    }

                    @Override
                    public void deleteUsed(@NonNull ImapOtpFetcher.OtpMail mail) {
                        mail.deleteUsedMails(3_000);
                    }
                },
                new OtpRelayResponder.Credentials() {
                    @NonNull @Override public String username() { return prefs.getUsername(); }
                    @NonNull @Override public String password() { return prefs.getPassword(); }
                },
                OtpRelayStore::setLastResult,
                System::currentTimeMillis,
                new OtpRelayResponder.TotpSource() {
                    @Override
                    public boolean authenticatorMethod() {
                        return com.shakenokirimi12.uoa_app.services.idp.OtpMethod.AUTHENTICATOR.value.equals(prefs.getOtpMethod());
                    }

                    @androidx.annotation.Nullable
                    @Override
                    public com.shakenokirimi12.uoa_app.services.idp.Totp.Params keyFor(@NonNull String uid) {
                        // 今の ID の鍵だけ (別の ID の鍵は totpOtpauthFor が返さない)。
                        return com.shakenokirimi12.uoa_app.services.idp.Totp.parse(prefs.totpOtpauthFor(uid));
                    }

                    @Override
                    public boolean claimStep(long step) {
                        // アプリのログインと同じ台帳。同じコードを中継とログインで二重に使わない。
                        return com.shakenokirimi12.uoa_app.services.idp.TotpStepLedger.shared().claim(step);
                    }
                },
                Thread::sleep);
    }
}
