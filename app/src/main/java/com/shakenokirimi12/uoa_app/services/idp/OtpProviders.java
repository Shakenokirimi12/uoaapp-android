package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.AuthorizationContext;

/**
 * IdP の画面・設定・文脈から OtpProvider の実体を決める。
 * 「どの方式を使うか」の判断はここだけが持ち、認証チェーンにも UI にも持たせない。
 *
 * 方式の正は IdP 側の登録で、設定の方式はその控え (SPEC.md「3.1 方式の決め方」)。ログインのたびに OTP 入力画面の
 * otp_type を読んで選び、控えと違えば書き換える。読めなければ控えを、控えも無ければメール方式を使う。
 */
public final class OtpProviders {

    private OtpProviders() {}

    /** 登録の確認で送った窓を使用済みにする (AuthenticatorOtpProvider.markUsed)。 */
    public static void markStepUsed(@NonNull Totp.Params params, long timeMillis) {
        AuthenticatorOtpProvider.markUsed(params, timeMillis);
    }

    /**
     * uid のバックグラウンドの新規ログインで使う方式。人に入力を求めずにコードを用意できないなら null。
     * - 控えが無い、または保存しているアカウントと違う: null (どの方式で入るか分からない)
     * - 認証アプリ方式: このアプリが uid の止めていない鍵を持つときだけ
     * - メール方式: IMAP の自動取得で用意する (送ってよいかは BackgroundLoginPolicy が 6 時間の枠で決める)
     */
    @Nullable
    public static OtpMethod unattendedMethod(@NonNull String uid) {
        PreferenceManager prefs = PreferenceManager.getInstance();
        if (!uid.equals(prefs.getUsername()) || !prefs.isOtpMethodKnown()) return null;
        OtpMethod method = OtpMethod.from(prefs.getOtpMethod());
        if (method == OtpMethod.AUTHENTICATOR && Totp.parse(prefs.usableTotpOtpauthFor(uid)) == null) return null;
        return method;
    }

    public static boolean canRunUnattended(@NonNull String uid, @NonNull String pass) {
        return unattendedMethod(uid) != null;
    }

    @NonNull
    public static OtpProvider forLogin(@NonNull String uid, @NonNull String pass,
                                       @NonNull AuthorizationContext context) {
        return new ResolvingOtpProvider(uid, pass, context);
    }

    @NonNull
    static OtpProvider of(@NonNull OtpMethod method, @NonNull String uid, @NonNull String pass,
                          @NonNull AuthorizationContext context) {
        switch (method) {
            case AUTHENTICATOR:
                // 止めた鍵 (2 回弾かれた) は自動では使わない。前面では手入力になる (SPEC.md「7.2」)。
                return new AuthenticatorOtpProvider(uid, context,
                        () -> PreferenceManager.getInstance().usableTotpOtpauthFor(uid));
            case MAIL:
            default:
                return new MailOtpProvider(uid, pass, context);
        }
    }

    /**
     * OTP 入力画面に着くまでは方式を決めない。着いたら otp_type で選ぶ (forOtpPage)。
     * 保存しているアカウントとは別のアカウントでのログイン (設定画面でのアカウント変更の確認) では、保存している
     * 控えと鍵は前のアカウントのものなので使わず、書き換えもしない。
     */
    private static final class ResolvingOtpProvider implements OtpProvider {
        private final String uid;
        private final String pass;
        private final AuthorizationContext context;
        /** このアカウントの控えを読み書きしてよいか (保存しているアカウントと同じ、または未保存)。 */
        private final boolean ownsSettings;

        ResolvingOtpProvider(String uid, String pass, AuthorizationContext context) {
            this.uid = uid;
            this.pass = pass;
            this.context = context;
            String stored = PreferenceManager.getInstance().getUsername();
            this.ownsSettings = stored.isEmpty() || stored.equals(uid);
        }

        @Nullable
        private OtpMethod cached() {
            PreferenceManager prefs = PreferenceManager.getInstance();
            if (!ownsSettings || !prefs.isOtpMethodKnown()) return null;
            return OtpMethod.from(prefs.getOtpMethod());
        }

