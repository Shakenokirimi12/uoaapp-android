package com.shakenokirimi12.uoa_app.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class NetworkClient {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private static OkHttpClient cookieClient;
    private static OkHttpClient noCookieClient;
    private static OkHttpClient noRedirectClient;

    private static final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();

    public static String getUserAgent() {
        return USER_AGENT;
    }

    public static synchronized OkHttpClient getCookieClient() {
        if (cookieClient == null) {
            cookieClient = new OkHttpClient.Builder()
                    .cookieJar(new CookieJar() {
                        @Override
                        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                            List<Cookie> existing = cookieStore.get(url.host());
                            Map<String, Cookie> merged = new HashMap<>();
                            if (existing != null) {
                                for (Cookie c : existing) merged.put(c.name(), c);
                            }
                            for (Cookie c : cookies) merged.put(c.name(), c);
                            cookieStore.put(url.host(), new ArrayList<>(merged.values()));
                        }

                        @Override
                        public List<Cookie> loadForRequest(HttpUrl url) {
                            List<Cookie> cookies = cookieStore.get(url.host());
                            return cookies != null ? new ArrayList<>(cookies) : new ArrayList<>();
                        }
                    })
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

    /** アプリ内ブラウザへセッションを持ち込むために、ホスト単位でcookieを取り出す。 */
    public static synchronized List<Cookie> cookiesForHost(String host) {
        List<Cookie> cookies = cookieStore.get(host);
        return cookies != null ? new ArrayList<>(cookies) : new ArrayList<>();
    }

    /**
     * SeciossIdPClient は cookie を自前で管理していて、共有の cookie jar を通らない。
     * IdP 経由で得た Moodle のセッション cookie (MoodleSession 等) をここへ入れないと、
     * 以降の getCookieClient() 経由のリクエストが未ログイン扱いになる (iOS の
     * MoodleService.injectCookiesIntoSharedStorage と同じ役割)。
     * 1 件でも壊れた値があっても他は入れる。落とした件数は呼び出し元へ返す。
     */
    public static synchronized int injectCookieHeader(String host, boolean secure, String cookieHeader) {
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
                        .domain(host)
                        .path("/");
                if (secure) b.secure();
                parsed.add(b.build());
            } catch (IllegalArgumentException e) {
                dropped++;
            }
        }
        List<Cookie> existing = cookieStore.get(host);
        Map<String, Cookie> merged = new HashMap<>();
        if (existing != null) {
            for (Cookie c : existing) merged.put(c.name(), c);
        }
        for (Cookie c : parsed) merged.put(c.name(), c);
        cookieStore.put(host, new ArrayList<>(merged.values()));
        return dropped;
    }

    /** ホストの cookie を "a=b; c=d" 形式で取り出す (永続化用)。無ければ空文字。 */
    public static synchronized String exportCookieHeader(String host) {
        List<Cookie> cookies = cookieStore.get(host);
        if (cookies == null || cookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Cookie c : cookies) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name()).append('=').append(c.value());
        }
        return sb.toString();
    }

    public static synchronized boolean hasCookies(String host) {
        List<Cookie> cookies = cookieStore.get(host);
        return cookies != null && !cookies.isEmpty();
    }

    public static void clearCookies() {
        cookieStore.clear();
    }
}
