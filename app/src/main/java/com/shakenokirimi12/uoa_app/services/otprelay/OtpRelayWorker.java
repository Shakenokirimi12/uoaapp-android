package com.shakenokirimi12.uoa_app.services.otprelay;

import android.content.Context;
import android.content.Intent;
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

/**
 * PC からの依頼の処理。FCM の受信・通知のボタンから、すぐ動く (expedited) 仕事として積む。
 *
 * 自動で返すのは「連携が自動になっている」かつ「メールからの自動取得に同意している」ときだけ。
 * 同意していなければメールは読まず、承認の通知を出して、押されたときにだけ読む
 * (同意の無いまま大学のメールボックスに入らない)。
 */
public class OtpRelayWorker extends Worker {
    private static final String TAG = "OtpRelay";

    static final String MODE_INCOMING = "incoming";
    static final String MODE_APPROVE = "approve";
    static final String MODE_REJECT = "reject";
    static final String MODE_REREGISTER = "reregister";

    private static final String K_MODE = "mode";
    private static final String K_PAIRING = "pairingId";
    private static final String K_REQUEST = "requestId";
    private static final String K_REQUESTED_AT = "requestedAt";
    private static final String K_EXPIRES_AT = "expiresAt";
    private static final String K_DELIVERED_AT = "deliveredAt";
    private static final String K_TOKEN = "token";

    public OtpRelayWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** FCM で依頼が届いた。同じ依頼が二重に届いても 1 回だけ処理する (KEEP)。 */
    public static void onIncoming(@NonNull Context ctx, @NonNull OtpRelayRequest request, long deliveredAt) {
        enqueue(ctx, MODE_INCOMING, request, deliveredAt);
    }

    static void enqueue(@NonNull Context ctx, @NonNull String mode, @NonNull OtpRelayRequest request, long deliveredAt) {
        Data data = new Data.Builder()
                .putString(K_MODE, mode)
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
        // 受信は重複を捨てる。承認・拒否は前の処理 (承認待ちの通知を出した受信) を置き換える。
        ExistingWorkPolicy policy = MODE_INCOMING.equals(mode) ? ExistingWorkPolicy.KEEP : ExistingWorkPolicy.REPLACE;
        WorkManager.getInstance(ctx).enqueueUniqueWork("otp_relay_" + request.requestId, policy, work);
    }

    /** FCM トークンが変わった。通信できるときに全部の連携を登録し直す。 */
    public static void reregister(@NonNull Context ctx, @NonNull String token) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(OtpRelayWorker.class)
                .setInputData(new Data.Builder().putString(K_MODE, MODE_REREGISTER).putString(K_TOKEN, token).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork("otp_relay_reregister", ExistingWorkPolicy.REPLACE, work);
    }

    /** アプリ内の確認ダイアログの「承認して送る」。 */
    public static void approveFromUi(@NonNull Context ctx, @NonNull OtpRelayRequest request) {
        enqueue(ctx, MODE_APPROVE, request, request.requestedAt);
    }

    /** アプリ内の確認ダイアログの「拒否」。 */
    public static void rejectFromUi(@NonNull Context ctx, @NonNull OtpRelayRequest request) {
        OtpRelayNotifications.cancel(ctx, request);
        enqueue(ctx, MODE_REJECT, request, request.requestedAt);
    }

    public static void putRequestExtras(@NonNull Intent intent, @NonNull OtpRelayRequest r) {
        putRequest(intent, r);
    }

    static void putRequest(@NonNull Intent intent, @NonNull OtpRelayRequest r) {
        intent.putExtra(K_PAIRING, r.pairingId).putExtra(K_REQUEST, r.requestId)
                .putExtra(K_REQUESTED_AT, r.requestedAt).putExtra(K_EXPIRES_AT, r.expiresAt);
    }

