package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

/**
 * SP (Moodle / CampusSquare) のセッションの永続化。CLAUDE.md の「SP session」の規則:
 * 属性込みで持つ / セッション cookie も時間で切らずに持ち越す / 失効分は捨てる /
 * 復元先のホストに送られるものだけ戻す / 持ち主 (ユーザー名) が今のユーザーと違う・無いものは戻さない。
 */
public class SpSessionCookiesTest {

    private static final String ELMS = "elms.u-aizu.ac.jp";
    private static final String CSWEB = "csweb.u-aizu.ac.jp";
    private static final HttpUrl MOODLE = HttpUrl.parse("https://elms.u-aizu.ac.jp/my/");
    private static final HttpUrl CS = HttpUrl.parse("https://csweb.u-aizu.ac.jp/campusweb/campusportal.do");
    private static final HttpUrl IDP = HttpUrl.parse("https://slink.secioss.com/pub/tenantlogin.cgi");

    private static final long NOW = 1_800_000_000_000L;
    private static final String OWNER = "s1300001";
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static Cookie parse(HttpUrl url, String line) {
        Cookie c = Cookie.parse(url, line);
        if (c == null) throw new AssertionError("unparseable: " + line);
        return c;
    }

    private static Cookie named(List<Cookie> cookies, String name) {
        for (Cookie c : cookies) if (c.name().equals(name)) return c;
        return null;
    }

    @Test
    public void roundTrip_keepsDomainHostOnlyPathExpiryAndSecure() {
        long expires = NOW + 7 * DAY;
        List<Cookie> session = Arrays.asList(
                parse(CS, "JSESSIONID=J1; Path=/campusweb; Secure; HttpOnly"),
                new Cookie.Builder().name("AWSALB").value("A1").hostOnlyDomain(CSWEB).path("/").expiresAt(expires).build(),
                parse(CS, "SHARED=S1; Domain=u-aizu.ac.jp; Path=/"));

        List<Cookie> restored = SpSessionCookies.restore(SpSessionCookies.serialize(session, NOW, OWNER), OWNER, CSWEB, NOW);

        assertEquals(3, restored.size());
        Cookie jsession = named(restored, "JSESSIONID");
        assertTrue(jsession.hostOnly());
        assertEquals(CSWEB, jsession.domain());
        assertEquals("/campusweb", jsession.path());
        assertTrue(jsession.secure());
        assertTrue(jsession.httpOnly());
        assertFalse("session cookie stays a session cookie", jsession.persistent());

        Cookie alb = named(restored, "AWSALB");
        assertTrue(alb.persistent());
        assertEquals(expires, alb.expiresAt());

        Cookie shared = named(restored, "SHARED");
        assertFalse(shared.hostOnly());
        assertEquals("u-aizu.ac.jp", shared.domain());
    }

    @Test
    public void sessionCookies_arePersistedWithoutTheIdpOneHourLimit() {
        String stored = SpSessionCookies.serialize(
                Arrays.asList(parse(MOODLE, "MoodleSession=M1; Path=/; Secure")), NOW, OWNER);

        // 一晩どころか 30 日後でも捨てない。生死は probe が確かめる (SP では死んだ cookie の害は probe 1 回だけ)。
        List<Cookie> restored = SpSessionCookies.restore(stored, OWNER, ELMS, NOW + 30 * DAY);

        assertEquals(1, restored.size());
        assertEquals("M1", restored.get(0).value());
    }

    @Test
    public void expiredCookies_areDroppedOnRestore() {
        String stored = SpSessionCookies.serialize(Arrays.asList(
                parse(MOODLE, "MoodleSession=M1; Path=/"),
                new Cookie.Builder().name("AWSALB").value("A1").hostOnlyDomain(ELMS).path("/")
                        .expiresAt(NOW + 60_000).build()), NOW, OWNER);

        List<Cookie> later = SpSessionCookies.restore(stored, OWNER, ELMS, NOW + 2 * 60_000);

        assertEquals(1, later.size());
        assertEquals("MoodleSession", later.get(0).name());
    }

    @Test
    public void restore_dropsCookiesThatWouldNotBeSentToTheHost() {
        List<Cookie> mixed = new ArrayList<>();
        mixed.add(parse(CS, "JSESSIONID=J1; Path=/campusweb"));
        mixed.add(parse(IDP, "AWSALB=IDP; Path=/"));
        mixed.add(parse(MOODLE, "MoodleSession=M1; Path=/"));

        List<Cookie> restored = SpSessionCookies.restore(SpSessionCookies.serialize(mixed, NOW, OWNER), OWNER, ELMS, NOW);

        assertEquals(1, restored.size());
        assertEquals("MoodleSession", restored.get(0).name());
    }

