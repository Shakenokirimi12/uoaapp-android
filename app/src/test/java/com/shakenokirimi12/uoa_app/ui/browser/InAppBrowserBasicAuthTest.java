package com.shakenokirimi12.uoa_app.ui.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** アプリ内ブラウザが保存済みの資格情報で答えてよい Basic 認証 (CLAUDE-SECURITY F3)。 */
public class InAppBrowserBasicAuthTest {
    private static final String PAGE = "https://web-int.u-aizu.ac.jp/";

    @Test
    public void onlyTheKnownHostAndRealmOverHttps() {
        assertTrue(InAppBrowserActivity.mayOfferStoredCredentials("web-int.u-aizu.ac.jp", "web-int-proxy", PAGE));
        assertTrue(InAppBrowserActivity.mayOfferStoredCredentials("WEB-INT.u-aizu.ac.jp", "web-int-proxy", PAGE));
    }

    @Test
    public void otherCampusHostsAreRefused() {
        // 学内の他のホスト (教員の個人ページ・研究室のサーバ) が 401 を返しても渡さない。
        assertFalse(InAppBrowserActivity.mayOfferStoredCredentials("web-ext.u-aizu.ac.jp", "web-int-proxy", PAGE));
        assertFalse(InAppBrowserActivity.mayOfferStoredCredentials("web-int.u-aizu.ac.jp.evil.example", "web-int-proxy", PAGE));
    }

    @Test
    public void otherRealmOrPlainHttpIsRefused() {
        assertFalse(InAppBrowserActivity.mayOfferStoredCredentials("web-int.u-aizu.ac.jp", "x", PAGE));
        assertFalse(InAppBrowserActivity.mayOfferStoredCredentials("web-int.u-aizu.ac.jp", "web-int-proxy",
                "http://web-int.u-aizu.ac.jp/"));
        assertFalse(InAppBrowserActivity.mayOfferStoredCredentials("web-int.u-aizu.ac.jp", "web-int-proxy", null));
    }
}
