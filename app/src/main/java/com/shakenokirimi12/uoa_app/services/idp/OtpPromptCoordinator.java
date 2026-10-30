package com.shakenokirimi12.uoa_app.services.idp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.UoaApplication;
import com.shakenokirimi12.uoa_app.services.AppStrings;
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
 *   3. バックグラウンド (WorkManager の同期) では IMAP の自動取得だけ。取れなければ OTP_FETCH_FAILED。
 *      前面にいないのに前面の文脈で来たときは INTERACTIVE_LOGIN_REQUIRED を投げ、次に前面へ来たときに任せる
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

    /** メール方式で用意したコード。mail は IMAP から取れたときのメール (接続を持ったまま)、手入力なら null。 */
    public static final class MailCode {
        @NonNull public final String code;
        @Nullable public final ImapOtpFetcher.OtpMail mail;

        MailCode(@NonNull String code, @Nullable ImapOtpFetcher.OtpMail mail) {
            this.code = code;
            this.mail = mail;
        }
    }

    /**
     * メール方式。OTP が実際に要求されたときだけ呼ばれる。取れたメールは消さずに返す。消すのは IdP がコードを
     * 受け付けてから (OtpProvider.onAccepted、SPEC.md「8. OTP メールの削除」)。返した mail は呼び出し側が必ず閉じる。
     * バックグラウンドでは IMAP の自動取得だけを使い、取れなければ OTP_FETCH_FAILED (手入力は待たない)。
     */
    @NonNull
    public MailCode mailCode(@NonNull String uid, @NonNull String pass,
                             @NonNull AuthorizationContext context) throws Exception {
        boolean background = !context.allowsNewLogin();
        // SeciossIdPClient は motp.cgi を叩いた直後にここへ来る。メール送信の時刻として使う。
        long requestedAt = System.currentTimeMillis();
        ImapOtpFetcher fetcher = new ImapOtpFetcher();
        try {
            ImapOtpFetcher.OtpMail mail = fetcher.fetchOtpMail(uid, pass, 30, requestedAt);
            // 取得 1 回ごとに診断の記録 (Wana) を送る (待たない)。
            com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayResponder.reportLoginFetch(fetcher.progress(), null);
            return new MailCode(mail.code, mail);
        } catch (Exception e) {
            com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayResponder.reportLoginFetch(fetcher.progress(), e);
            // 例外の文面はサーバの応答行を含みうるので、決まった名前だけを残す (Progress.describe)。
            android.util.Log.w("OtpPromptCoordinator", "メールからの自動取得に失敗: " + ImapOtpFetcher.Progress.describe(e));
            if (background) {
                throw new SeciossError(SeciossError.Kind.OTP_FETCH_FAILED, ImapOtpFetcher.Progress.describe(e));
            }
        }

        // メールが届かない・メールサーバーに繋がらない。手入力に落とす。
        Activity activity = UoaApplication.getForegroundActivity();
        if (activity == null) throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);

        String code = promptOnMainThread(activity,
                AppStrings.get(R.string.otp_prompt_mail_manual_fallback));
        if (code == null) throw cancelled();
        return new MailCode(code, null);
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

        String code = promptOnMainThread(activity, AppStrings.get(R.string.otp_prompt_authenticator_code));
        if (code == null) throw cancelled();
        return code;
    }

    /**
     * 入力を取り消した (時間切れを含む)。待っていた別のサービスのログインと、同じ同期の残りのログインを始めない
     * (IdpLoginGate、SPEC.md「5. 前面ログイン」)。
     */
    @NonNull
    private static SeciossError cancelled() {
        IdpLoginGate.shared().noteCancelled();
        return new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, "otp prompt cancelled");
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
