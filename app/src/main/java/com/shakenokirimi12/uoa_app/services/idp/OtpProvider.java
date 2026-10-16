package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;

/**
 * コードを 1 つ調達するだけのインターフェース。認証チェーンは方式を知らない。
 * iOS の Auth/OTPProvider.swift と同じ形。
 *
 * 方式ごとに違うのは実質 2 点しかないので、それだけを問う。
 */
public interface OtpProvider {
    /**
     * IdP にメール送信を要求する必要があるか (motp.cgi, motprefresh=0)。
     * メール方式は要る。認証アプリ方式は要らない。
     * 以前はここを区別せず常に送っていた。方式を選べるようにする以上、分ける。
     */
    boolean requiresMailDispatch();

    /** コードを取得する。取れない場合は SeciossError を投げる。 */
    @NonNull String code() throws Exception;
}
