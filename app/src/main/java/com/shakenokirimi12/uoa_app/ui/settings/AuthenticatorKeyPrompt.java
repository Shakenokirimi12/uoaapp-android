package com.shakenokirimi12.uoa_app.ui.settings;

import android.app.Activity;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import com.shakenokirimi12.uoa_app.services.idp.Totp;

/**
 * 認証アプリ方式なのに、このアプリがキーを持っていない人への案内 (iOS の AuthPromptsModifier と同じ)。
 * 認証アプリ方式はキーを必ずこのアプリにも持つ決まりにした (2026-10-24) ので、それより前に登録した人と、
 * 作ったコードを IdP に弾かれてキーを捨てた人が対象。前面に出たときに一度だけ出し、アプリ全体は止めない。
 * 閉じても、ログインの設定画面に登録し直しの案内が残る。キーを保存すると、また案内できるように戻る
 * (PreferenceManager.saveTotp)。
 */
public final class AuthenticatorKeyPrompt {
    private AuthenticatorKeyPrompt() {}

    /** キーが無く、登録し直しが要るか。 */
    public static boolean needsReregistration(@NonNull PreferenceManager prefs) {
        return prefs.isOtpMethodKnown()
                && OtpMethod.from(prefs.getOtpMethod()) == OtpMethod.AUTHENTICATOR
                && Totp.parse(prefs.totpOtpauthFor(prefs.getUsername())) == null;
    }

    /** 出している案内。前面に戻るたびに重ねて出さない。 */
    @androidx.annotation.Nullable
    private static java.lang.ref.WeakReference<androidx.appcompat.app.AlertDialog> showing;

    @MainThread
    public static void showAtLaunch(@NonNull Activity activity, @NonNull Runnable openLoginSettings) {
        androidx.appcompat.app.AlertDialog current = showing != null ? showing.get() : null;
        if (current != null && current.isShowing()) return;
        PreferenceManager prefs = PreferenceManager.getInstance(activity);
        if (prefs.isTotpReregistrationPrompted()) return;
        if (!prefs.hasCredentials() || prefs.isCredentialsInvalid() || !needsReregistration(prefs)) return;
        // 案内したと記録するのは、利用者が答えたとき (ボタン・戻る・外側のタップ)。答える前に画面が作り直されて
        // (回転など) ダイアログが消えたら、次に前面に出たときにもう一度出す。
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.login_reregister_needed_title)
                .setMessage(R.string.login_reregister_needed_text)
                .setPositiveButton(R.string.login_reregister_prompt_open, (d, w) -> {
                    prefs.setTotpReregistrationPrompted();
                    openLoginSettings.run();
                })
                .setNegativeButton(R.string.login_reregister_prompt_later, (d, w) -> prefs.setTotpReregistrationPrompted())
                .setOnCancelListener(d -> prefs.setTotpReregistrationPrompted())
                .show();
        showing = new java.lang.ref.WeakReference<>(dialog);
    }
}
