package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.AuthorizationContext;

/**
 * 設定と文脈から OtpProvider の実体を決める。
 * 「どの方式を使うか」の判断はここだけが持ち、認証チェーンにも UI にも持たせない。
 */
public final class OtpProviders {

    private OtpProviders() {}

    @NonNull
    public static OtpProvider forLogin(@NonNull String uid, @NonNull String pass,
                                       @NonNull AuthorizationContext context) {
        PreferenceManager prefs = PreferenceManager.getInstance();
        String stored = prefs.getUsername();
        // 保存しているアカウントとは別のアカウントでのログイン (設定画面でのアカウント変更の確認)。保存している
        // 方式は前のアカウントのものなので使わず、IdP から読んだ方式でこのログインだけ進める (保存はしない)。
        if (!stored.isEmpty() && !stored.equals(uid)) return new UndecidedOtpProvider(uid, pass, context, false);
        if (!prefs.isOtpMethodKnown()) return new UndecidedOtpProvider(uid, pass, context, true);
        return of(OtpMethod.from(prefs.getOtpMethod()), uid, pass, context);
    }

    /**
     * OTP 入力画面の otp_type から、IdP がログインに使っている方式を読む。分からなければ null。
     * 'mail' はメール方式のアカウントで確かめた値 (2026-09-07 の HAR、uoa-unofficial-extension の
     * fixtures/idp/allotplogin.html)。'none' は同じページの JS が「OTP 未設定」(auth_err_020) として
     * 扱う値なので方式には数えない。認証アプリ方式で出る値は未確かめなので、それ以外の値を認証アプリとみなす
     * (OtpRegistrationCommit.idpUsesMethod と同じ見分け方)。
     */
    @Nullable
    static OtpMethod methodFromOtpType(@Nullable String otpType) {
        if (otpType == null || otpType.trim().isEmpty() || "none".equalsIgnoreCase(otpType.trim())) return null;
        return "mail".equalsIgnoreCase(otpType.trim()) ? OtpMethod.MAIL : OtpMethod.AUTHENTICATOR;
    }

    @NonNull
    private static OtpProvider of(@NonNull OtpMethod method, @NonNull String uid, @NonNull String pass,
                                  @NonNull AuthorizationContext context) {
        switch (method) {
            case AUTHENTICATOR:
                return new AuthenticatorOtpProvider(context);
            case MAIL:
            default:
                return new MailOtpProvider(uid, pass, context);
        }
    }

    /**
     * 受け取り方がまだ決まっていない端末 (新しく入れた・復元した・アカウントを変えた) のログイン。
     * 受け取り方は大学側 (IdP) に登録されている方式と揃っていないとログインできないので、既定のメールを
     * 決め打ちせず、OTP 入力画面の otp_type から IdP の方式を読んで保存し、その方式で続ける。
     * 読めなければメール方式で進み、保存はしない (次のログインでまた読む)。persist が false (保存している
     * アカウントとは別のアカウントでのログイン) なら、読んだ方式で進むだけで保存しない。
     * iOS は初期設定で本人に方式を選んでもらう。Android の初期設定にはその段が無いので、こうしている。
     */
    private static final class UndecidedOtpProvider implements OtpProvider {
        private final String uid;
        private final String pass;
        private final AuthorizationContext context;
        private final OtpProvider fallback;
        private final boolean persist;

        UndecidedOtpProvider(String uid, String pass, AuthorizationContext context, boolean persist) {
            this.uid = uid;
            this.pass = pass;
            this.context = context;
            this.fallback = new MailOtpProvider(uid, pass, context);
            this.persist = persist;
        }

        @Override public boolean requiresMailDispatch() { return fallback.requiresMailDispatch(); }

        @NonNull
        @Override public String code() throws Exception { return fallback.code(); }

        @NonNull
        @Override public OtpProvider forOtpPage(@Nullable String otpType) {
            OtpMethod method = methodFromOtpType(otpType);
            if (method == null) return fallback;
            if (!persist) return of(method, uid, pass, context);
            PreferenceManager.getInstance().adoptOtpMethodFromIdp(method);
            // 登録の完了がほぼ同時に別の方式を書いていたら、そちらに従う (adopt は決まっていれば書かない)。
            return of(OtpMethod.from(PreferenceManager.getInstance().getOtpMethod()), uid, pass, context);
        }
    }

    /**
     * メールで届いたコードを IMAP から取得し、取れなければ入力を求める。
     * 取得と入力の出し分けは OtpPromptCoordinator が持つ。
     */
    private static final class MailOtpProvider implements OtpProvider {
        private final String uid;
        private final String pass;
        private final AuthorizationContext context;

        MailOtpProvider(String uid, String pass, AuthorizationContext context) {
            this.uid = uid;
            this.pass = pass;
            this.context = context;
        }

        @Override public boolean requiresMailDispatch() { return true; }

        @NonNull
        @Override public String code() throws Exception {
            return OtpPromptCoordinator.getInstance().code(uid, pass, context);
        }
    }

    /**
     * 認証アプリが表示する TOTP を手入力してもらう。
     *
     * アプリがシークレットを保持しないため自動取得はできない。バックグラウンド更新では必ず
     * INTERACTIVE_LOGIN_REQUIRED になる。方式としてこれを選べる代わりに、セッションの永続化が
     * 効いていることが前提になる。
     */
    private static final class AuthenticatorOtpProvider implements OtpProvider {
        private final AuthorizationContext context;

        AuthenticatorOtpProvider(AuthorizationContext context) { this.context = context; }

        @Override public boolean requiresMailDispatch() { return false; }

        @NonNull
        @Override public String code() throws Exception {
            return OtpPromptCoordinator.getInstance().codeFromAuthenticator(context);
        }
    }
}
