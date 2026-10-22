package com.shakenokirimi12.uoa_app.ui.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;

/**
 * OTP の登録で、アカウントを書き換える POST の前後を受け持つ (iOS の IdPOTPRegistrationFlow の
 * completeEmail / finishRegistration / failRegistration と同じ判断)。Android にも LiveData にも依存しない。
 *
 * このアプリの受け取り方 (otp_method) を新しい方式に書き換えるのはここだけ。大学側 (IdP) に登録されている
 * 方式と揃っていないとログインできないので、登録が済んだと分かったときにしか書かない。
 */
public final class OtpRegistrationCommit {
    /** 登録した方式を、このアプリの受け取り方として保存する。 */
    public interface MethodWriter { void apply(@NonNull OtpMethod method); }

    /** 学外の経路でパスワードだけ入り、OTP 入力画面の otp_type を読む。読めなければ null。 */
    public interface OtpTypeReader { @Nullable String read(); }

    public enum Result {
        /** 登録できた。受け取り方を書き換えた。 */
        SUCCEEDED,
        /** IdP は完了と返したが、ログインに使う方式が切り替え先になっていない。受け取り方は変えていない。 */
        NOT_SWITCHED,
        /** 登録できなかった。受け取り方は変えていない。 */
        FAILED
    }

    /** 設定画面からの切り替えなら切り替え先。null なら OTP 未設定のアカウントの新規登録。 */
    @Nullable private final OtpMethod switchingTo;
    @NonNull private final MethodWriter writer;
    @NonNull private final OtpTypeReader reader;
    private boolean dismissed;
    private boolean posting;

    public OtpRegistrationCommit(@Nullable OtpMethod switchingTo, @NonNull MethodWriter writer,
                                 @NonNull OtpTypeReader reader) {
        this.switchingTo = switchingTo;
        this.writer = writer;
        this.reader = reader;
    }

    @Nullable public OtpMethod switchingTo() { return switchingTo; }

    /**
     * 登録の POST を送ってよいか。画面が閉じられたあとは送らない (閉じたあとに IdP だけが変わるのを防ぐ)。
     * true を返したら、以後 dismiss() は処理を止めさせない。
     */
    public synchronized boolean tryBeginPost() {
        if (dismissed) return false;
        posting = true;
        return true;
    }

    /**
     * 画面が閉じられた。POST をもう送っていれば true を返す。そのときは処理を止めずに最後まで走らせ、
     * 結果 (受け取り方の書き換え) を取りこぼさないようにする。
     */
    public synchronized boolean dismiss() {
        dismissed = true;
        return posting;
    }

    /** IdP が「設定が完了しました」を返したあと。切り替えでは otp_type で確かめてから書き換える。 */
    @NonNull
    public Result afterCompletion(@NonNull OtpMethod registered) {
        if (switchingTo != null && !idpUsesMethod(registered, reader.read())) return Result.NOT_SWITCHED;
        writer.apply(registered);
        return Result.SUCCEEDED;
    }

    /**
     * 登録の POST が完了の表示を返さなかったとき。IdP が受け付けたあとで応答だけ失われた場合、失敗に
     * すると IdP だけが切り替わる。切り替えでは otp_type を読み、IdP がすでに切り替え先でログインさせると
     * 読めたときだけ成功にする (読めなければ失敗のまま)。
     */
    @NonNull
    public Result afterError(@NonNull OtpMethod registered) {
        if (switchingTo == null) return Result.FAILED;
        String otpType = reader.read();
        if (otpType == null || otpType.isEmpty() || !idpUsesMethod(registered, otpType)) return Result.FAILED;
        writer.apply(registered);
        return Result.SUCCEEDED;
    }

    /**
     * IdP がログインに method を使っているとみなせるか。確かめられているのは、メール方式のアカウントで
     * otp_type が 'mail' になることだけなので、'mail' かどうかだけで見分ける。値が読めないときは登録完了の
     * 表示を信じる (学内にいると OTP 入力画面を通らないので、読めないことがある)。
     */
    static boolean idpUsesMethod(@NonNull OtpMethod method, @Nullable String otpType) {
        if (otpType == null || otpType.isEmpty()) return true;
        return "mail".equals(otpType) == (method == OtpMethod.MAIL);
    }
}
