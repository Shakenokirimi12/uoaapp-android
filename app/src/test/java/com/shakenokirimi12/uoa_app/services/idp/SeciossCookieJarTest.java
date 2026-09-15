package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.Headers;
import okhttp3.HttpUrl;

/**
 * 「ホストをまたぐセッション状態 (cookie) の取り違え」バグの再発防止テスト。
 *
 * かつて cookie を名前だけの Map<String,String> で持ち回っていたため、IdP(slink.secioss.com) と
 * SP(csweb / elms) が同名の cookie (AWS ALB の AWSALB 等) を発行すると互いに上書きし合い、両方の ALB
 * スティッキーが壊れて SAML が延々リダイレクトし、端末側で -1007 (リダイレクト過多)・学務データが
 * 「何も出ない」状態になっていた (2026-09-15、実機のセルラー回線で発生)。
 *
 * cookie はホスト (ドメイン) 単位で保持し、そのホストにだけ送る、という不変条件をここで固定する。
 * SeciossIdPClient.mergeCookies / cookieHeaderFor / seedSecioss を直接叩くので HTTP は不要。
 */
public class SeciossCookieJarTest {

    private static final HttpUrl IDP = HttpUrl.parse("https://slink.secioss.com/pub/tenantlogin.cgi");
    private static final HttpUrl SP = HttpUrl.parse("https://csweb.u-aizu.ac.jp/campusweb/campusportal.do");

    /** 指定 URL に対して受信した Set-Cookie 群をパースする (host-only / domain 属性を実物どおり反映)。 */
    private static List<Cookie> received(HttpUrl url, String... setCookieLines) {
        Headers.Builder hb = new Headers.Builder();
        for (String line : setCookieLines) hb.add("Set-Cookie", line);
        return Cookie.parseAll(url, hb.build());
    }

    @Test
    public void sameNameFromDifferentHosts_bothRetained_sentToOwnHostOnly() {
        // これがまさに以前の衝突ケース。IdP と SP が同名 AWSALB を別値で返す。
        List<Cookie> jar = new ArrayList<>();
        SeciossIdPClient.mergeCookies(jar, received(IDP, "AWSALB=IDPVALUE; Path=/"));
        SeciossIdPClient.mergeCookies(jar, received(SP, "AWSALB=SPVALUE; Path=/"));

        // 名前が同じでもホストが違えば共存する (片方が消えてはならない)。
        assertEquals(2, jar.size());

        // 各ホストには自分の AWSALB だけが送られる。相手の値が混ざってはならない。
        assertEquals("AWSALB=IDPVALUE", SeciossIdPClient.cookieHeaderFor(jar, IDP));
        assertEquals("AWSALB=SPVALUE", SeciossIdPClient.cookieHeaderFor(jar, SP));
    }

    @Test
    public void hostOnlyCookie_notSentToAnotherHost() {
        // Domain 属性の無い cookie は発行元ホストにしか送らない (host-only)。
        List<Cookie> jar = new ArrayList<>();
        SeciossIdPClient.mergeCookies(jar, received(IDP, "sessid=abc123; Path=/"));

        assertEquals("sessid=abc123", SeciossIdPClient.cookieHeaderFor(jar, IDP));
        assertTrue(SeciossIdPClient.cookieHeaderFor(jar, SP).isEmpty());
        // secioss の別サブドメインにも、host-only なので送られない。
        assertTrue(SeciossIdPClient.cookieHeaderFor(jar,
                HttpUrl.parse("https://other.secioss.com/x")).isEmpty());
    }

    @Test
    public void sameNameSameHost_replacedNotDuplicated() {
        // 同じ (name, domain, path) は最新で置き換わり、ヘッダに二重に載らない。
        List<Cookie> jar = new ArrayList<>();
        SeciossIdPClient.mergeCookies(jar, received(IDP, "AWSALB=OLD; Path=/"));
        SeciossIdPClient.mergeCookies(jar, received(IDP, "AWSALB=NEW; Path=/"));

        assertEquals(1, jar.size());
        assertEquals("AWSALB=NEW", SeciossIdPClient.cookieHeaderFor(jar, IDP));
    }

    @Test
    public void seededSsoCookies_reachIdpHost_butNotSp() {
        // SSO ストアの平文 cookie を仕立て直したものが、IdP(slink.secioss.com) へは送られ、SP へは送られない。
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("AWSALB", "STORED");
        stored.put("SSOSESSIONID", "sso-xyz");
        List<Cookie> seeded = SeciossIdPClient.seedSecioss(stored);

        String toIdp = SeciossIdPClient.cookieHeaderFor(seeded, IDP);
        assertTrue(toIdp.contains("AWSALB=STORED"));
        assertTrue(toIdp.contains("SSOSESSIONID=sso-xyz"));
        assertTrue(SeciossIdPClient.cookieHeaderFor(seeded, SP).isEmpty());
    }

    @Test
    public void seedCookie_replacedByFreshServerCookie_notDuplicated() {
        // 再ログイン(useStoredSso)の要。seed した AWSALB は、slink がその後に返す新しい AWSALB で
        // 置き換わらねばならない。seed を ".secioss.com" ドメインで作ると発行元(host-only の
        // "slink.secioss.com")と domain 表現が食い違い、mergeCookies が古い値を消さず、
        // "AWSALB=STORED; AWSALB=FRESH" と二重に送って ALB スティッキーをまた壊す。
        List<Cookie> jar = new ArrayList<>(SeciossIdPClient.seedSecioss(single("AWSALB", "STORED")));
        // AWS ALB は Domain 属性なし = host-only で AWSALB を発行する。
        SeciossIdPClient.mergeCookies(jar, received(IDP, "AWSALB=FRESH; Path=/"));

        assertEquals(1, jar.size());
        assertEquals("AWSALB=FRESH", SeciossIdPClient.cookieHeaderFor(jar, IDP));
    }

    @Test
    public void spCookie_doesNotLeakBackIntoIdpSeedOrRequests() {
        // SP が同名 AWSALB を出しても、IdP へ送る cookie は SSO seed 由来の値のまま。
        List<Cookie> jar = new ArrayList<>(SeciossIdPClient.seedSecioss(single("AWSALB", "IDPSEED")));
        SeciossIdPClient.mergeCookies(jar, received(SP, "AWSALB=SPVALUE; Path=/"));

        assertEquals("AWSALB=IDPSEED", SeciossIdPClient.cookieHeaderFor(jar, IDP));
        assertEquals("AWSALB=SPVALUE", SeciossIdPClient.cookieHeaderFor(jar, SP));
        assertFalse(SeciossIdPClient.cookieHeaderFor(jar, IDP).contains("SPVALUE"));
    }

    private static Map<String, String> single(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
