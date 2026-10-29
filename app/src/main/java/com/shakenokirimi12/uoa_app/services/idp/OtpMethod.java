package com.shakenokirimi12.uoa_app.services.idp;

/**
 * OTP の受け取り方。正は IdP 側の登録で、アプリの設定はその控え (ログインのたびに otp_type から書き直す)。
 * iOS の OTPMethod と同じ値を使う (アプリ間で設定の意味を揃えるため)。
 */
public enum OtpMethod {
    /** メールで届く確認コード。IdP 側に motp.cgi への送信要求が要る。 */
    MAIL("mail"),
    /**
     * 認証アプリ (Google / Microsoft Authenticator、1Password 等) が表示する TOTP。
     * 登録のときにこのアプリにもシークレットを保存し、コードを作って入れる (無ければ認証アプリを見て手入力)。
     */
    AUTHENTICATOR("authenticator");

    public final String value;

    OtpMethod(String value) { this.value = value; }

    /**
     * IdP の OTP 入力画面 (allotplogin.cgi) の otp_type から、IdP がログインに使っている方式を読む。
     * otp_type の解釈はここだけが持つ (ログインの OtpProviders と、登録の確認の OtpRegistrationCommit)。
     * - "mail": メール方式 (メール方式のアカウントで確かめた値。2026-09-07 の HAR、uoa-unofficial-extension の
     *   fixtures/idp/allotplogin.html)
     * - "" と "none": 分からない (null)。"none" は同じページの JS が「OTP 未設定」(auth_err_020) として扱う値
     * - それ以外: 認証アプリ方式。認証アプリ方式のアカウントで実際に何が来るかは未確認 (OTP 診断に生の値を載せて確かめる)
     */
    @androidx.annotation.Nullable
    public static OtpMethod fromOtpType(@androidx.annotation.Nullable String otpType) {
        if (otpType == null) return null;
        String v = otpType.trim();
        if (v.isEmpty() || "none".equalsIgnoreCase(v)) return null;
        return "mail".equalsIgnoreCase(v) ? MAIL : AUTHENTICATOR;
    }

    public static OtpMethod from(String raw) {
        for (OtpMethod method : values()) {
            if (method.value.equals(raw)) return method;
        }
        return MAIL;
    }
}
