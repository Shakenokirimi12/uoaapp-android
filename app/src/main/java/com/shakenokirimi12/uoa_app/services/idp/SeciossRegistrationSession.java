package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;

/**
 * OTP 登録の多段フロー (方式選択 -> 確認 -> 送信/QR 取得 -> コード確認 -> 登録完了) を、呼び出し側 (UI) が
 * 各ステップの間でユーザー操作を挟めるように、明示的なセッションオブジェクトとして持たせる。
 * iOS の SeciossRegistrationSession の移植。
 */
public final class SeciossRegistrationSession {
    public static final String ENTRY_URL = "https://slink.secioss.com/user/?lang=ja,tenant=u-aizu.ac.jp";
    private static final String QR_PAGE_URL = "https://slink.secioss.com/user/index.php?app=qrsecret&st=ga";
    private static final String QR_CHECK_URL = "https://slink.secioss.com/user/checkotp.php?app=qrsecret&st=ga";
    private static final String MAIL_PAGE_URL = "https://slink.secioss.com/user/index.php?app=motpmail&tenant=u-aizu.ac.jp";
    private static final String MAIL_CHECK_URL = "https://slink.secioss.com/user/checkotp.php?app=motpmail&tenant=u-aizu.ac.jp";
    private static final String COMPLETION_MARKER = "設定が完了しました";

    private final SeciossIdPClient client;
    private Map<String, String> cookies;

    SeciossRegistrationSession(@NonNull SeciossIdPClient client, @NonNull Map<String, String> cookies) {
        this.client = client;
        this.cookies = cookies;
    }

    /**
     * 「ワンタイムパスワードは既に設定済みです: <方式>」の表示から、フレッシュな新規登録なのか 2 つ目の
     * 方式の追加登録なのかを取り出す (2026-09-07、実機 HAR から検証済みの正規表現)。
     */
    @Nullable
    static String existingStatusLabel(@NonNull String html) {
        String s = SeciossIdPClient.firstGroup("既に設定済みです[:：]&nbsp;\\s*([^<]+)", html);
        return s != null ? s.trim() : null;
    }

    // ---- Authenticator アプリ方式 ----

    public static final class AuthenticatorChallenge {
        /**
         * 外部の認証アプリ (Google/Microsoft Authenticator、1Password 等) へ Intent で渡すための URL。
         * このアプリ自身はシークレットを保持・計算しない (このアプリだけが 2FA を持つ状態を避けるため)。
         */
        @NonNull public final String otpauthUrl;
        @Nullable public final String existingStatusLabel;
        final String csrf;

        AuthenticatorChallenge(@NonNull String otpauthUrl, @Nullable String existingStatusLabel, @NonNull String csrf) {
            this.otpauthUrl = otpauthUrl;
            this.existingStatusLabel = existingStatusLabel;
            this.csrf = csrf;
        }
    }

    /**
     * QR ページ (実際には QR 画像ではなく JS 内に平文の otpauth URL が埋め込まれている) を取得し、確認画面まで
     * 進めて csrf トークンを得る。取得したシークレットはページ再読み込みで無効になるため、呼び出し側は
     * 速やかに外部アプリへ渡すこと。
     */
    @NonNull
    public AuthenticatorChallenge beginAuthenticatorRegistration() throws Exception {
        SeciossIdPClient.HttpResult qr = client.get(QR_PAGE_URL, cookies, Collections.singletonMap("Referer", ENTRY_URL));
        cookies = qr.cookies;
        String otpauth = qr.status == 200 ? SeciossIdPClient.firstGroup("var str = \"(otpauth://[^\"]+)\"", qr.html) : null;
        if (otpauth == null) {
            throw new SeciossError(SeciossError.Kind.MISSING_FIELD, "otpauth URL on qrsecret page");
        }
        otpauth = otpauth.replace("&amp;", "&");

        SeciossIdPClient.HttpResult step2 = client.postForm(QR_CHECK_URL, "use_menu=1", cookies, QR_PAGE_URL);
        cookies = step2.cookies;
        String csrf = step2.status == 200 ? SeciossIdPClient.extractField("csrf", step2.html) : null;
        if (csrf == null) {
            throw new SeciossError(SeciossError.Kind.MISSING_FIELD, "csrf on qrsecret confirmation page");
        }
        return new AuthenticatorChallenge(otpauth, existingStatusLabel(step2.html), csrf);
    }

