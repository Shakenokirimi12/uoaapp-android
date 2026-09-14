package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SeciossIdPClientTest {

    private static final String LOGIN_FORM =
            "<form action=\"tenantlogin.cgi\" method=\"post\">"
            + "<input type=\"hidden\" value=\"abc123\" name=\"sessid\">"
            + "<input name=\"back\" type=\"hidden\" value=\"https://slink.secioss.com/pub/login.cgi?x=1&amp;y=2\" />"
            + "<input type=\"hidden\" name=\"tenant\" value=\"u-aizu.ac.jp\">"
            + "<input type=\"text\" name=\"username\">"
            + "</form>";

    @Test
    public void extractField_ignoresAttributeOrder() {
        // id/name/value の並びはページによって揺れるので、タグ全体を切ってから value を探す
        assertEquals("abc123", SeciossIdPClient.extractField("sessid", LOGIN_FORM));
        // back はクエリ文字列を含み &amp; でエスケープされている。そのまま GET すると別 URL になる
        assertEquals("https://slink.secioss.com/pub/login.cgi?x=1&y=2", SeciossIdPClient.extractField("back", LOGIN_FORM));
        assertEquals("u-aizu.ac.jp", SeciossIdPClient.extractField("tenant", LOGIN_FORM));
    }

    @Test
    public void extractField_missingValueOrField() {
        assertNull(SeciossIdPClient.extractField("username", LOGIN_FORM));
        assertNull(SeciossIdPClient.extractField("password", LOGIN_FORM));
    }

    @Test
    public void containsAuthFailureMessage_ignoresScriptBodies() {
        // ログインフォームは正常時でも JS 内に同じ文言を持つ。そこだけ見て誤検知しない
        String normal = "<html><script>var m = 'User Name or password is invalid';</script><form></form></html>";
        assertFalse(SeciossIdPClient.containsAuthFailureMessage(normal));
        String failed = normal + "<div id=\"comment\" class=\"message error\">ユーザー名またはパスワードが間違っています</div>";
        assertTrue(SeciossIdPClient.containsAuthFailureMessage(failed));
    }

    @Test
    public void detectRelay_autoSubmitForm() {
        String html = "<body onload=\"document.f.submit()\">"
                + "<form name=\"f\" action=\"https://csweb.u-aizu.ac.jp/campusweb/saml/acs\" method=\"post\">"
                + "<input type=\"hidden\" name=\"SAMLResponse\" value=\"PHNhbWw+\"/>"
                + "<input type=\"hidden\" name=\"RelayState\" value=\"a&amp;b\"/>"
                + "</form></body>";
        SeciossIdPClient.DetectedRelay relay = SeciossIdPClient.detectRelay(html);
        assertNotNull(relay);
        assertTrue(relay.isFormSubmit());
        assertEquals("https://csweb.u-aizu.ac.jp/campusweb/saml/acs", relay.formAction);
        assertEquals("PHNhbWw+", relay.fields.get("SAMLResponse"));
        assertEquals("a&b", relay.fields.get("RelayState"));
    }

    @Test
    public void detectRelay_metaRefreshAndLocation() {
        SeciossIdPClient.DetectedRelay meta = SeciossIdPClient.detectRelay(
                "<meta http-equiv=\"refresh\" content=\"0;url=/pub/next.cgi?s=1\">");
        assertNotNull(meta);
        assertFalse(meta.isFormSubmit());
        assertEquals("/pub/next.cgi?s=1", meta.redirectGetUrl);

        SeciossIdPClient.DetectedRelay loc = SeciossIdPClient.detectRelay(
                "<script>location.href = \"https://example.test/x\";</script>");
        assertNotNull(loc);
        assertEquals("https://example.test/x", loc.redirectGetUrl);

        assertNull(SeciossIdPClient.detectRelay("<html><a href=\"/x\">link</a></html>"));
    }

    @Test
    public void resolve_relativeAgainstPageUrl() {
        assertEquals("https://slink.secioss.com/pub/allotplogin.cgi",
                SeciossIdPClient.resolve("https://slink.secioss.com/pub/login.cgi?a=1", "allotplogin.cgi"));
    }

    @Test
    public void existingStatusLabel_parsesMethodName() {
        String html = "<p>ワンタイムパスワードは既に設定済みです:&nbsp; メール </p>";
        assertEquals("メール", SeciossRegistrationSession.existingStatusLabel(html));
        assertNull(SeciossRegistrationSession.existingStatusLabel("<p>未設定</p>"));
    }

    @Test
    public void htmlUnescape_basicEntities() {
        assertEquals("a&b \"q\" 'x' <t>", SeciossIdPClient.htmlUnescape("a&amp;b &quot;q&quot; &#39;x&#39; &lt;t&gt;"));
    }

    @Test
    public void urlEncode_spaceIsPercent20() {
        assertEquals("a%20b%26c", SeciossIdPClient.urlEncode("a b&c"));
    }

    @Test
    public void visibleExcerpt_stripsScriptStyleAndTags() {
        String html = "<html><head><style>body { color: red }</style>"
                + "<script type=\"text/javascript\">var x = '<b>hidden</b>';</script></head>"
                + "<body>\n  <div>Hello&nbsp;<b>World</b></div>\n\n<p>  second   line </p></body></html>";
        assertEquals("Hello World second line", SeciossIdPClient.visibleExcerpt(html, 400));
        assertEquals("Hello", SeciossIdPClient.visibleExcerpt(html, 5));
    }

    @Test
    public void classifyErrorPage_authErrIsAccessDenied() {
        String html = "<html><body><p>アクセスが許可されていません</p><a href=\"logout.cgi\">ログアウト</a></body></html>";
        SeciossError e = SeciossIdPClient.classifyErrorPage(
                "https://slink.secioss.com/pub/error.cgi?msg=auth_err_003&lang=ja", html);
        assertNotNull(e);
        assertEquals(SeciossError.Kind.ACCESS_DENIED, e.kind);
        assertEquals("auth_err_003", e.detail);
        assertTrue(e.loginMessage().contains("auth_err_003"));
    }

    @Test
    public void classifyErrorPage_bodyMarkerWithoutMsgIsAccessDenied() {
        SeciossError e = SeciossIdPClient.classifyErrorPage(
                "https://slink.secioss.com/pub/error.cgi", "<p>アクセスが許可されていません</p>");
        assertNotNull(e);
        assertEquals(SeciossError.Kind.ACCESS_DENIED, e.kind);
        assertEquals("unknown", e.detail);
    }

    @Test
    public void classifyErrorPage_otherMsgIsUnrecognizedWithExcerpt() {
        // error.cgi はどのメッセージでも「ログアウト」を含むので、成功と誤判定してはならない
        String html = "<script>var a=1;</script><h1>エラー</h1><p>不明な問題が発生しました</p><a href=\"#\">ログアウト</a>";
        SeciossError e = SeciossIdPClient.classifyErrorPage(
                "https://slink.secioss.com/pub/error.cgi?msg=sys_err_001", html);
        assertNotNull(e);
        assertEquals(SeciossError.Kind.UNRECOGNIZED_STATE, e.kind);
        assertNotNull(e.detail);
        assertTrue(e.detail.startsWith("error.cgi(sys_err_001) "));
        assertTrue(e.detail.contains("不明な問題が発生しました"));
        assertFalse(e.detail.contains("var a=1"));
    }

    @Test
    public void classifyErrorPage_nonErrorUrlIsNull() {
        assertNull(SeciossIdPClient.classifyErrorPage("https://csweb.u-aizu.ac.jp/campusweb/campusportal.do", "ログアウト"));
    }
}
