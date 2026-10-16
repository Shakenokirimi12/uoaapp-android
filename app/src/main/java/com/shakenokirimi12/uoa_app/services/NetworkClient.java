package com.shakenokirimi12.uoa_app.services;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.OkHttpClient;

public class NetworkClient {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private static OkHttpClient cookieClient;
    private static OkHttpClient noCookieClient;
    private static OkHttpClient noRedirectClient;

    /** ホスト / ドメイン / パス / 期限をブラウザと同じ規則で扱う jar。プロセス内メモリのみ。 */
    private static final HostScopedCookieJar cookieStore = new HostScopedCookieJar();

    public static String getUserAgent() {
        return USER_AGENT;
    }

    public static synchronized OkHttpClient getCookieClient() {
        if (cookieClient == null) {
            cookieClient = new OkHttpClient.Builder()
                    .cookieJar(cookieStore)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        return cookieClient;
    }

    public static synchronized OkHttpClient getNoCookieClient() {
        if (noCookieClient == null) {
            noCookieClient = new OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        return noCookieClient;
    }

    public static synchronized OkHttpClient getNoRedirectClient() {
        if (noRedirectClient == null) {
            noRedirectClient = new OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
        }
        return noRedirectClient;
    }

    /** アプリ内ブラウザへセッションを持ち込むために、ホストへ送られうる cookie を取り出す。 */
    public static List<Cookie> cookiesForHost(String host) {
        return cookieStore.forHost(host);
    }

    /**
     * SeciossIdPClient は cookie を自前で管理していて、共有の cookie jar を通らない。
     * IdP 経由で得た Moodle のセッション cookie (MoodleSession 等) をここへ入れないと、
     * 以降の getCookieClient() 経由のリクエストが未ログイン扱いになる (iOS の
     * MoodleService.injectCookiesIntoSharedStorage と同じ役割)。
     * 入れる cookie は host-only (そのホストにだけ送る)。1 件でも壊れた値があっても他は入れる。
     * 落とした件数は呼び出し元へ返す。
     */
    public static int injectCookieHeader(String host, boolean secure, String cookieHeader) {
        List<Cookie> parsed = new ArrayList<>();
        int dropped = 0;
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            try {
                Cookie.Builder b = new Cookie.Builder()
                        .name(trimmed.substring(0, eq))
                        .value(trimmed.substring(eq + 1))
                        .hostOnlyDomain(host)
                        .path("/");
                if (secure) b.secure();
                parsed.add(b.build());
            } catch (IllegalArgumentException e) {
                dropped++;
            }
        }
        cookieStore.add(parsed);
        return dropped;
    }

    /** ホストへ送られうる cookie を "a=b; c=d" 形式で取り出す (永続化用)。無ければ空文字。 */
    public static String exportCookieHeader(String host) {
        StringBuilder sb = new StringBuilder();
        for (Cookie c : cookieStore.forHost(host)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name()).append('=').append(c.value());
        }
        return sb.toString();
    }

    public static boolean hasCookies(String host) {
        return !cookieStore.forHost(host).isEmpty();
    }

    public static void clearCookies() {
        cookieStore.clear();
    }
}
