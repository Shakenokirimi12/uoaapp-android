package com.shakenokirimi12.uoa_app.services.idp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.UoaApplication;
import com.shakenokirimi12.uoa_app.services.AuthorizationContext;
import com.shakenokirimi12.uoa_app.ui.idp.OtpPromptDialog;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * IdP ログインの途中でワンタイムパスワードを要求されたときの窓口。iOS の OTPPromptCoordinator の移植。
 *
 * 設定画面に「IdP でログイン」のようなボタンは置かない方針なので、コードが要る瞬間にここが判断する:
 *   1. メール方式なら、まず IMAP から取る (ユーザーの操作は不要)。メール方式を選んでいることが、
 *      メールを読む同意になる (設定の説明に書いてある)。以前の「メールから自動で取得する」の選択は
 *      2026-10-21 に廃止した
 *   2. 取れなかったら、アプリが前面にいるときだけ入力ダイアログを出して待つ (手入力が最後の手段)
 *   3. 前面にいない (WorkManager の同期) なら待ち受けられないので INTERACTIVE_LOGIN_REQUIRED を投げ、
 *      次に前面へ来たときの同期に任せる
 *
 * 呼び出し元はサービス層のワーカースレッドなので、ダイアログの結果はキュー経由で受け取って
 * そのスレッドをブロックする (UI スレッドからは絶対に呼ばないこと)。
 */
public final class OtpPromptCoordinator {
    private static final OtpPromptCoordinator INSTANCE = new OtpPromptCoordinator();
    /** 入力待ちの上限。IdP 側の sessid もいずれ切れるので、無限には待たない。 */
    private static final long INPUT_TIMEOUT_MINUTES = 3;
    /** ArrayBlockingQueue は null を入れられないので、キャンセルはこの番兵で表す。 */
    private static final String CANCELLED = "\u0000cancelled";

    public static OtpPromptCoordinator getInstance() { return INSTANCE; }

    private OtpPromptCoordinator() {}

    /** メール方式。OTP が実際に要求されたときだけ呼ばれる。 */
    @NonNull
    public String code(@NonNull String uid, @NonNull String pass,
                       @NonNull AuthorizationContext context) throws Exception {
        // バックグラウンドでは入力も自動取得も待ち受けない。ここまで来ること自体が想定外だが
        // (ServiceAuthenticator が手前で止める)、経路が増えたときのために念のため塞ぐ。
        if (!context.allowsNewLogin()) {
            throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
        }
        // SeciossIdPClient は motp.cgi を叩いた直後にここへ来る。メール送信の時刻として使う。
        long requestedAt = System.currentTimeMillis();
        try {
            return new ImapOtpFetcher().fetchOtpCode(uid, pass, 30, requestedAt);
        } catch (Exception e) {
            // メールが届かない・メールサーバーに繋がらない。手入力に落とす。
            android.util.Log.w("OtpPromptCoordinator", "メールからの自動取得に失敗: " + e);
        }

        Activity activity = UoaApplication.getForegroundActivity();
        if (activity == null) throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);

        String code = promptOnMainThread(activity,
                "メールからワンタイムパスワードを取得できませんでした。届いたコードを入力してください。");
        if (code == null) throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
        return code;
    }

    /**
     * 認証アプリ方式。シークレットを持たないので自動取得はできず、必ず手入力になる。
     * メールの自動取得は使わないため、受信箱には一切触れない。
     */
    @NonNull
    public String codeFromAuthenticator(@NonNull AuthorizationContext context) throws Exception {
        if (!context.allowsNewLogin()) {
            throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
        }
        Activity activity = UoaApplication.getForegroundActivity();
        if (activity == null) throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);

        String code = promptOnMainThread(activity, "認証アプリに表示されているコードを入力してください。");
        if (code == null) throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
        return code;
    }

    /** キャンセル・タイムアウトなら null。 */
    @Nullable
    private String promptOnMainThread(@NonNull Activity activity, @NonNull String message)
            throws InterruptedException {
        BlockingQueue<String> result = new ArrayBlockingQueue<>(1);
        new Handler(Looper.getMainLooper()).post(() ->
                OtpPromptDialog.show(activity, message, entered ->
                        result.offer(entered != null ? entered : CANCELLED)));
        String value = result.poll(INPUT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        return value == null || CANCELLED.equals(value) ? null : value;
    }
}
