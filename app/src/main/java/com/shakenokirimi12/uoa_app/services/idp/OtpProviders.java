package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.AuthorizationContext;

/**
 * 設定と文脈から OtpProvider の実体を決める。
 * 「どの方式を使うか」の判断はここだけが持ち、認証チェーンにも UI にも持たせない。
 */
public final class OtpProviders {

    private OtpProviders() {}

    @NonNull
    public static OtpProvider forLogin(@NonNull String uid, @NonNull String pass,
                                       @NonNull AuthorizationContext context) {
        OtpMethod method = OtpMethod.from(PreferenceManager.getInstance().getOtpMethod());
        switch (method) {
            case AUTHENTICATOR:
                return new AuthenticatorOtpProvider(context);
            case MAIL:
            default:
                return new MailOtpProvider(uid, pass, context);
        }
    }

    /**
     * メールで届いたコードを IMAP から取得し、取れなければ入力を求める。
     * 取得と入力の出し分けは OtpPromptCoordinator が持つ。
     */
    private static final class MailOtpProvider implements OtpProvider {
        private final String uid;
        private final String pass;
        private final AuthorizationContext context;

        MailOtpProvider(String uid, String pass, AuthorizationContext context) {
            this.uid = uid;
            this.pass = pass;
            this.context = context;
        }

        @Override public boolean requiresMailDispatch() { return true; }

        @NonNull
        @Override public String code() throws Exception {
            return OtpPromptCoordinator.getInstance().code(uid, pass, context);
        }
    }

    /**
     * 認証アプリが表示する TOTP を手入力してもらう。
     *
     * アプリがシークレットを保持しないため自動取得はできない。バックグラウンド更新では必ず
     * INTERACTIVE_LOGIN_REQUIRED になる。方式としてこれを選べる代わりに、セッションの永続化が
     * 効いていることが前提になる。
     */
    private static final class AuthenticatorOtpProvider implements OtpProvider {
        private final AuthorizationContext context;

        AuthenticatorOtpProvider(AuthorizationContext context) { this.context = context; }

        @Override public boolean requiresMailDispatch() { return false; }

        @NonNull
        @Override public String code() throws Exception {
            return OtpPromptCoordinator.getInstance().codeFromAuthenticator(context);
        }
    }
}
