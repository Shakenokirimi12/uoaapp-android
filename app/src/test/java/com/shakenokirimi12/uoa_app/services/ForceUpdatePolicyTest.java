package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ForceUpdatePolicyTest {
    private static final String PLAY = "https://play.google.com/store/apps/details?id=com.example";

    // ---- バージョンの比較 ----

    @Test
    public void compare_trailingZerosAreEqual() {
        assertEquals(Integer.valueOf(0), ForceUpdatePolicy.compareVersions("3.5", "3.5.0"));
        assertEquals(Integer.valueOf(0), ForceUpdatePolicy.compareVersions("1.0.0.0", "1"));
    }

    @Test
    public void compare_isNumericNotLexical() {
        // 文字列比較だと "1.10" < "1.9" になる。
        assertTrue(ForceUpdatePolicy.compareVersions("1.10", "1.9") > 0);
        assertTrue(ForceUpdatePolicy.compareVersions("3.9", "3.10") < 0);
    }

    @Test
    public void compare_extraComponentsCount() {
        assertTrue(ForceUpdatePolicy.compareVersions("1.0.1", "1.0") > 0);
        assertTrue(ForceUpdatePolicy.compareVersions("3.4.1", "3.5.0") < 0);
        assertTrue(ForceUpdatePolicy.compareVersions("3.5.0.1", "3.5") > 0);
    }

    @Test
    public void compare_toleratesSurroundingWhitespace() {
        assertEquals(Integer.valueOf(0), ForceUpdatePolicy.compareVersions(" 1.0.0\n", "1.0"));
    }

    @Test
    public void compare_garbageIsUnknownNotZero() {
        // 0 とみなすと "abc" が最も古い版になり、全員を止めたり誰も止めなかったりする。
        for (String bad : new String[] {"", " ", "abc", "v1.0", "1.0-beta", "1..0", ".1", "1.", "1.0.x",
                "99999999999", "1.-1", "１.０"}) {
            assertNull(bad, ForceUpdatePolicy.parseVersion(bad));
            assertNull(bad, ForceUpdatePolicy.compareVersions("1.0.0", bad));
            assertNull(bad, ForceUpdatePolicy.compareVersions(bad, "1.0.0"));
        }
        assertNull(ForceUpdatePolicy.parseVersion(null));
    }

    // ---- ストアの URL ----

    @Test
    public void storeUrl_acceptsHttpsWithHostAndMarket() {
        assertTrue(ForceUpdatePolicy.isValidStoreUrl(PLAY));
        assertTrue(ForceUpdatePolicy.isValidStoreUrl("market://details?id=com.example"));
    }

    @Test
    public void storeUrl_rejectsOthers() {
        for (String bad : new String[] {null, "", " ", "http://play.google.com/x", "https://", "https:///x",
                "javascript:alert(1)", "intent://x", "not a url", " https://play.google.com/x", "play.google.com"}) {
            assertFalse(String.valueOf(bad), ForceUpdatePolicy.isValidStoreUrl(bad));
        }
    }

    @Test
    public void storeUrl_fallsBackToPlayPage() {
        String fallback = ForceUpdatePolicy.playStoreUrl("com.shakenokirimi12.uoa_app");
        assertEquals("https://play.google.com/store/apps/details?id=com.shakenokirimi12.uoa_app", fallback);
        assertEquals(fallback, ForceUpdatePolicy.storeUrl(null, "com.shakenokirimi12.uoa_app"));
        assertEquals(fallback, ForceUpdatePolicy.storeUrl(
                new ForceUpdatePolicy.Requirement("1.0.1", null, null), "com.shakenokirimi12.uoa_app"));
        assertEquals(PLAY, ForceUpdatePolicy.storeUrl(
                new ForceUpdatePolicy.Requirement("1.0.1", PLAY, null), "com.shakenokirimi12.uoa_app"));
    }

    // ---- 端末に残す条件 ----

    @Test
    public void nothingFetched_failsOpen() {
        assertFalse(ForceUpdatePolicy.isBlocked("1.0.0", null));
    }

    @Test
    public void fetchedValidMinimum_isSavedAndBlocksOlder() {
        ForceUpdatePolicy.Requirement r = ForceUpdatePolicy.afterSuccessfulFetch(null, " 1.0.1 ", PLAY, "更新してください");
        assertEquals(new ForceUpdatePolicy.Requirement("1.0.1", PLAY, "更新してください"), r);
        assertTrue(ForceUpdatePolicy.isBlocked("1.0.0", r));
        assertFalse(ForceUpdatePolicy.isBlocked("1.0.1", r));
        assertFalse(ForceUpdatePolicy.isBlocked("1.1", r));
    }

    @Test
    public void fetchedInvalidUrl_isDroppedButMinimumKept() {
        ForceUpdatePolicy.Requirement r = ForceUpdatePolicy.afterSuccessfulFetch(null, "1.0.1", "javascript:x", " ");
        assertEquals(new ForceUpdatePolicy.Requirement("1.0.1", null, null), r);
        assertTrue(ForceUpdatePolicy.isBlocked("1.0.0", r));
    }

    @Test
    public void fetchedNullOrBlankMinimum_clears() {
        ForceUpdatePolicy.Requirement cached = new ForceUpdatePolicy.Requirement("1.0.1", PLAY, null);
        assertNull(ForceUpdatePolicy.afterSuccessfulFetch(cached, null, PLAY, null));
        assertNull(ForceUpdatePolicy.afterSuccessfulFetch(cached, "  ", PLAY, null));
    }

    @Test
    public void fetchedGarbageMinimum_keepsCached() {
        ForceUpdatePolicy.Requirement cached = new ForceUpdatePolicy.Requirement("1.0.1", PLAY, null);
        assertSame(cached, ForceUpdatePolicy.afterSuccessfulFetch(cached, "v2", null, null));
        assertTrue(ForceUpdatePolicy.isBlocked("1.0.0", ForceUpdatePolicy.afterSuccessfulFetch(cached, "v2", null, null)));
        // 何も残っていないときは、壊れた値では止めない。
        assertNull(ForceUpdatePolicy.afterSuccessfulFetch(null, "v2", null, null));
    }

    @Test
    public void failedFetch_keepsEnforcingCached() {
        // 取得に失敗したときは afterSuccessfulFetch を呼ばない = 残っている条件がそのまま効く。
        ForceUpdatePolicy.Requirement cached = new ForceUpdatePolicy.Requirement("1.0.1", null, null);
        assertTrue(ForceUpdatePolicy.isBlocked("1.0.0", cached));
    }

    @Test
    public void unreadableOwnVersion_doesNotBlock() {
        ForceUpdatePolicy.Requirement r = new ForceUpdatePolicy.Requirement("1.0.1", null, null);
        assertFalse(ForceUpdatePolicy.isBlocked("1.0.0-debug", r));
    }
}