    /** 外部の認証アプリで表示されたコードを、ユーザーがこのアプリに入力し直したものを渡す。 */
    public void completeAuthenticatorRegistration(@NonNull AuthenticatorChallenge challenge, @NonNull String code) throws Exception {
        String body = "password=" + code + "&use_menu=1&op=login&csrf=" + challenge.csrf;
        SeciossIdPClient.HttpResult result = client.postForm(QR_CHECK_URL, body, cookies, QR_CHECK_URL);
        cookies = result.cookies;
        if (result.status != 200 || !result.html.contains(COMPLETION_MARKER)) {
            throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE, "Authenticator registration did not confirm completion");
        }
    }

    // ---- メール方式 ----

    public static final class EmailChallenge {
        @Nullable public final String existingStatusLabel;
        final String csrf;

        EmailChallenge(@Nullable String existingStatusLabel, @NonNull String csrf) {
            this.existingStatusLabel = existingStatusLabel;
            this.csrf = csrf;
        }
    }

    /** 確認コードの送信先メールアドレスを指定し、送信を依頼する。コード自体はここでは待たない。 */
    public void requestEmailCode(@NonNull String notifyEmail) throws Exception {
        SeciossIdPClient.HttpResult page = client.get(MAIL_PAGE_URL, cookies, Collections.singletonMap("Referer", ENTRY_URL));
        cookies = page.cookies;
        String csrf = page.status == 200 ? SeciossIdPClient.extractField("csrf", page.html) : null;
        if (csrf == null) {
            throw new SeciossError(SeciossError.Kind.MISSING_FIELD, "csrf on motpmail page");
        }
        SeciossIdPClient.HttpResult send = client.postForm(MAIL_PAGE_URL,
                "motpmail=" + notifyEmail + "&csrf=" + csrf + "&use_menu=1", cookies, MAIL_PAGE_URL);
        cookies = send.cookies;
        if (send.status != 200) {
            throw new SeciossError(SeciossError.Kind.UNEXPECTED_STATUS, send.status, "motpmail send");
        }
    }

    /** requestEmailCode の後に呼ぶ。確認コード入力画面の csrf (送信依頼のものとは別発行) と既存 OTP 状態を取得する。 */
    @NonNull
    public EmailChallenge fetchEmailConfirmationChallenge() throws Exception {
        SeciossIdPClient.HttpResult page = client.get(MAIL_CHECK_URL, cookies, Collections.singletonMap("Referer", MAIL_PAGE_URL));
        cookies = page.cookies;
        String csrf = page.status == 200 ? SeciossIdPClient.extractField("csrf", page.html) : null;
        if (csrf == null) {
            throw new SeciossError(SeciossError.Kind.MISSING_FIELD, "csrf on motpmail confirmation page");
        }
        return new EmailChallenge(existingStatusLabel(page.html), csrf);
    }

    public void completeEmailRegistration(@NonNull EmailChallenge challenge, @NonNull String code) throws Exception {
        String body = "password=" + code + "&use_menu=1&op=login&csrf=" + challenge.csrf;
        SeciossIdPClient.HttpResult result = client.postForm(MAIL_CHECK_URL, body, cookies, MAIL_CHECK_URL);
        cookies = result.cookies;
        if (result.status != 200 || !result.html.contains(COMPLETION_MARKER)) {
            throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE, "email registration did not confirm completion");
        }
    }
}