        private OtpProvider fallback() {
            OtpMethod cached = cached();
            return of(cached != null ? cached : OtpMethod.MAIL, uid, pass, context);
        }

        // forOtpPage の前に問われたときは控えの方式で答える (SeciossIdPClient は必ず forOtpPage を先に呼ぶ)。
        @Override public boolean requiresMailDispatch() { return fallback().requiresMailDispatch(); }

        @NonNull
        @Override public String code() throws Exception { return fallback().code(); }

        @Override public boolean canRunUnattended() { return ownsSettings && unattendedMethod(uid) != null; }

        @Override public void beforeLogin() throws SeciossError {
            com.shakenokirimi12.uoa_app.services.BackgroundLoginPolicy.getInstance()
                    .checkBeforeIdpLogin(uid, pass, !context.allowsNewLogin());
        }

        @NonNull
        @Override public OtpProvider forOtpPage(@Nullable String otpType) {
            OtpMethod read = OtpMethod.fromOtpType(otpType);
            OtpMethod cached = cached();
            reportOtpType(otpType, read, cached);
            if (read == null) return fallback();
            if (ownsSettings) {
                PreferenceManager prefs = PreferenceManager.getInstance();
                // 控えは IdP の値に揃える (別の場所で登録し直した場合も、次のログインで追従する)。
                prefs.recordOtpMethodFromIdp(read);
                // IdP がメール方式なら、このアカウントの認証アプリの鍵は使えないので消す
                // (アプリから切り替えたときと同じ。SPEC.md「3.1 方式の決め方」、decisions D-12)。
                if (read == OtpMethod.MAIL && prefs.totpOtpauthFor(uid) != null) prefs.clearTotp();
            }
            return of(read, uid, pass, context);
        }

        /**
         * otp_type の生の値を OTP 診断に残す。認証アプリ方式で何が来るかが未確認なので、実データで確かめる
         * (秘密ではない。設定「利用状況と診断情報を送る」がオフなら送らない)。
         */
        private void reportOtpType(@Nullable String raw, @Nullable OtpMethod read, @Nullable OtpMethod cached) {
            try {
                java.util.Map<String, String> tags = new java.util.TreeMap<>();
                tags.put("otp_type", otpTypeLabel(raw));
                tags.put("otp_type_method", read != null ? read.value : "unknown");
                tags.put("cached_method", cached != null ? cached.value : "unknown");
                tags.put("login_context", context.allowsNewLogin() ? "interactive" : "background");
                com.shakenokirimi12.uoa_app.services.diagnostics.OtpDiagnostics.sendAsync(java.util.Collections.singletonList(
                        com.shakenokirimi12.uoa_app.services.diagnostics.OtpDiagnostics.event("IdPOtpType", null,
                                com.shakenokirimi12.uoa_app.services.diagnostics.OtpDiagnostics.CONTEXT_LOGIN,
                                read != null ? read.value : "unknown",
                                com.shakenokirimi12.uoa_app.services.diagnostics.OtpDiagnostics.supportId(), tags,
                                new com.google.gson.JsonObject(), java.util.Collections.emptyList(),
                                com.shakenokirimi12.uoa_app.services.diagnostics.OtpDiagnostics.Build.current(),
                                System.currentTimeMillis())));
            } catch (RuntimeException e) {
                // 診断が送れなくてもログインは続ける (単体テストでは android の API が使えない)。
            }
        }
    }

    /** 診断に載せる otp_type。null は "(absent)"、空は "(empty)"。英数と - _ だけを 32 文字まで残す。 */
    @NonNull
    static String otpTypeLabel(@Nullable String raw) {
        if (raw == null) return "(absent)";
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_-]", "?");
        if (cleaned.isEmpty()) return "(empty)";
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    /**
     * メールで届いたコードを IMAP から取得し、取れなければ (前面だけ) 入力を求める。
     * 取得と入力の出し分けは OtpPromptCoordinator が持つ。取れたメールは IdP がコードを受け付けてから消す。
     */
    static final class MailOtpProvider implements OtpProvider {
        private final String uid;
        private final String pass;
        private final AuthorizationContext context;
        /** 直前の code() で使ったメール (接続を持ったまま)。手入力なら null。 */
        @Nullable private ImapOtpFetcher.OtpMail mail;
        @Nullable private String sent;

