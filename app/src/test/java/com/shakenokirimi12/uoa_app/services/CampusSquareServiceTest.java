package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CampusSquareServiceTest {
    private static final String HOST = "csweb.u-aizu.ac.jp";
    private static final String PORTAL = "https://csweb.u-aizu.ac.jp/campusweb/campusportal.do?page=main";
    private static final String AUTHENTICATED = "<html><body><a href=\"logout\">ログアウト</a><input name=\"password\" type=\"hidden\"></body></html>";

    @Test
    public void authenticatedPortal_isNotUnauthenticated() {
        assertFalse(CampusSquareService.looksUnauthenticated(AUTHENTICATED, PORTAL, HOST));
        // URL が取れないときはホスト判定を飛ばして本文だけ見る
        assertFalse(CampusSquareService.looksUnauthenticated(AUTHENTICATED, null, HOST));
        assertFalse(CampusSquareService.looksUnauthenticated(AUTHENTICATED, "", HOST));
    }

    @Test
    public void redirectedOffHostOrToTenantLogin_isUnauthenticated() {
        assertTrue(CampusSquareService.looksUnauthenticated(AUTHENTICATED, "https://idp.example.ac.jp/sso/", HOST));
        assertTrue(CampusSquareService.looksUnauthenticated(AUTHENTICATED,
                "https://csweb.u-aizu.ac.jp/cgi-bin/tenantlogin.cgi?back=x", HOST));
    }

    @Test
    public void legacyLoginForm_isUnauthenticated() {
        String form = "<form><input name=\"userName\"><input name=\"password\" type=\"password\"></form>";
        assertTrue(CampusSquareService.looksUnauthenticated(form, PORTAL, HOST));
        // password だけではログインフォームと見なさない (認証後のページにも hidden で現れ得る)
        assertFalse(CampusSquareService.looksUnauthenticated("<input name=\"password\">", PORTAL, HOST));
    }

    @Test
    public void ssoErrorAndAuthErrorPage_areUnauthenticated() {
        assertTrue(CampusSquareService.looksUnauthenticated("<p>[SSO-Error] あなたは現在このシステムを利用することができません</p>", PORTAL, HOST));
        assertTrue(CampusSquareService.looksUnauthenticated("<html><head><title>認証エラー</title></head></html>", PORTAL, HOST));
    }
}
