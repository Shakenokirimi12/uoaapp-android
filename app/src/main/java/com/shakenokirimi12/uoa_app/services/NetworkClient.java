package com.shakenokirimi12.uoa_app.services;

import java.util.List;

import okhttp3.Cookie;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;

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
                    .dns(TimedDns.INSTANCE)
                    .cookieJar(cookieStore)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        return cookieClient;
    }

    /**
     * cookie jar を持たないクライアント。CampusSquare は Cookie ヘッダを手で付けてこれで叩く。
     * OkHttp はリダイレクト先が別ホストでも手で付けた Cookie ヘッダを外さない (外すのは Authorization
     * だけ)。csweb のセッションが切れていると IdP へ 302 されるので、そのまま追うと CampusSquare の
     * cookie が slink.secioss.com へ送られる。別ホストへのホップでは Cookie ヘッダを落とす。
     */
    public static synchronized OkHttpClient getNoCookieClient() {
        if (noCookieClient == null) {
            noCookieClient = new OkHttpClient.Builder()
                    .dns(TimedDns.INSTANCE)
                    .addNetworkInterceptor(DROP_COOKIE_HEADER_ACROSS_HOSTS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        return noCookieClient;
    }

    /** 元のリクエストとホストが違うホップ (= リダイレクト先) では、手で付けた Cookie ヘッダを送らない。 */
    static final Interceptor DROP_COOKIE_HEADER_ACROSS_HOSTS = chain -> {
        Request request = chain.request();
        String originalHost = chain.call().request().url().host();
        if (request.header("Cookie") != null && !request.url().host().equalsIgnoreCase(originalHost)) {
            request = request.newBuilder().removeHeader("Cookie").build();
        }
        return chain.proceed(request);
    };

    /** セッション確認で同じホスト内のリダイレクトを追う上限。ループしても probe が止まるように。 */
    static final int MAX_SAME_HOST_REDIRECTS = 5;

    /**
     * セッション確認用 (iOS の AuthenticatedHTTP sameHostRedirectsOnly と同じ)。3xx は、行き先が元と同じ
     * ホストで、https から http へ落ちないときだけ GET で追う。別ホストへの 3xx・上限超え・Location の
     * 無い 3xx はその 3xx の応答を返す (呼び出し側は「未ログイン」と判定する)。
     *
     * 別ホストへ出た時点で未ログインは確定し、追うと IdP まで歩いて古い cookie でループする。一方で
     * ログイン済みの SP も同じホスト内で 302 を返すことがある (CampusSquare のフロー URL は
     * _flowExecutionKey 付きの URL へ 302 する) ので、3xx を全部「未ログイン」にはできない。
     *
     * noRedirectClient はリダイレクトを追わないクライアント。手で付けた Cookie ヘッダは同じホストにしか
     * 送られない。返した Response は呼び出し側で close すること。
     */
    public static okhttp3.Response executeFollowingSameHostRedirects(OkHttpClient noRedirectClient, Request request)
            throws java.io.IOException {
        String host = request.url().host();
        Request current = request;
        okhttp3.Response resp = noRedirectClient.newCall(current).execute();
        for (int hop = 0; hop < MAX_SAME_HOST_REDIRECTS && resp.isRedirect(); hop++) {
            String location = resp.header("Location");
            okhttp3.HttpUrl next = location != null ? current.url().resolve(location) : null;
            boolean sameHost = next != null && next.host().equalsIgnoreCase(host);
            boolean downgrade = next != null && current.url().isHttps() && !next.isHttps();
            if (!sameHost || downgrade) return resp;
            resp.close();
            current = current.newBuilder().url(next).get().build();
            resp = noRedirectClient.newCall(current).execute();
        }
        return resp;
    }

    public static synchronized OkHttpClient getNoRedirectClient() {
        if (noRedirectClient == null) {
            noRedirectClient = new OkHttpClient.Builder()
                    .dns(TimedDns.INSTANCE)
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
     * MoodleService.injectCookiesIntoSharedStorage と同じ役割)。永続化からの復元にも使う。
     * 属性 (Domain / host-only / Path / 期限 / Secure) は受け取ったまま保つ。
     */
    public static void addCookies(List<Cookie> cookies) {
        cookieStore.add(cookies);
    }

    /**
     * host 自身に出された cookie (host-only と Domain=host) を捨てる。親ドメイン宛ては残す。
     * 復元したセッションが死んでいたとき、ログインし直す前に呼ぶ。復元分と新しく発行される分の
     * Path や Domain が違うと (name, domain, path) が一致せず置き換わらないので、同名を二重に送ることになる。
     */
    public static void removeCookiesIssuedTo(String host) {
        cookieStore.removeIssuedTo(host);
    }

    public static boolean hasCookies(String host) {
        return !cookieStore.forHost(host).isEmpty();
    }

    public static void clearCookies() {
        cookieStore.clear();
    }
}