        MailOtpProvider(String uid, String pass, AuthorizationContext context) {
            this.uid = uid;
            this.pass = pass;
            this.context = context;
        }

        @Override public boolean requiresMailDispatch() { return true; }

        @Override public boolean canRunUnattended() { return true; }

        /** バックグラウンドでは、端末全体で 6 時間に 1 回の枠を、送信を頼む直前に取る (BackgroundLoginPolicy)。 */
        @Override public void beforeMailDispatch() throws SeciossError {
            if (context.allowsNewLogin()) return;
            com.shakenokirimi12.uoa_app.services.BackgroundLoginPolicy.getInstance().claimMailDispatch();
        }

        @NonNull
        @Override public String code() throws Exception {
            release();
            OtpPromptCoordinator.MailCode got = OtpPromptCoordinator.getInstance().mailCode(uid, pass, context);
            synchronized (this) {
                mail = got.mail;
                sent = got.code;
            }
            return got.code;
        }

        /**
         * IdP が受け付けた。送ったコードが取得済みのメールのものなら、そのメールとそれより古い OTP メールを消す。
         * 手入力で、取得済みのメールに同じコードが無ければ消さない (SPEC.md「8. OTP メールの削除」)。
         */
        @Override public void onAccepted() {
            ImapOtpFetcher.OtpMail used;
            String code;
            synchronized (this) {
                used = mail;
                code = sent;
                mail = null;
            }
            if (used == null) return;
            if (shouldDeleteAfterAccepted(used.code, code)) used.deleteUsedMails(DELETE_TIMEOUT_MS);
            else used.close();
        }

        @Override public void release() {
            ImapOtpFetcher.OtpMail held;
            synchronized (this) {
                held = mail;
                mail = null;
            }
            if (held != null) held.close();
        }
    }

    /** 使ったメールを消すのに使う時間の上限 (中継と同じ。SPEC.md「8. OTP メールの削除」)。 */
    static final long DELETE_TIMEOUT_MS = 3_000L;

    /** 取得済みのメールのコード mailCode と、IdP に送って受け付けられた sentCode が同じか (空白は無視する)。 */
    static boolean shouldDeleteAfterAccepted(@Nullable String mailCode, @Nullable String sentCode) {
        if (mailCode == null || sentCode == null) return false;
        String a = mailCode.replaceAll("\\s", "");
        String b = sentCode.replaceAll("\\s", "");
        return !a.isEmpty() && a.equals(b);
    }

    /**
     * 認証アプリ方式。登録のときに必ずシークレットをこのアプリにも保存し、アプリがコードを作って入れる
     * (iOS と同じ)。バックグラウンドの再ログインも同じシークレットで作る (canRunUnattended)。
     * 使えないのは、この決まりより前に登録した人 (鍵が無い) か、作ったコードを IdP に 2 回弾かれて止めたとき
     * (登録し直しが要る)。その間は前面でだけ認証アプリのコードを手入力してもらい、バックグラウンドでは
     * INTERACTIVE_LOGIN_REQUIRED で止める (OtpPromptCoordinator.codeFromAuthenticator)。
     */
    static final class AuthenticatorOtpProvider implements OtpProvider {
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

        /** 鍵を止める (PreferenceManager.suspendTotp)。テストで差し替える。 */
        private final java.util.function.Consumer<String> suspend;

        /** 手入力のコード (OtpPromptCoordinator.codeFromAuthenticator)。テストで差し替える。 */
        interface ManualCode { @NonNull String code(@NonNull AuthorizationContext context) throws Exception; }
        private final ManualCode manual;

