package com.shakenokirimi12.uoa_app.services.idp;

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

/** 永続化形式と持ち越し規則だけを見る (保存先は PreferenceManager 側で Android 依存)。 */
public class SeciossSsoStoreTest {
    private static final long NOW = 1_800_000_000_000L;

    private static SeciossSsoStore.StoredCookie c(String name, String value, long expiresAt) {
        return new SeciossSsoStore.StoredCookie(name, value, "/", expiresAt, NOW);
    }

    /**
     * ブラウザと同じ規則で持ち越す: セッション cookie (期限なし) と失効した cookie はプロセスを跨がない。
     * 旧版はこれを無期限に再生し、IdP 側で失効した samsessid/auth_tkt を送り続けて IdP 内で
     * 307→302→302 が延々ピンポンし too many redirects になっていた (2026-09-16 iOS 実機)。
     */
    @Test
    public void load_keepsOnlyUnexpiredPersistentCookies() {
        List<SeciossSsoStore.StoredCookie> stored = Arrays.asList(
                c("AWSALB", "live", NOW + 3600_000),
                c("AWSALBCORS", "stale", NOW - 1),
                c("SimpleSAMLSessionID", "sess", -1),
                c("_opensaml_req_ss:mem:aaaa", "state", NOW + 3600_000),
                c("auth_tkt", "", NOW + 3600_000));

        // セッション cookie は永続化されない (メモリ上には残る)。
        String serialized = SeciossSsoStore.serializePersistent(stored);
        assertEquals("AWSALB\tlive\t/\t" + (NOW + 3600_000) + "\t"
                + "\nAWSALBCORS\tstale\t/\t" + (NOW - 1) + "\t"
                + "\n_opensaml_req_ss:mem:aaaa\tstate\t/\t" + (NOW + 3600_000) + "\t"
                + "\nauth_tkt\t\t/\t" + (NOW + 3600_000) + "\t", serialized);

        List<SeciossSsoStore.StoredCookie> loaded = SeciossSsoStore.load(serialized, NOW);
        assertEquals(1, loaded.size());
        assertEquals("AWSALB", loaded.get(0).name);
        assertEquals("live", loaded.get(0).value);
    }

    @Test
    public void load_rejectsLegacyNameValueFormat() {
        // 旧形式 (名前=値のみ) は期限を判定できない → 読まない = 汚染端末の自己修復。
        assertTrue(SeciossSsoStore.load("samsessid=old; auth_tkt=old", NOW).isEmpty());
        assertTrue(SeciossSsoStore.load("", NOW).isEmpty());
        assertTrue(SeciossSsoStore.load(null, NOW).isEmpty());
        assertNull(SeciossSsoStore.serializePersistent(Arrays.asList(c("SimpleSAMLSessionID", "s", -1))));
    }

    @Test
    public void persistable_dropsSingleUseAndDeletedCookies_butKeepsSessionCookiesInMemory() {
        List<SeciossSsoStore.StoredCookie> kept = SeciossSsoStore.persistable(Arrays.asList(
                c("samsessid", "live-session", -1),
                c("auth_tkt", "live-ticket", -1),
                c("_opensaml_req_ss:mem:aaaa", "state1", -1),
                c("_redirection_state", "x", -1),
                c("AWSALB", "", NOW + 1000)), NOW);

        assertEquals(2, kept.size());
        assertEquals("samsessid", kept.get(0).name);
        assertEquals("auth_tkt", kept.get(1).name);
    }

    @Test
    public void sessionCookie_expiresInMemoryAfterLifetime() {
        // プロセスが日を跨いで生きても、失効した IdP セッション cookie を再生し続けない。
        SeciossSsoStore.StoredCookie fresh = new SeciossSsoStore.StoredCookie("samsessid", "a", "/", -1, NOW - 10_000);
        SeciossSsoStore.StoredCookie stale = new SeciossSsoStore.StoredCookie("auth_tkt", "b", "/", -1,
                NOW - SeciossSsoStore.SESSION_COOKIE_LIFETIME_MS);
        List<SeciossSsoStore.StoredCookie> kept = SeciossSsoStore.persistable(Arrays.asList(fresh, stale), NOW);
        assertEquals(1, kept.size());
        assertEquals("samsessid", kept.get(0).name);
    }

    // ---- auth_tkt (Domain=secioss.com) と持ち主 ----

    private static final HttpUrl TENANT_LOGIN = HttpUrl.parse("https://slink.secioss.com/pub/tenantlogin.cgi");

    /** メモリ上の保存先。 */
    private static final class MemoryPersistence implements SeciossSsoStore.Persistence {
        String cookies;
        String owner;
        @Override public String loadCookies() { return cookies; }
        @Override public void saveCookies(String serialized) { cookies = serialized; }
        @Override public String loadOwner() { return owner; }
        @Override public void saveOwner(String o) { owner = o; }
    }

    private long now = NOW;
    private final MemoryPersistence persistence = new MemoryPersistence();
    private final SeciossSsoStore store = new SeciossSsoStore(() -> now, persistence);

    private static List<Cookie> setCookies(HttpUrl url, String... lines) {
        List<Cookie> out = new ArrayList<>();
        for (String line : lines) {
            Cookie c = Cookie.parse(url, line);
            if (c != null) out.add(c);
        }
        return out;
    }

    private static String header(List<Cookie> jar, HttpUrl url) {
        return SeciossIdPClient.cookieHeaderFor(jar, url, NOW);
    }