    @Nullable
    public static OtpRelayRequest getRequest(@Nullable Intent intent) {
        if (intent == null) return null;
        String pairingId = intent.getStringExtra(K_PAIRING);
        String requestId = intent.getStringExtra(K_REQUEST);
        if (pairingId == null || requestId == null) return null;
        return new OtpRelayRequest(pairingId, requestId,
                intent.getLongExtra(K_REQUESTED_AT, 0), intent.getLongExtra(K_EXPIRES_AT, 0));
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

        OtpRelayRequest request = requestFromInput();
        if (request == null) return Result.success();
        OtpRelayPairing pairing = OtpRelayStore.find(request.pairingId);
        // 知らない連携 (この端末で解除済み) からの依頼は何もしない。
        if (pairing == null) return Result.success();
        OtpRelayResponder responder = responder(ctx);
        long deliveredAt = getInputData().getLong(K_DELIVERED_AT, System.currentTimeMillis());

        if (MODE_REJECT.equals(mode)) {
            responder.reject(pairing, request);
            return Result.success();
        }

        if (MODE_APPROVE.equals(mode)) {
            showProgress(ctx, pairing, request);
            report(ctx, pairing, request, responder.approve(pairing, request));
            return Result.success();
        }

        // 受信。
        if (request.expiresAt <= System.currentTimeMillis()) return Result.success();
        // FCM は同じメッセージを再配信することがある。処理済みの依頼でもう一度メールを読みに行かない
        // (使用済みメールは消してあるので mail-timeout → 承認の通知、という誤った流れになる)。
        if (!OtpRelayStore.markHandled(request.requestId)) return Result.success();
        // 以降、承認で使う依頼時刻は補正済みの値にする (iOS と同じく、承認時に二重に補正しない)。
        OtpRelayRequest effective = new OtpRelayRequest(request.pairingId, request.requestId,
                OtpRelayTiming.effectiveRequestedAt(request.requestedAt, deliveredAt), request.expiresAt);
        boolean consented = PreferenceManager.getInstance(ctx).isOtpAutoFetchEnabled();
        if (!pairing.autoApprove() || !consented) {
            OtpRelayNotifications.approval(ctx, pairing, effective);
            return Result.success();
        }
        showProgress(ctx, pairing, request);
        OtpRelayResponder.Outcome outcome =
                responder.fetchAndRespond(pairing, request, deliveredAt, OtpRelayTiming.MAIL_WAIT_LIMIT_MS);
        if (outcome.kind == OtpRelayResponder.Kind.NEEDS_APPROVAL) {
            OtpRelayNotifications.approval(ctx, pairing, effective);
        } else {
            report(ctx, pairing, request, outcome);
        }
        return Result.success();
    }

    private void showProgress(Context ctx, OtpRelayPairing pairing, OtpRelayRequest request) {
        // Android 11 以前は expedited が前景サービスとして動き、getForegroundInfo の通知が既に出ている。
        // 同じ文面を 2 つ並べないよう、承認の通知を消すだけにする。
        if (android.os.Build.VERSION.SDK_INT < 31) {
            OtpRelayNotifications.cancel(ctx, request);
            return;
        }
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
        switch (outcome.kind) {
            case SENT:
                OtpRelayNotifications.sent(ctx, pairing, request);
                break;
            case ALREADY_ANSWERED:
                OtpRelayNotifications.alreadyAnswered(ctx, request);
                break;
            case FAILED:
                OtpRelayNotifications.failed(ctx, request, outcome.reason);
                break;
            case NEEDS_APPROVAL:
                // approve() は NEEDS_APPROVAL を返さない (mail-timeout として終える)。
                OtpRelayNotifications.failed(ctx, request, "mail-timeout");
                break;
        }
    }

    @NonNull
    static OtpRelayResponder responder(@NonNull Context ctx) {
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        ImapOtpFetcher fetcher = new ImapOtpFetcher();
        return new OtpRelayResponder(
                new OtpRelayApi(),
                new OtpRelayResponder.MailSource() {
                    @NonNull
                    @Override
                    public ImapOtpFetcher.OtpMail fetch(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                                                        long since) throws Exception {
                        return fetcher.fetchOtpMail(uid, pass, timeoutSeconds, since);
                    }

                    @Override
                    public void deleteUsed(@NonNull String uid, @NonNull String pass, int upToUid) throws Exception {
                        fetcher.deleteUsedMails(uid, pass, upToUid, 3_000);
                    }
                },
                new OtpRelayResponder.Credentials() {
                    @NonNull @Override public String username() { return prefs.getUsername(); }
                    @NonNull @Override public String password() { return prefs.getPassword(); }
                },
                OtpRelayStore::setLastResult,
                System::currentTimeMillis);
    }
}
