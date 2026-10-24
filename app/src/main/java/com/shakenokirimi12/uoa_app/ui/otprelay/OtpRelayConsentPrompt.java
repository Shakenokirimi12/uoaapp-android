package com.shakenokirimi12.uoa_app.ui.otprelay;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairing;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairingService;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayStore;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 同意を記録する前に作った連携の確認 (iOS の OTPRelayAlertsModifier の consent と同じ)。連携し直さずに
 * 済むよう、起動時・設定の「PC との連携 (OTP)」を開いたとき・断った依頼の通知を押したときに確かめる。
 * 「続ける」で同意を記録し (以後は自動で返す)、「解除」で連携を解除する。確かめ終わるまで、その連携の
 * 依頼はメールを読まずに断る (OtpRelayResponder.fetchAndRespond)。
 */
public final class OtpRelayConsentPrompt {
    private OtpRelayConsentPrompt() {}

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 確認を出している画面。同時に 2 つ出さない (画面が無くなったら出し直せる)。 */
    @Nullable private static WeakReference<Activity> owner;
    /** 起動してからの確認を最後まで終えたか (答え終わった・確かめるものが無かった)。 */
    private static boolean launchCheckDone;

    /**
     * 起動して前面に出たときに確かめる。最後まで答えたら、このプロセスでは聞き直さない (前面に戻るたびには
     * 聞かない)。答える前に画面が作り直された (回転・ダークモードの切り替え) ときはダイアログが消えるので、
     * 次に前面に出たときにもう一度出す。
     */
    @MainThread
    public static void showAtLaunch(@NonNull Activity activity) {
        if (launchCheckDone) return;
        show(activity, () -> launchCheckDone = true);
    }

    /**
     * 同意の無い連携があれば 1 件ずつ確かめる。
     * @param onDone 確かめ終わったら (確かめるものが無いときも) 呼ぶ。別の画面で確認を出している途中なら
     *               何もせずに false を返し、onDone も呼ばない。
     */
    @MainThread
    public static boolean show(@NonNull Activity activity, @Nullable Runnable onDone) {
        Activity current = owner != null ? owner.get() : null;
        if (current != null && !current.isFinishing() && !current.isDestroyed()) return false;
        owner = new WeakReference<>(activity);
        next(activity, new HashSet<>(), onDone);
        return true;
    }

    private static void next(Activity activity, Set<String> asked, @Nullable Runnable onDone) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            owner = null;
            return;
        }
        OtpRelayPairing pairing = null;
        for (OtpRelayPairing p : OtpRelayStore.all()) {
            if (!p.hasMailConsent() && !asked.contains(p.pairingId)) {
                pairing = p;
                break;
            }
        }
        if (pairing == null) {
            OtpRelayPairing totp = needsTotpConsent(asked);
            if (totp != null) {
                askTotpConsent(activity, totp, asked, onDone);
                return;
            }
            done(onDone);
            return;
        }
        // 「解除」に失敗して閉じたものを、同じ流れの中でもう一度聞かない (次は起動時か設定を開いたとき)。
        asked.add(pairing.pairingId);
        OtpRelayPairing p = pairing;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.otp_relay_consent_title)
                .setMessage(activity.getString(R.string.otp_relay_consent_message, p.browserName))
                .setCancelable(false)
                .setPositiveButton(R.string.otp_relay_consent_continue, (d, w) -> {
                    if (OtpRelayStore.recordConsent(p.pairingId, System.currentTimeMillis())) {
                        next(activity, asked, onDone);
                    } else {
                        message(activity, activity.getString(R.string.otp_relay_consent_save_failed), () -> done(onDone));
                    }
                })
                .setNegativeButton(R.string.otp_relay_unpair, (d, w) -> unpair(activity, p, asked, onDone))
                .show();
    }

    /**
     * 認証アプリ方式の人 (設定が認証アプリで、今の ID のセットアップキーを持つ) だけに聞く。メールの同意だけがある
     * (以前の文面で作った) 連携のうち最初のもの。無ければ null。
     */
    @Nullable
    private static OtpRelayPairing needsTotpConsent(Set<String> asked) {
        com.shakenokirimi12.uoa_app.data.PreferenceManager prefs = com.shakenokirimi12.uoa_app.data.PreferenceManager.getInstance();
        boolean authenticator = com.shakenokirimi12.uoa_app.services.idp.OtpMethod.AUTHENTICATOR.value.equals(prefs.getOtpMethod());
        String uid = prefs.getUsername().trim();
        if (!authenticator || uid.isEmpty() || prefs.totpOtpauthFor(uid) == null) return null;
        for (OtpRelayPairing p : OtpRelayStore.all()) {
            if (p.hasMailConsent() && !p.hasTotpConsent() && !asked.contains(p.pairingId)) return p;
        }
        return null;
    }

    /** 「「<PC>」に認証アプリのコードを送りますか？」。「あとで」は記録しない (次に起動したときにもう一度聞く)。 */
    private static void askTotpConsent(Activity activity, OtpRelayPairing p, Set<String> asked, @Nullable Runnable onDone) {
        asked.add(p.pairingId);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.otp_relay_totp_consent_title, p.browserName))
                .setMessage(R.string.otp_relay_totp_consent_message)
                .setCancelable(false)
                .setPositiveButton(R.string.otp_relay_totp_consent_send, (d, w) -> {
                    if (OtpRelayStore.recordTotpConsent(p.pairingId, System.currentTimeMillis())) {
                        next(activity, asked, onDone);
                    } else {
                        message(activity, activity.getString(R.string.otp_relay_consent_save_failed), () -> done(onDone));
                    }
                })
                .setNegativeButton(R.string.otp_relay_unpair, (d, w) -> unpair(activity, p, asked, onDone))
                .setNeutralButton(R.string.otp_relay_totp_consent_later, (d, w) -> next(activity, asked, onDone))
                .show();
    }

    private static void unpair(Activity activity, OtpRelayPairing p, Set<String> asked, @Nullable Runnable onDone) {
        EXECUTOR.execute(() -> {
            boolean ok;
            String error = null;
            try {
                ok = OtpRelayPairingService.unpair(p);
            } catch (IOException | RuntimeException e) {
                // RuntimeException も拾う。拾わないと確認の途中のまま止まり、以後どの画面からも確認を出せない。
                android.util.Log.w("OtpRelay", "unpair from consent prompt failed: " + e);
                ok = false;
                error = activity.getString(R.string.otp_relay_unreachable);
            }
            boolean done = ok;
            String reason = error;
            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    owner = null;
                    return;
                }
                if (done) {
                    next(activity, asked, onDone);
                    return;
                }
                // 解除できなかった連携は、同意が無いまま残る (依頼は断り続ける)。設定の画面と同じく、
                // この端末からだけ消すこともできるようにする。
                String failed = activity.getString(R.string.otp_relay_unpair_failed_message);
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.otp_relay_unpair_failed_title)
                        .setMessage(reason != null ? reason + "\n\n" + failed : failed)
                        .setCancelable(false)
                        .setPositiveButton(R.string.otp_relay_forget_local, (d, w) -> {
                            OtpRelayStore.remove(p.pairingId);
                            next(activity, asked, onDone);
                        })
                        .setNegativeButton(R.string.cancel, (d, w) -> next(activity, asked, onDone))
                        .show();
            });
        });
    }

    private static void message(Activity activity, String text, Runnable then) {
        new MaterialAlertDialogBuilder(activity)
                .setMessage(text)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (d, w) -> then.run())
                .show();
    }

    private static void done(@Nullable Runnable onDone) {
        owner = null;
        if (onDone != null) onDone.run();
    }
}
