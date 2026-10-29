package com.shakenokirimi12.uoa_app.services.idp;

/**
 * 1 回のログインで、IdP にパスワードを送って通ったか (SessionCoordinator がログインを走らせるスレッドに置く)。
 * SSO だけで通ったログインはパスワードを確かめていないので、「最後に成功したパスワード」として記録しない
 * (BackgroundLoginPolicy.succeeded)。値は真偽だけで、資格情報は持たない。
 */
public final class LoginEvidence {
    private static final ThreadLocal<boolean[]> PASSWORD_ACCEPTED = new ThreadLocal<>();

    private LoginEvidence() {}

    /** このスレッドでログインを始める。 */
    public static void begin() {
        PASSWORD_ACCEPTED.set(new boolean[1]);
    }

    /** IdP がパスワードを受け付けた (SeciossIdPClient)。begin していないスレッドでは何もしない。 */
    public static void notePasswordAccepted() {
        boolean[] cell = PASSWORD_ACCEPTED.get();
        if (cell != null) cell[0] = true;
    }

    /** ログインを終える。パスワードを送って通っていれば true。 */
    public static boolean end() {
        boolean[] cell = PASSWORD_ACCEPTED.get();
        PASSWORD_ACCEPTED.remove();
        return cell != null && cell[0];
    }
}
