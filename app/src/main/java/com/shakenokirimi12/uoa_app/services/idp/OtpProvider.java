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
     * 方式の正は IdP 側の登録なので、ログインのたびにこの画面の値で選ぶ (OtpProviders)。
     */
    @NonNull
    default OtpProvider forOtpPage(@Nullable String otpType) throws SeciossError { return this; }

    /**
     * 人に入力を求めずにコードを用意できるか (バックグラウンドの新規ログインの前提)。認証アプリ方式は止めていない鍵が
     * あるとき、メール方式は IMAP の自動取得で用意できる。決めるのは BackgroundLoginPolicy。
     */
    default boolean canRunUnattended() { return false; }

    /**
     * motp.cgi で OTP メールの送信を頼む直前に呼ぶ。送ってはいけない (バックグラウンドの 6 時間の枠が空いていない等) なら
     * SeciossError を投げる。送ったあとでは止めても遅い。
     */
    default void beforeMailDispatch() throws SeciossError {}

    /** code() で渡したコードを IdP が受け付けなかった。 */
    default void onRejected() {}

    /** code() で渡したコードを IdP が受け付けた。使い終わった OTP メールを消すのはここ (SPEC.md「8. OTP メールの削除」)。 */
    default void onAccepted() {}

    /** ログインが終わった (成否を問わない)。持っている接続などを手放す。何度呼んでもよい。 */
    default void release() {}

    /**
     * 弾かれたとき、同じログインの中で 1 回だけ code() からやり直すか (SeciossIdPClient)。やり直しも弾かれたら
     * onRejected を呼ぶ。
     */
    default boolean retriesRejectedCode() { return false; }
}
