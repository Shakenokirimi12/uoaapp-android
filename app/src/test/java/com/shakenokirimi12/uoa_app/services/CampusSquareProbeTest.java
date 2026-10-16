package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.shakenokirimi12.uoa_app.testing.LoopbackHttpServer;
import com.shakenokirimi12.uoa_app.testing.LoopbackHttpServer.Reply;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.Request;
import okhttp3.Response;

/**
 * CampusSquare のセッション probe と、手で付けた Cookie ヘッダの扱い。
 *
 * ループバックの 1 台のサーバを "127.0.0.1" (= csweb 役) と "localhost" (= IdP 役) の 2 つのホスト名で
 * 叩き分ける。OkHttp にとってはホスト名が違えば別ホスト。
 */
public class CampusSquareProbeTest {

    private LoopbackHttpServer server;

    @Before
    public void setUp() throws Exception {
        server = new LoopbackHttpServer(req -> {
            switch (req.path) {
                case "/expired": // 未ログインの csweb: IdP へ 302。
                    return new Reply(302, "").header("Location", "http://localhost:" + server.port() + "/idp");
                case "/idp":
                    return new Reply(200, "<html><form action=\"tenantlogin.cgi\"></form></html>");
                case "/portal":
                    return new Reply(200, "<html><a href=\"logout\">ログアウト</a></html>");
                case "/loginform":
                    return new Reply(200, "<form><input name=\"userName\"><input name=\"password\"></form>");
                default:
                    return new Reply(404, "");
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        server.close();
    }

    private String cs(String path) { return "http://127.0.0.1:" + server.port() + path; }

    @Test
    public void redirect_meansNotLoggedIn_andIsNotFollowed() {
        String result = CampusSquareService.probeSession(NetworkClient.getNoRedirectClient(),
                cs("/expired"), "JSESSIONID=secret", "127.0.0.1");

        assertNull(result);
        assertEquals("JSESSIONID=secret", server.first("/expired").header("Cookie"));
        // IdP へは 1 リクエストも行かない (CampusSquare の cookie を IdP に渡さない)。
        assertNull(server.first("/idp"));
    }

    @Test
    public void portalPage_meansAlive() {
        String result = CampusSquareService.probeSession(NetworkClient.getNoRedirectClient(),
                cs("/portal"), "JSESSIONID=secret", "127.0.0.1");
        assertEquals("JSESSIONID=secret", result);
    }

    @Test
    public void loginForm_meansNotLoggedIn() {
        assertNull(CampusSquareService.probeSession(NetworkClient.getNoRedirectClient(),
                cs("/loginform"), "JSESSIONID=secret", "127.0.0.1"));
    }

    @Test
    public void noCookieClient_dropsManualCookieHeaderOnCrossHostRedirect() throws Exception {
        Request req = new Request.Builder().url(cs("/expired")).header("Cookie", "JSESSIONID=secret").build();
        try (Response resp = NetworkClient.getNoCookieClient().newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("localhost", resp.request().url().host());
        }
        assertEquals("JSESSIONID=secret", server.first("/expired").header("Cookie"));
        assertNull(server.first("/idp").header("Cookie"));
    }

    @Test
    public void noCookieClient_keepsCookieHeaderOnSameHost() throws Exception {
        Request req = new Request.Builder().url(cs("/portal")).header("Cookie", "JSESSIONID=secret").build();
        try (Response resp = NetworkClient.getNoCookieClient().newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
        assertEquals("JSESSIONID=secret", server.first("/portal").header("Cookie"));
    }
}
