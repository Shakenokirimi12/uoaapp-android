package com.shakenokirimi12.uoa_app.services.otprelay;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * ログアウトで預かった連携を、中継サーバから消し終えるまでやり直す。
 *
 * 以前はログアウトの瞬間に 1 回だけ解除を投げ、結果を待たずに連携の鍵 (K) を消していた。
 * 圏外などで解除が届かないと、サーバには連携が残って PC の依頼が届き続けるのに、
 * K が無いので端末からは二度と解除できなかった。鍵は解除が済むまで
 * OtpRelayStore の「解除待ち」に残し (ログアウトの clearAll でも消さない)、ここで送り直す。
 */
public class OtpRelayUnpairWorker extends Worker {
    private static final String TAG = "OtpRelayUnpair";
    private static final String WORK_NAME = "otp_relay_unpair";

    public OtpRelayUnpairWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** 解除待ちがあれば、通信できるときに送る。何度呼んでも 1 本にまとまる。 */
    public static void enqueue(@NonNull Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(OtpRelayUnpairWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, work);
    }

    /**
     * 解除の応答で、もう送り直さなくてよいか。204 は消せた、404 は既に無いか鍵が合わない
     * (workers/otp-relay は鍵の不一致も 404 で返す)。400/401/403 は送り直しても通らない。
     * それ以外 (5xx、429 など) は一時的な失敗として送り直す。
     */
    static boolean isSettled(int status) {
        return status == 204 || status == 404 || status == 400 || status == 401 || status == 403;
    }

    @NonNull
    @Override
    public Result doWork() {
        OtpRelayApi api = new OtpRelayApi();
        boolean remaining = false;
        for (OtpRelayPairing p : OtpRelayStore.pendingUnpairs()) {
            OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(p.key);
            if (keys == null) {
                // 鍵が壊れていて、どのみち解除できない。
                OtpRelayStore.removePendingUnpair(p.pairingId);
                continue;
            }
            try {
                int status = api.unpair(p.pairingId, keys).status;
                if (isSettled(status)) {
                    OtpRelayStore.removePendingUnpair(p.pairingId);
                } else {
                    Log.w(TAG, "unpair " + status + "; will retry");
                    remaining = true;
                }
            } catch (IOException e) {
                Log.w(TAG, "unpair failed; will retry: " + e);
                remaining = true;
            }
        }
        return remaining ? Result.retry() : Result.success();
    }
}