    /**
     * パスワード POST の応答で出る auth_tkt は Domain=secioss.com。domain の完全一致で絞っていた頃は
     * これを落とし、2 つ目の SP へのログインで毎回 OTP を求められていた (iOS で判明)。
     */
    @Test
    public void parentDomainTicket_isKept_andReplayedWithItsDomain() {
        store.claimFor("s1234567");
        store.merge("slink.secioss.com", setCookies(TENANT_LOGIN,
                "auth_tkt=TICKET; Domain=secioss.com; Path=/",
                "samsessid=SESS; Path=/"));

        List<Cookie> seeded = store.current();
        assertEquals(2, seeded.size());
        assertEquals("auth_tkt=TICKET; samsessid=SESS", header(seeded, TENANT_LOGIN));

        Cookie ticket = seeded.get(0).name().equals("auth_tkt") ? seeded.get(0) : seeded.get(1);
        assertEquals("secioss.com", ticket.domain());
        assertFalse(ticket.hostOnly());

        // サーバが同じ Domain で出し直したら置き換わる (二重送信しない)。
        List<Cookie> jar = new ArrayList<>(seeded);
        SeciossIdPClient.mergeCookies(jar, setCookies(TENANT_LOGIN, "auth_tkt=NEW; Domain=secioss.com; Path=/"), NOW);
        assertEquals("samsessid=SESS; auth_tkt=NEW", header(jar, TENANT_LOGIN));
    }

    @Test
    public void ticket_isSessionCookie_soItStaysInMemoryForAnHourAndIsNeverPersisted() {
        store.claimFor("s1234567");
        store.merge("slink.secioss.com", setCookies(TENANT_LOGIN, "auth_tkt=TICKET; Domain=secioss.com; Path=/"));

        assertNull(persistence.cookies);
        assertEquals(1, store.current().size());

        now = NOW + SeciossSsoStore.SESSION_COOKIE_LIFETIME_MS;
        assertTrue(store.current().isEmpty());
    }

    @Test
    public void persistentParentDomainCookie_roundTripsWithItsDomain() {
        long expires = NOW + 3600_000;
        SeciossSsoStore.StoredCookie c = new SeciossSsoStore.StoredCookie("auth_tkt", "T", "/", expires, NOW, "secioss.com");
        List<SeciossSsoStore.StoredCookie> loaded = SeciossSsoStore.load(
                SeciossSsoStore.serializePersistent(Arrays.asList(c)), NOW);
        assertEquals(1, loaded.size());
        assertEquals("secioss.com", loaded.get(0).parentDomain);
        // この形式の前の版 (4 欄) は host-only として読む。
        assertNull(SeciossSsoStore.load("AWSALB\tv\t/\t" + expires, NOW).get(0).parentDomain);
    }

    @Test
    public void deletionInstruction_removesTheTicket() {
        store.claimFor("s1234567");
        store.merge("slink.secioss.com", setCookies(TENANT_LOGIN, "auth_tkt=TICKET; Domain=secioss.com; Path=/"));
        store.merge("slink.secioss.com", setCookies(TENANT_LOGIN,
                "auth_tkt=; Domain=secioss.com; Path=/; Expires=Thu, 01 Jan 1970 00:00:01 GMT"));
        assertTrue(store.current().isEmpty());
    }

    @Test
    public void onlyCookiesFromTheIdpResponse_andOnlyOnesSentToTheIdp() {
        store.claimFor("s1234567");
        // 別ホストの応答からは取らない。
        HttpUrl other = HttpUrl.parse("https://other.secioss.com/x");
        store.merge("other.secioss.com", setCookies(other, "auth_tkt=FOREIGN; Domain=secioss.com; Path=/"));
        assertTrue(store.current().isEmpty());

        // slink の応答でも、slink に送られない cookie (別ホスト宛ての host-only) は入れない。
        Cookie spCookie = new Cookie.Builder().name("JSESSIONID").value("sp")
                .hostOnlyDomain("csweb.u-aizu.ac.jp").path("/").build();
        List<Cookie> mixed = new ArrayList<>(setCookies(TENANT_LOGIN, "samsessid=SESS; Path=/"));
        mixed.add(spCookie);
        store.merge("slink.secioss.com", mixed);
        assertEquals("samsessid=SESS", header(store.current(), TENANT_LOGIN));
    }

    @Test
    public void ssoOfAnotherAccount_isDiscarded() {
        store.claimFor("s1111111");
        store.merge("slink.secioss.com", setCookies(TENANT_LOGIN,
                "auth_tkt=A; Domain=secioss.com; Path=/",
                "AWSALB=A; Path=/; Expires=Fri, 01 Jan 2100 00:00:00 GMT"));
        assertEquals(2, store.current().size());

        store.claimFor("s2222222");
        assertTrue(store.current().isEmpty());
        assertNull(persistence.cookies);
        assertEquals("s2222222", persistence.owner);
    }

    @Test
    public void ssoWithoutRecordedOwner_isKept() {
        // この版より前に保存された SSO は持ち主が分からないだけ。捨てると更新直後に全員へ OTP メールが飛ぶ。
        persistence.cookies = "AWSALB\tv\t/\t" + (NOW + 3600_000);
        store.claimFor("s1234567");
        assertEquals(1, store.current().size());
        assertEquals("s1234567", persistence.owner);
    }
}
