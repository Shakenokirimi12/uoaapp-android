package com.shakenokirimi12.uoa_app.services;

import android.content.Context;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

/**
 * ログイン系サービス (Moodle / CampusSquare) が共通で使う、ID/PW 誤りの判定。
 *
 * ID/PW 誤りだけは他の失敗 (メンテナンス・通信エラー・想定外 HTML) と区別する必要がある。
 * 誤ったまま自動同期がリトライを繰り返すと大学側でアカウントがロックされうるため、
 * これを検出したら PreferenceManager.setCredentialsInvalid を立てて自動同期を止める。
 */
public final class AuthErrors {
    public static final String INVALID_CREDENTIALS_MESSAGE = "ログインに失敗しました。ユーザー名またはパスワードを確認してください。";

    private AuthErrors() {}

    public static boolean isInvalidCredentials(String message) {
        return INVALID_CREDENTIALS_MESSAGE.equals(message);
    }

    /**
     * バックグラウンドでは新規ログインしない (AuthorizationContext.BACKGROUND) ため、保存済みセッションが
     * 切れていると SeciossError(INTERACTIVE_LOGIN_REQUIRED) で止まる。失敗ではなく「ユーザーが開いて
     * ログインするのを待っている」状態。サービスは例外を文字列で渡してくるので、種別名で見分ける
     * (Moodle は「接続エラー: 」を前に付ける)。
     */
    public static boolean isInteractiveLoginRequired(String message) {
        return message != null && message.contains(
                com.shakenokirimi12.uoa_app.services.idp.SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED.name());
    }

    /** 各画面・ワーカーの onError から呼ぶ。 */
    public static void markInvalidIfCredentialsError(Context context, String message) {
        if (isInvalidCredentials(message)) {
            PreferenceManager.getInstance(context.getApplicationContext()).setCredentialsInvalid(true);
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
