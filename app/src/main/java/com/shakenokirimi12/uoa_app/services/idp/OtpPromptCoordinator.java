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
import com.shakenokirimi12.uoa_app.ui.settings.AuthenticatorKeyChoice;

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
 *   2. 取れなかったら、アプリが前面にいるときだけ入力ダイアログを出して待つ (手入力が最後の手段)。
 *      認証アプリ方式で使える鍵が無いときは、手入力の前に取り込み・登録し直しを選べるようにする (codeWithoutKey)
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
     * 認証アプリ方式で、このアプリに uid の使える鍵が無い (無い・止めている)。前面でだけ、手入力の前に
     * 取り込む・登録し直す・手入力・あとで を選んでもらう (AuthenticatorKeyChoice)。
     * 手入力ならそのコードを、鍵を取り込んだら null を返す (呼び出し側が取り込んだ鍵でコードを作る)。
     * 登録し直す・あとで・時間切れは取り消し (cancelled)。登録し直すなら、取り消したあとで登録し直しの画面を開く。
     * バックグラウンドでは選ばせない (INTERACTIVE_LOGIN_REQUIRED)。
     */
    @Nullable
    public String codeWithoutKey(@NonNull String uid, @NonNull AuthorizationContext context) throws Exception {
        if (!context.allowsNewLogin()) {
            throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
        }
        Activity activity = UoaApplication.getForegroundActivity();
        if (activity == null) throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);

        Handler main = new Handler(Looper.getMainLooper());
        BlockingQueue<AuthenticatorKeyChoice.Outcome> result = new ArrayBlockingQueue<>(1);
        java.util.concurrent.atomic.AtomicReference<AuthenticatorKeyChoice.Handle> handle =
                new java.util.concurrent.atomic.AtomicReference<>();
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(INPUT_TIMEOUT_MINUTES);
        main.post(() -> handle.set(AuthenticatorKeyChoice.show(activity, uid, result::offer)));
        // 取り込み (認証アプリを開いてスクリーンショットを撮る) も含めて、手入力と同じ時間だけ待つ。IdP の OTP 入力画面の
        // 有効期限が分からないので延ばさない (切れたあとに取り込んだ鍵のコードを送ると、弾かれて鍵を止めうる)。
        AuthenticatorKeyChoice.Outcome outcome = result.poll(INPUT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (outcome == null) {
            // 時間切れ。出したままの選択を閉じる。取り込みの手順の途中なら手順は残し、保存できたらホームで取り直す
            // (取り込んだ鍵は、このログインには使わない)。
            main.post(() -> {
                AuthenticatorKeyChoice.Handle h = handle.get();
                if (h != null) h.cancel();
            });
            throw cancelled();
        }
        switch (outcome) {
            case IMPORTED:
                return null;
            case MANUAL: {
                // 選んでいた時間も含めて、手入力と同じ時間だけ待つ (iOS も選択と入力で 1 つの待ち受け)。
                long left = Math.max(0, deadline - System.currentTimeMillis());
                String code = promptOnMainThread(activity, AppStrings.get(R.string.otp_prompt_authenticator_code), left);
                if (code == null) throw cancelled();
                return code;
            }
            case REREGISTER: {
                // 先に取り消しを記録してから開く。登録し直しのログインが、取り消しの前に IdpLoginGate で待ち始めると、
                // この取り消しに巻き込まれて止まる。
                SeciossError error = cancelled();
                main.post(() -> {
                    if (activity instanceof com.shakenokirimi12.uoa_app.ui.idp.OtpReregistrationHost
                            && !activity.isFinishing() && !activity.isDestroyed()) {
                        ((com.shakenokirimi12.uoa_app.ui.idp.OtpReregistrationHost) activity).openOtpReregistration();
                    }
                });
                throw error;
            }
            case LATER:
            default:
                throw cancelled();
        }
    }

    /**
     * 認証アプリ方式の手入力。鍵があっても使える窓が来なかったとき (OtpProviders.AuthenticatorOtpProvider) と、
     * 別のアカウントへの切り替えの確認 (保存している鍵も設定も前のアカウントのもの) で使う。
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
        return promptOnMainThread(activity, message, TimeUnit.MINUTES.toMillis(INPUT_TIMEOUT_MINUTES));
    }

    @Nullable
    private String promptOnMainThread(@NonNull Activity activity, @NonNull String message, long timeoutMillis)
            throws InterruptedException {
        BlockingQueue<String> result = new ArrayBlockingQueue<>(1);
        Handler main = new Handler(Looper.getMainLooper());
        java.util.concurrent.atomic.AtomicReference<Runnable> dismiss = new java.util.concurrent.atomic.AtomicReference<>();
        main.post(() -> dismiss.set(OtpPromptDialog.show(activity, message, entered ->
                result.offer(entered != null ? entered : CANCELLED))));
        String value = result.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (value == null) {
            // 時間切れ。出したままにすると、入れたコードがどこにも届かない。閉じる (出す処理は先に main に積んである)。
            main.post(() -> {
                Runnable d = dismiss.get();
                if (d != null) d.run();
            });
        }
        return value == null || CANCELLED.equals(value) ? null : value;
    }
}