        AuthenticatorOtpProvider(String uid, AuthorizationContext context, java.util.function.Supplier<String> otpauth) {
            this(uid, context, otpauth, TotpStepLedger.shared(), AuthenticatorOtpProvider::suspendStoredKey);
        }

        AuthenticatorOtpProvider(String uid, AuthorizationContext context, java.util.function.Supplier<String> otpauth,
                                 TotpStepLedger ledger) {
            this(uid, context, otpauth, ledger, AuthenticatorOtpProvider::suspendStoredKey);
        }

        AuthenticatorOtpProvider(String uid, AuthorizationContext context, java.util.function.Supplier<String> otpauth,
                                 TotpStepLedger ledger, java.util.function.Consumer<String> suspend) {
            this(uid, context, otpauth, ledger, suspend, ctx -> OtpPromptCoordinator.getInstance().codeFromAuthenticator(ctx));
        }

        AuthenticatorOtpProvider(String uid, AuthorizationContext context, java.util.function.Supplier<String> otpauth,
                                 TotpStepLedger ledger, java.util.function.Consumer<String> suspend, ManualCode manual) {
            this.manual = manual;
            this.uid = uid;
            this.context = context;
            this.otpauth = otpauth;
            this.ledger = ledger;
            this.suspend = suspend;
        }

        @Override public boolean requiresMailDispatch() { return false; }

        @Override public boolean canRunUnattended() {
            return Totp.parse(otpauth.get()) != null;
        }

        @NonNull
        @Override public String code() throws Exception {
            Totp.Params params = Totp.parse(otpauth.get());
            generated = params != null;
            if (params == null) return manual.code(context);
            try {
                return unusedCode(params, ledger);
            } catch (SeciossError e) {
                // 使える窓が来なかった (時計が戻って、台帳の窓が今より先にある等)。前面では手入力に回す。
                // バックグラウンドは止めた印を立てずに諦める (TOTP_STEP_UNAVAILABLE はアプリ内の事情)。
                if (e.kind != SeciossError.Kind.TOTP_STEP_UNAVAILABLE || !context.allowsNewLogin()) throw e;
                generated = false;
                return manual.code(context);
            }
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
                // 台帳の窓が 2 窓以上先 (時計が戻った) なら、待っても使える窓は来ない。待たずに諦める。
                if (ledger.lastUsed() >= step + 2) break;
                // 窓の終わり間際に作ると、送る間に切れて弾かれうる。中継と同じ下限 (Totp.MIN_MS_LEFT_TO_SEND)。
                if (left >= Totp.MIN_MS_LEFT_TO_SEND && ledger.claim(step)) return Totp.code(params, now);
                Thread.sleep(left + 100);
            }
            // アプリ内の事情 (使える窓が来なかった)。バックグラウンドの止めた印は立てない (TOTP_STEP_UNAVAILABLE)。
            throw new SeciossError(SeciossError.Kind.TOTP_STEP_UNAVAILABLE, "no unused TOTP step became available");
        }

        /** 保存したシークレットで作ったコードだけ、次の窓で 1 回やり直す (手入力のコードは入れ直してもらう)。 */
        @Override public boolean retriesRejectedCode() { return generated; }

        /**
         * アプリが作ったコードを、次の窓でのやり直しも含めて IdP が 2 回続けて受け付けなかった。保存したシークレットが
         * IdP の登録と食い違っている (別の場所で登録し直した等) か、時計がずれている。鍵は消さずに止め、自動では
         * 使わない (前面は手入力、バックグラウンドと PC 中継は使わない)。登録し直しの成功で外れる
         * (SPEC.md「7.2 鍵のコードが IdP に 2 回弾かれたとき」)。1 回目では止めない。同じ窓を PC への中継や
         * 別のログインが先に使っていると、正しい鍵でも弾かれうる。
         */
        @Override public void onRejected() {
            if (!generated) return;
            suspend.accept(uid);
        }

        private static void suspendStoredKey(@NonNull String owner) {
            android.util.Log.w("OtpProviders", "IdP rejected the generated code twice; suspending the stored authenticator key");
            PreferenceManager.getInstance().suspendTotp(owner);
        }
    }
}
