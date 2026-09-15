package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IdP (slink.secioss.com) 自身の SSO セッション cookie を、アプリの再起動をまたいで保持する。
 * iOS の SeciossSSOStore の移植。
 *
 * これが生きていれば、入口 URL を叩いた時点で IdP がログインフォームではなく SAMLResponse の
 * 自動 submit を返すため、パスワードも OTP も要求されずに SP へ戻れる。持たずに毎回パスワードから
 * やり直すと、同期のたびに大学のメールサーバーへ OTP メールが飛ぶ。
 *
 * secioss.com が発行した cookie だけを入れる。SP (csweb/elms) の cookie を混ぜると、次回 IdP へ
 * 無関係なセッションを送ることになる。
 *
 * 値はメモリに持たず毎回 PreferenceManager から読む。アカウント切り替え (setUsername) が
 * 保存先を直接消すので、メモリに写しを持つと消したはずの cookie を送り続けることになる。
 */
public final class SeciossSsoStore {
    /** 参照時に初めて作る。static 初期化で PreferenceManager に触ると純粋な parse まで Android 依存になる。 */
    private static final class Holder {
        static final SeciossSsoStore INSTANCE = new SeciossSsoStore();
    }

    public static SeciossSsoStore getInstance() { return Holder.INSTANCE; }

    private final Object lock = new Object();

    private SeciossSsoStore() {}

    @NonNull
    public Map<String, String> current() {
        synchronized (lock) {
            return parse(PreferenceManager.getInstance().getIdpSsoCookieHeader());
        }
    }

    public void merge(@NonNull Map<String, String> newCookies) {
        if (newCookies.isEmpty()) return;
        synchronized (lock) {
            PreferenceManager prefs = PreferenceManager.getInstance();
            Map<String, String> cookies = parse(prefs.getIdpSsoCookieHeader());
            cookies.putAll(newCookies);
            prefs.setIdpSsoCookieHeader(SeciossIdPClient.cookieHeader(cookies));
        }
    }

    /** ログアウト時・資格情報を入れ直したとき。 */
    public void clear() {
        synchronized (lock) {
            PreferenceManager.getInstance().setIdpSsoCookieHeader(null);
        }
    }

    static Map<String, String> parse(String header) {
        Map<String, String> result = new LinkedHashMap<>();
        if (header == null) return result;
        for (String pair : header.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            result.put(trimmed.substring(0, eq), trimmed.substring(eq + 1));
        }
        return result;
    }
}