    /**
     * 持ち主が違う・無いセッションは戻さない。設定でアカウントを切り替えている間に前のアカウントの自動の取得が
     * セッションを作り直しても、新しいアカウントはそれを使わない。持ち主を記録する前の保存分 (旧形式を含む) は
     * 誰のものか分からないので、よそのものとして扱う。
     */
    @Test
    public void sessionsOfAnotherOrUnknownOwner_areNotRestored() {
        String stored = SpSessionCookies.serialize(
                Arrays.asList(parse(MOODLE, "MoodleSession=M1; Path=/; Secure")), NOW, " " + OWNER + " ");

        assertEquals(OWNER, SpSessionCookies.ownerOf(stored));
        assertTrue(SpSessionCookies.ownedBy(stored, OWNER));
        assertEquals(1, SpSessionCookies.restore(stored, OWNER, ELMS, NOW).size());

        assertFalse(SpSessionCookies.ownedBy(stored, "s1300002"));
        assertTrue(SpSessionCookies.restore(stored, "s1300002", ELMS, NOW).isEmpty());
        assertFalse(SpSessionCookies.ownedBy(stored, ""));

        // 持ち主を記録する前の新形式と、旧形式。
        String ownerless = "{\"v\":1,\"cookies\":[{\"name\":\"MoodleSession\",\"value\":\"M1\",\"domain\":\"elms.u-aizu.ac.jp\","
                + "\"hostOnly\":true,\"path\":\"/\",\"secure\":true,\"httpOnly\":false}]}";
        assertNull(SpSessionCookies.ownerOf(ownerless));
        assertFalse(SpSessionCookies.ownedBy(ownerless, OWNER));
        assertTrue(SpSessionCookies.restore(ownerless, OWNER, ELMS, NOW).isEmpty());
        assertNull(SpSessionCookies.ownerOf("MoodleSession=M1; AWSALB=A1"));
        assertFalse(SpSessionCookies.ownedBy("MoodleSession=M1; AWSALB=A1", OWNER));
        assertTrue(SpSessionCookies.restore("MoodleSession=M1; AWSALB=A1", OWNER, ELMS, NOW).isEmpty());
    }

    @Test
    public void singleUseSamlStateAndEmptyValues_areNotPersisted() {
        String stored = SpSessionCookies.serialize(Arrays.asList(
                parse(CS, "_opensaml_req_ss:mem:abc=state; Path=/"),
                parse(CS, "_redirection_state=x; Path=/"),
                parse(CS, "EMPTY=; Path=/"),
                parse(CS, "JSESSIONID=J1; Path=/campusweb")), NOW, OWNER);

        List<Cookie> restored = SpSessionCookies.restore(stored, OWNER, CSWEB, NOW);

        List<String> names = new ArrayList<>();
        for (Cookie c : restored) names.add(c.name());
        assertFalse(names.contains("_opensaml_req_ss:mem:abc"));
        assertFalse(names.contains("_redirection_state"));
        assertFalse(names.contains("EMPTY"));
        assertTrue(names.contains("JSESSIONID"));
    }

    @Test
    public void nothingWorthKeeping_serializesToNull_andGarbageRestoresToEmpty() {
        assertNull(SpSessionCookies.serialize(new ArrayList<>(), NOW, OWNER));
        assertTrue(SpSessionCookies.restore("{not json", OWNER, ELMS, NOW).isEmpty());
        assertTrue(SpSessionCookies.restore("", OWNER, ELMS, NOW).isEmpty());
        assertTrue(SpSessionCookies.restore(null, OWNER, ELMS, NOW).isEmpty());
    }

    @Test
    public void headerFor_honorsPath() {
        List<Cookie> cookies = Arrays.asList(
                parse(CS, "JSESSIONID=J1; Path=/campusweb"),
                parse(CS, "OTHER=O1; Path=/elsewhere"));

        assertEquals("JSESSIONID=J1", SpSessionCookies.headerFor(cookies, CS, NOW));
    }

    @Test
    public void restoredIntoTheSharedJar_aServerReissueReplacesItInsteadOfDuplicating() {
        HostScopedCookieJar jar = new HostScopedCookieJar(() -> NOW);
        String stored = SpSessionCookies.serialize(
                Arrays.asList(parse(MOODLE, "MoodleSession=OLD; Path=/; Secure")), NOW, OWNER);
        jar.add(SpSessionCookies.restore(stored, OWNER, ELMS, NOW));

        jar.saveFromResponse(MOODLE, Arrays.asList(parse(MOODLE, "MoodleSession=NEW; Path=/; Secure")));

        List<Cookie> sent = jar.loadForRequest(MOODLE);
        assertEquals(1, sent.size());
        assertEquals("NEW", sent.get(0).value());
    }
}
