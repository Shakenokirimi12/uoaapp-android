package com.shakenokirimi12.uoa_app.services.idp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.UoaApplication;

/**
 * ログインの途中で「ワンタイムパスワード未設定」と分かったとき、設定画面を探させずにその場で
 * 登録画面を開く (iOS の AppState.showOTPRegistration と同じ)。設定画面から新規登録を始める導線は持たない
 * (設定画面からは、設定済みのアカウントの方式の切り替えとしてだけ開く)。
 * バックグラウンド同期 (前面 Activity 無し) では開かない。同じプロセス内で何度も積まない。
 */
public final class OtpRegistrationLauncher {
    private static final long REPEAT_SUPPRESS_MS = 10 * 60 * 1000L;
    private static volatile long lastLaunchedAt;

    private OtpRegistrationLauncher() {}

    /** SeciossError が OTP_NOT_CONFIGURED なら登録画面を開く。それ以外は何もしない。 */
    public static void launchIfNeeded(@NonNull Throwable error) {
        Throwable t = error;
        while (t != null && !(t instanceof SeciossError)) t = t.getCause();
        if (!(t instanceof SeciossError) || ((SeciossError) t).kind != SeciossError.Kind.OTP_NOT_CONFIGURED) return;
        Activity activity = UoaApplication.getForegroundActivity();
        // 初期設定のログインでも開く (iOS は初期設定の画面の上に出す)。ID / パスワードはまだ保存していないので、
        // 初期設定が確かめている最中の値で登録する (IdPOtpRegistrationActivity)。初期設定のログインは利用者が
        // ボタンを押したときだけ走るので、間隔を空けずに毎回開く (閉じてから押し直したら、また登録を出す)。
        if (activity instanceof com.shakenokirimi12.uoa_app.ui.onboarding.OnboardingActivity) {
            new Handler(Looper.getMainLooper()).post(() ->
                    ((com.shakenokirimi12.uoa_app.ui.onboarding.OnboardingActivity) activity).openOtpRegistration());
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLaunchedAt < REPEAT_SUPPRESS_MS) return;
        if (!(activity instanceof MainActivity)) return;
        lastLaunchedAt = now;
        new Handler(Looper.getMainLooper()).post(() -> ((MainActivity) activity).openOtpRegistration());
    }
}
