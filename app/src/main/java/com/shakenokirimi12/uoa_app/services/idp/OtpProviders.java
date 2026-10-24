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

    /** 登録の確認で送った窓を使用済みにする (AuthenticatorOtpProvider.markUsed)。 */
    public static void markStepUsed(@NonNull Totp.Params params, long timeMillis) {
        AuthenticatorOtpProvider.markUsed(params, timeMillis);
    }

    /**
     * バックグラウンドの文脈で、人もメールも使わずにコードを用意できるか。認証アプリ方式で、このアプリが
     * uid の鍵を持っているときだけ true。受け取り方がまだ決まっていない端末とメール方式は false。
     */
    public static boolean canRunUnattended(@NonNull String uid, @NonNull String pass) {
        return forLogin(uid, pass, AuthorizationContext.BACKGROUND).canRunUnattended();
    }

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
    static OtpProvider of(@NonNull OtpMethod method, @NonNull String uid, @NonNull String pass,
                                  @NonNull AuthorizationContext context) {
        switch (method) {
            case AUTHENTICATOR:
                return new AuthenticatorOtpProvider(uid, context,
                        () -> PreferenceManager.getInstance().totpOtpauthFor(uid));
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
     * 認証アプリ方式。登録のときに必ずシークレットをこのアプリにも保存し、アプリがコードを作って入れる
     * (iOS と同じ)。バックグラウンドの再ログインも同じシークレットで作る (canRunUnattended)。
     * 持っていないのは、この決まりより前に登録した人か、作ったコードを IdP に弾かれて捨てたときだけ
     * (登録し直しが要る)。その間は前面でだけ認証アプリのコードを手入力してもらい、バックグラウンドでは
     * INTERACTIVE_LOGIN_REQUIRED で止める (OtpPromptCoordinator.codeFromAuthenticator)。
     */
    static final class AuthenticatorOtpProvider implements OtpProvider {
        /** 窓の終わり間際に作ると、送る間に切れて弾かれうる。これより残りが短ければ次の窓を待つ。 */
        private static final long MIN_MS_LEFT = 3_000L;

        private final String uid;
        private final AuthorizationContext context;
        /** このアプリが持つ uid の otpauth URL。持っていなければ null。 */
        private final java.util.function.Supplier<String> otpauth;
        /** 直前の code() を保存したシークレットから作ったか。 */
        private volatile boolean generated;
        /**
         * 使った窓の記録。Moodle と CampusSquare のログインが同じ窓に続く、PC への中継が同じ窓を使う、と同じコードを
         * 2 回送ることになり、IdP に弾かれうる。使用済みなら次の窓まで待つ (iOS の TOTPStepLedger と同じ)。
         */
        private final TotpStepLedger ledger;

        AuthenticatorOtpProvider(String uid, AuthorizationContext context, java.util.function.Supplier<String> otpauth) {
            this(uid, context, otpauth, TotpStepLedger.shared());
        }

        AuthenticatorOtpProvider(String uid, AuthorizationContext context, java.util.function.Supplier<String> otpauth,
                                 TotpStepLedger ledger) {
            this.uid = uid;
            this.context = context;
            this.otpauth = otpauth;
            this.ledger = ledger;
        }

        @Override public boolean requiresMailDispatch() { return false; }

        @Override public boolean canRunUnattended() {
            return Totp.parse(otpauth.get()) != null;
        }

        @NonNull
        @Override public String code() throws Exception {
            Totp.Params params = Totp.parse(otpauth.get());
            generated = params != null;
            if (params == null) return OtpPromptCoordinator.getInstance().codeFromAuthenticator(context);
            return unusedCode(params, ledger);
        }

        /** 登録の確認で送った窓を使用済みにする。このあとのログインで同じコードを送り直さない (弾かれうる)。 */
        static void markUsed(@NonNull Totp.Params params, long timeMillis) {
            // 既に新しい窓を使っていれば何もしない (claim は新しい窓だけを記録する)。
            TotpStepLedger.shared().claim(Totp.step(params, timeMillis));
        }

        /** 残りが十分で、まだ送っていない窓のコード。待つのは高々 2 窓分。 */
        @NonNull
        private static String unusedCode(@NonNull Totp.Params params, @NonNull TotpStepLedger ledger) throws Exception {
            for (int i = 0; i < 3; i++) {
                long now = System.currentTimeMillis();
                long left = Totp.millisLeftInWindow(params, now);
                long step = Totp.step(params, now);
                if (left >= MIN_MS_LEFT && ledger.claim(step)) return Totp.code(params, now);
                Thread.sleep(left + 100);
            }
            throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE, "no unused TOTP step became available");
        }

        /** 保存したシークレットで作ったコードだけ、次の窓で 1 回やり直す (手入力のコードは入れ直してもらう)。 */
        @Override public boolean retriesRejectedCode() { return generated; }

        /**
         * アプリが作ったコードを、次の窓でのやり直しも含めて IdP が 2 回続けて受け付けなかった。保存したシークレットが
         * IdP の登録と食い違っている (別の場所で登録し直した等) ので捨て、次からは認証アプリのコードを入力してもらう
         * (iOS と同じ)。1 回目では捨てない。同じ窓を PC への中継や別のログインが先に使っていると、正しい鍵でも弾かれうる。
         */
        @Override public void onRejected() {
            if (!generated) return;
            android.util.Log.w("OtpProviders", "IdP rejected the generated code; discarding the stored authenticator secret");
            PreferenceManager.getInstance().clearTotp();
        }
    }
}
