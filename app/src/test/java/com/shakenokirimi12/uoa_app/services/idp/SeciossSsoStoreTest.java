package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

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
        assertEquals("AWSALB\tlive\t/\t" + (NOW + 3600_000)
                + "\nAWSALBCORS\tstale\t/\t" + (NOW - 1)
                + "\n_opensaml_req_ss:mem:aaaa\tstate\t/\t" + (NOW + 3600_000)
                + "\nauth_tkt\t\t/\t" + (NOW + 3600_000), serialized);

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
}
