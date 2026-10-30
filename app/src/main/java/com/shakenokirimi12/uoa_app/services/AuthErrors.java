package com.shakenokirimi12.uoa_app.services;

import android.content.Context;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

/**
 * ログイン系サービス (Moodle / CampusSquare) が共通で使う、ID/PW 誤りの判定。
 *
 * ID/PW 誤りだけは他の失敗 (メンテナンス・通信エラー・想定外 HTML) と区別する必要がある。
 * 誤ったまま自動同期がリトライを繰り返すと大学側でアカウントがロックされうるため、
 * これを検出したら資格情報の指紋に誤りの印を付け (BackgroundLoginPolicy)、ID かパスワードが変わるまでログインしない。
 */
public final class AuthErrors {
    public static String invalidCredentialsMessage() {
        return AppStrings.get(R.string.auth_error_invalid_credentials);
    }

    private AuthErrors() {}

    public static boolean isInvalidCredentials(String message) {
        return invalidCredentialsMessage().equals(message);
    }

    /**
     * バックグラウンドで新規ログインの条件を満たさないと SeciossError(INTERACTIVE_LOGIN_REQUIRED) で止まる。
     * 失敗ではなく「ユーザーが開いてログインするのを待っている」状態。サービスは例外を文字列で渡してくるので、
     * 種別名か、ログイン画面向けの文言 (SeciossError.loginMessage。ログインの途中で投げたものは包まれている) で
     * 見分ける (Moodle は「接続エラー: 」を前に付ける)。
     */
    public static boolean isInteractiveLoginRequired(String message) {
        if (message == null) return false;
        com.shakenokirimi12.uoa_app.services.idp.SeciossError marker = new com.shakenokirimi12.uoa_app.services.idp.SeciossError(
                com.shakenokirimi12.uoa_app.services.idp.SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
        return message.contains(marker.kind.name()) || message.contains(marker.loginMessage());
    }

    /** 各画面の onError から呼ぶ。保存している資格情報に誤りの印を付ける (前面なので通知は出さない)。 */
    public static void markInvalidIfCredentialsError(Context context, String message) {
        if (isInvalidCredentials(message)) {
            PreferenceManager prefs = PreferenceManager.getInstance(context.getApplicationContext());
            BackgroundLoginPolicy.getInstance().markInvalid(prefs.getUsername(), prefs.getPassword());
        }
    }

    /**
     * 誤り文言の判定は <script> を除いてから行う。ページの JS 内に同じ文言が文字列として
     * 埋まっているだけで、正しいパスワードのユーザーを止める誤検知を避ける (iOS で実例あり)。
     */
    public static String withoutScripts(String html) {
        return html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
    }
}
