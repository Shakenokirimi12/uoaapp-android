package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

    /**
     * OTP 入力画面に着いたときに、その画面の otp_type (読めなければ null) を渡して、使う実体を決め直す。
     * 受け取り方がまだ決まっていない端末で、IdP が実際に使っている方式に合わせるため (OtpProviders)。
     */
    @NonNull
    default OtpProvider forOtpPage(@Nullable String otpType) { return this; }

    /**
     * 人に入力を求めず、メールも使わずにコードを用意できるか。バックグラウンドの新規ログインを始めてよいかは
     * これで決まる (BackgroundLoginPolicy)。メール方式は常に false (ログイン 1 回 = OTP メール 1 通)。
     */
    default boolean canRunUnattended() { return false; }

    /** code() で渡したコードを IdP が受け付けなかった。 */
    default void onRejected() {}

    /**
     * 弾かれたとき、同じログインの中で 1 回だけ code() からやり直すか (SeciossIdPClient)。やり直しも弾かれたら
     * onRejected を呼ぶ。
     */
    default boolean retriesRejectedCode() { return false; }
}
