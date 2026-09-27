package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    @Test
    public void parseGrades_readsCreditsFromHeaderColumn() {
        String html = "<table><tr><th>単位修得状況</th><th>合計</th></tr><tr><td>x</td></tr></table>"
                + "<table><tr><th>No.</th><th>年度</th><th>学期</th><th>科目コード</th><th>科目名</th>"
                + "<th>教員</th><th>点数</th><th>評価</th><th>単位数</th></tr>"
                + "<tr><td>1</td><td>2025</td><td>前期</td><td>FU01</td><td>線形代数</td>"
                + "<td>山田</td><td>85</td><td>A</td><td>2</td></tr></table>";
        java.util.List<com.shakenokirimi12.uoa_app.data.models.Grade> g = CampusSquareService.parseGrades(html);
        assertEquals(1, g.size());
        assertEquals("線形代数", g.get(0).getCourseName());
        assertEquals("A", g.get(0).getGrade());
        assertEquals("2", g.get(0).getCredits());
    }

    @Test
    public void parseGrades_withoutCreditsHeader_leavesCreditsEmpty() {
        String html = "<tr><td>1</td><td>2025</td><td>前期</td><td>FU01</td><td>線形代数</td>"
                + "<td>山田</td><td>85</td><td>A</td></tr>";
        java.util.List<com.shakenokirimi12.uoa_app.data.models.Grade> g = CampusSquareService.parseGrades(html);
        assertEquals(1, g.size());
        assertNull(g.get(0).getCredits());
    }
}
