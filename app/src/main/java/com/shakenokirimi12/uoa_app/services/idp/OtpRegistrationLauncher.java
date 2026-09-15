package com.shakenokirimi12.uoa_app.services.idp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.UoaApplication;

/**
 * ログインの途中で「ワンタイムパスワード未設定」と分かったとき、設定画面を探させずにその場で
 * 登録画面を開く (iOS の AppState.showOTPRegistration と同じ)。設定画面からの手動導線は持たない。
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
        long now = System.currentTimeMillis();
        if (now - lastLaunchedAt < REPEAT_SUPPRESS_MS) return;
        Activity activity = UoaApplication.getForegroundActivity();
        if (!(activity instanceof MainActivity)) return;
        lastLaunchedAt = now;
        new Handler(Looper.getMainLooper()).post(() -> ((MainActivity) activity).openOtpRegistration());
    }
}
