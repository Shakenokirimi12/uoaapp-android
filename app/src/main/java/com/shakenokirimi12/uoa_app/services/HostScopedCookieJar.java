package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.services.idp.SeciossIdPClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * NetworkClient.getCookieClient() の cookie jar。ブラウザと同じ規則で持つ:
 * - (name, domain, path) が同じものだけ置き換える。同名でもホストが違えば共存する
 * - 期限切れの Set-Cookie (Max-Age=0 など) は削除指示。保存せず、既存の同名 cookie も消す
 * - 送るのは Cookie.matches(url) (Domain / Path / Secure) を満たし、期限切れでないものだけ
 *
 * 以前は「ホスト名 → 名前ごとの Map」で持ち、送信時に Domain / Path / Secure / 期限を見ていなかった。
 * そのため削除指示の cookie を保存して送り返し、Secure cookie を http に送りうる状態だった
 * (CLAUDE.md の SAML / cookie の不変条件)。規則は SeciossIdPClient の jar と同じものを使う。
 * テストは HostScopedCookieJarTest。
 */
public final class HostScopedCookieJar implements CookieJar {

    /** テストで時刻を固定するため。 */
    public interface Clock { long now(); }

    private final List<Cookie> cookies = new ArrayList<>();
    private final Clock clock;

    public HostScopedCookieJar() { this(System::currentTimeMillis); }

    public HostScopedCookieJar(@NonNull Clock clock) { this.clock = clock; }

    @Override
    public synchronized void saveFromResponse(@NonNull HttpUrl url, @NonNull List<Cookie> received) {
        SeciossIdPClient.mergeCookies(cookies, received, clock.now());
    }

    @NonNull
    @Override
    public synchronized List<Cookie> loadForRequest(@NonNull HttpUrl url) {
        long now = clock.now();
        cookies.removeIf(c -> SeciossIdPClient.isExpired(c, now));
        List<Cookie> out = new ArrayList<>();
        for (Cookie c : cookies) {
            if (c.matches(url)) out.add(c);
        }
        // 同名でパスが違うものは、パスの長い (具体的な) 方を先に送る (RFC 6265 5.4)。安定ソート。
        out.sort((a, b) -> Integer.compare(b.path().length(), a.path().length()));
        return out;
    }

    /** 外から持ち込んだ cookie (IdP 経由のセッション、永続化からの復元) を同じ規則で入れる。 */
    public synchronized void add(@NonNull List<Cookie> incoming) {
        SeciossIdPClient.mergeCookies(cookies, incoming, clock.now());
    }

    /**
     * host に送られうる (ドメインが一致する) 期限内の cookie。パスは問わない。
     * アプリ内ブラウザへの受け渡しと、セッションの永続化に使う。
     */
    @NonNull
    public synchronized List<Cookie> forHost(@NonNull String host) {
        long now = clock.now();
        String h = host.toLowerCase(Locale.ROOT);
        List<Cookie> out = new ArrayList<>();
        for (Cookie c : cookies) {
            if (SeciossIdPClient.isExpired(c, now)) continue;
            if (domainMatches(c, h)) out.add(c);
        }
        return out;
    }

    public synchronized void clear() { cookies.clear(); }

    /** host 自身に出された cookie (host-only と Domain=host) を消す。親ドメイン宛ては他ホストとの共有なので残す。 */
    public synchronized void removeIssuedTo(@NonNull String host) {
        String h = host.toLowerCase(Locale.ROOT);
        cookies.removeIf(c -> c.domain().equals(h));
    }

    public static boolean domainMatches(@NonNull Cookie c, @NonNull String host) {
        String domain = c.domain();
        if (host.equals(domain)) return true;
        return !c.hostOnly() && host.endsWith("." + domain);
    }
}
