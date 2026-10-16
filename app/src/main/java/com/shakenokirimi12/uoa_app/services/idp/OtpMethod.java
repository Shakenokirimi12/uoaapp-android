package com.shakenokirimi12.uoa_app.services.idp;

/**
 * OTP の受け取り方。ユーザーが設定で選ぶ。iOS の OTPMethod と同じ値を使う
 * (アプリ間で設定の意味を揃えるため)。
 */
public enum OtpMethod {
    /** メールで届く確認コード。IdP 側に motp.cgi への送信要求が要る。 */
    MAIL("mail"),
    /**
     * 認証アプリ (Google / Microsoft Authenticator、1Password 等) が表示する TOTP。
     * このアプリはシークレットを保持せず、ユーザーが認証アプリを見て手入力する。
     */
    AUTHENTICATOR("authenticator");

    public final String value;

    OtpMethod(String value) { this.value = value; }

    public static OtpMethod from(String raw) {
        for (OtpMethod method : values()) {
            if (method.value.equals(raw)) return method;
        }
        return MAIL;
    }
}
