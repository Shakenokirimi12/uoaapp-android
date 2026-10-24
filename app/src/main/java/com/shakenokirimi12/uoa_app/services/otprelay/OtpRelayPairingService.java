package com.shakenokirimi12.uoa_app.services.otprelay;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 連携の登録・解除・再登録。どれも通信するので、メインスレッドから呼ばないこと。
 * iOS の OTPRelayService.pair / unpair / deviceTokenDidChange と同じ手順。
 */
public final class OtpRelayPairingService {
    private static final String TAG = "OtpRelay";

    private OtpRelayPairingService() {}

    /** 失敗の理由。文面は iOS の RelayError に合わせる。 */
    public static final class PairingException extends Exception {
        PairingException(String message) { super(message); }
    }

    /**
     * QR (リンク) の内容で連携する。成功したら保存済みの連携を返す。
     * 保存に失敗したら、サーバ側に作った連携も消してから失敗にする (片側だけ残さない)。
     */
    @NonNull
    public static OtpRelayPairing pair(@NonNull Context ctx, @NonNull OtpRelayLink.Pending pending)
            throws PairingException {
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(pending.key);
        if (keys == null) throw new PairingException(serverMessage(400, "bad-request"));
        if (!PreferenceManager.getInstance(ctx).isSecureStorageAvailable()) {
            throw new PairingException("この端末では連携の鍵を暗号化して保存できないため、連携できません。");
        }
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            throw new PairingException("通知の許可がないため連携できません。設定アプリで通知を許可してから、もう一度 QR を読んでください。");
        }
        String token = fcmToken();
        if (token == null) {
            throw new PairingException("通知の登録 (FCM) が済んでいないため連携できません。通信できる状態で、少し待ってからもう一度 QR を読んでください。");
        }

        OtpRelayApi api = new OtpRelayApi();
        OtpRelayApi.Reply reply;
        try {
            reply = api.register(pending.pairingId, keys, token, deviceName(), pending.browserName, false);
        } catch (IOException e) {
            throw new PairingException("中継サーバに接続できませんでした。");
        }
        if (reply.status != 200 && reply.status != 201) {
            throw new PairingException(serverMessage(reply.status, reply.error));
        }

        OtpRelayPairing pairing = new OtpRelayPairing(pending.pairingId, pending.key, pending.browserName,
                System.currentTimeMillis());
        // 同じ QR の読み直し (既にこの端末にある連携) なら、保存に失敗してもサーバ側は消さない
        // (消すと、端末に残っている元の連携が使えなくなる)。
        boolean existed = OtpRelayStore.find(pending.pairingId) != null;
        if (!OtpRelayStore.add(pairing)) {
            if (existed) throw new PairingException("連携をこの端末に保存できませんでした。もう一度 QR を読んでください。");
            try {
                api.unpair(pending.pairingId, keys);
            } catch (IOException e) {
                Log.w(TAG, "could not roll back the server-side pairing: " + e);
            }
            throw new PairingException("連携をこの端末に保存できませんでした。もう一度 QR を読んでください。");
        }
        return pairing;
    }

    /** 中継サーバから消す。204 / 404 ならこの端末からも消して true。それ以外は false (端末側は残す)。 */
    public static boolean unpair(@NonNull OtpRelayPairing pairing) throws IOException {
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(pairing.key);
        if (keys == null) {
            OtpRelayStore.remove(pairing.pairingId);
            return true;
        }
        OtpRelayApi.Reply reply = new OtpRelayApi().unpair(pairing.pairingId, keys);
        if (reply.status == 204 || reply.status == 404) {
            OtpRelayStore.remove(pairing.pairingId);
            return true;
        }
        return false;
    }

    /**
     * ログアウト時。端末の連携 (K) を消す前に、中継サーバからも消す。消さないと PC の依頼が
     * 届き続け、K が無いので端末からは二度と解除できない。連携は「解除待ち」に移して
     * (clearAll でも残る)、解除が済むまで OtpRelayUnpairWorker が送り直す。
     * 呼んだあとで PreferenceManager.clearAll() を呼んでよい。
     */
    public static void unpairAllOnLogout(@NonNull Context ctx) {
        if (OtpRelayStore.all().isEmpty() && OtpRelayStore.pendingUnpairs().isEmpty()) return;
        if (!OtpRelayStore.moveAllToPendingUnpair()) {
            Log.w(TAG, "could not keep the pairings for unpair; the relay may keep them");
        }
        OtpRelayUnpairWorker.enqueue(ctx.getApplicationContext());
    }

    /**
     * FCM トークンが変わったら、全部の連携を新しいトークンで登録し直す (updateOnly)。
     * サーバに無い (404) ものはこの端末からも消す。それ以外の失敗は残して次に任せる。
     */
    public static void reregisterAll(@NonNull String token) {
        OtpRelayApi api = new OtpRelayApi();
        for (OtpRelayPairing p : OtpRelayStore.all()) {
            OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(p.key);
            if (keys == null) continue;
            try {
                OtpRelayApi.Reply reply = api.register(p.pairingId, keys, token, deviceName(), p.browserName, true);
                if (reply.status == 404) OtpRelayStore.remove(p.pairingId);
                else if (reply.status != 200 && reply.status != 201) {
                    Log.w(TAG, "re-register " + reply.status + " " + reply.error);
                }
            } catch (IOException e) {
                Log.w(TAG, "re-register failed: " + e);
            }
        }
    }

    @Nullable
    private static String fcmToken() {
        try {
            String token = Tasks.await(FirebaseMessaging.getInstance().getToken(), 10, TimeUnit.SECONDS);
            if (token != null && !token.isEmpty()) {
                PreferenceManager.getInstance().setFcmToken(token);
                return token;
            }
        } catch (Exception e) {
            Log.w(TAG, "FCM token unavailable: " + e);
        }
        String cached = PreferenceManager.getInstance().getFcmToken();
        return cached.isEmpty() ? null : cached;
    }

    /** 通知に出る端末名。中継サーバは空白だけ・100 文字超を断る。 */
    @NonNull
    static String deviceName() {
        String model = Build.MODEL != null ? OtpRelayLink.sanitizeLabel(Build.MODEL) : "";
        if (model.isEmpty()) return "Android";
        return model.length() > 100 ? model.substring(0, 100) : model;
    }

    @NonNull
    static String serverMessage(int status, @Nullable String code) {
        if ("pairing-taken".equals(code)) return "この QR は既に別の端末で使われています。PC 側で QR を出し直してください。";
        return "中継サーバでエラーが起きました (" + (code != null ? code : "http-" + status) + ")。";
    }
}
