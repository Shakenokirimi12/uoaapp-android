package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

/**
 * 共有 cookie jar (NetworkClient.getCookieClient) の不変条件。SeciossCookieJarTest と同じ観点:
 * 同名・別ホストは共存し自分のホストにだけ送る / 削除指示 (期限切れ) は保存も送信もしない /
 * Path と Secure を守る。
 */
public class HostScopedCookieJarTest {

    private static final HttpUrl MOODLE = HttpUrl.parse("https://elms.u-aizu.ac.jp/my/");
    private static final HttpUrl CS = HttpUrl.parse("https://csweb.u-aizu.ac.jp/campusweb/campusportal.do");
    private static final HttpUrl IDP = HttpUrl.parse("https://slink.secioss.com/pub/tenantlogin.cgi");

    private long now = 1_800_000_000_000L;
    private final HostScopedCookieJar jar = new HostScopedCookieJar(() -> now);

    /** Expires は絶対時刻で書くので、parse 時の現在時刻に依存しない。 */
    private static List<Cookie> received(HttpUrl url, String... lines) {
        List<Cookie> out = new ArrayList<>();
        for (String line : lines) {
            Cookie c = Cookie.parse(url, line);
            if (c != null) out.add(c);
        }
        return out;
    }

    private static String header(List<Cookie> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Cookie c : cookies) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name()).append('=').append(c.value());
        }
        return sb.toString();
    }

    @Test
    public void sameNameFromDifferentHosts_coexist_andGoOnlyToTheirHost() {
        jar.saveFromResponse(MOODLE, received(MOODLE, "AWSALB=MOODLE; Path=/"));
        jar.saveFromResponse(CS, received(CS, "AWSALB=CS; Path=/"));
        jar.saveFromResponse(IDP, received(IDP, "AWSALB=IDP; Path=/"));

        assertEquals("AWSALB=MOODLE", header(jar.loadForRequest(MOODLE)));
        assertEquals("AWSALB=CS", header(jar.loadForRequest(CS)));
        assertEquals("AWSALB=IDP", header(jar.loadForRequest(IDP)));
    }

    @Test
    public void deletionCookie_removesExisting_andIsNeverSent() {
        jar.saveFromResponse(MOODLE, received(MOODLE, "MoodleSession=abc; Path=/"));
        assertEquals("MoodleSession=abc", header(jar.loadForRequest(MOODLE)));

        // サーバの削除指示。以前の jar はこれを保存して送り返していた。
        jar.saveFromResponse(MOODLE, received(MOODLE,
                "MoodleSession=deleted; Path=/; Expires=Thu, 01 Jan 1970 00:00:01 GMT"));

        assertEquals("", header(jar.loadForRequest(MOODLE)));
        assertTrue(jar.forHost("elms.u-aizu.ac.jp").isEmpty());
    }

    @Test
    public void cookieThatExpiresLater_isDroppedOnceExpired() {
        long expires = now + 60_000;
        Cookie c = new Cookie.Builder().name("t").value("1").hostOnlyDomain("elms.u-aizu.ac.jp")
                .path("/").expiresAt(expires).build();
        List<Cookie> list = new ArrayList<>();
        list.add(c);
        jar.add(list);
        assertEquals("t=1", header(jar.loadForRequest(MOODLE)));

        now = expires + 1;
        assertEquals("", header(jar.loadForRequest(MOODLE)));
    }

    @Test
    public void pathAndSecure_areHonored() {
        jar.saveFromResponse(CS, received(CS,
                "scoped=1; Path=/campusweb",
                "secureOnly=1; Path=/; Secure"));

        HttpUrl otherPath = HttpUrl.parse("https://csweb.u-aizu.ac.jp/other");
        HttpUrl plainHttp = HttpUrl.parse("http://csweb.u-aizu.ac.jp/campusweb/x");

        assertEquals("scoped=1; secureOnly=1", header(jar.loadForRequest(CS)));
        assertEquals("secureOnly=1", header(jar.loadForRequest(otherPath)));
        assertEquals("scoped=1", header(jar.loadForRequest(plainHttp)));
    }

    @Test
    public void hostOnlyCookie_isNotSentToSubdomainOrSibling_butDomainCookieIs() {
        jar.saveFromResponse(CS, received(CS, "hostonly=1; Path=/", "shared=1; Path=/; Domain=u-aizu.ac.jp"));

        assertEquals("shared=1", header(jar.loadForRequest(MOODLE)));
        assertEquals("hostonly=1; shared=1", header(jar.loadForRequest(CS)));
        assertEquals("", header(jar.loadForRequest(IDP)));
    }

    @Test
    public void injectedSessionCookie_isReplacedByServerSetCookieOfSameName() {
        // IdP 経由で持ち込んだ (host-only) MoodleSession は、サーバが同名で出し直したら置き換わる。
        Cookie injected = new Cookie.Builder().name("MoodleSession").value("old")
                .hostOnlyDomain("elms.u-aizu.ac.jp").path("/").secure().build();
        List<Cookie> list = new ArrayList<>();
        list.add(injected);
        jar.add(list);
        jar.saveFromResponse(MOODLE, received(MOODLE, "MoodleSession=new; Path=/; Secure"));

        assertEquals("MoodleSession=new", header(jar.loadForRequest(MOODLE)));
    }
}
