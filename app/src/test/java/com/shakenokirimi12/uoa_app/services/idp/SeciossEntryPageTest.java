package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 入口が返した画面の見分け。途中まで済んだ SSO を載せると OTP の入力画面が返り、以前はこれを
 * 「ログイン画面が見つからない」(MISSING_FIELD) として止まっていた。
 */
public class SeciossEntryPageTest {
    /** 2026-10-23 に campusweb/ssologin.do?page=portal60 から辿ったログインフォームの input の形 (値は置き換え)。 */
    private static final String LOGIN_FORM =
            "<form id=\"login\" name=\"login\" class=\"login\" method=\"post\" action=\"tenantlogin.cgi\" autocomplete=\"off\">"
            + "<input type=\"hidden\" name=\"FunctionID\" id=\"FunctionID\" value=\"tenantlogin\">"
            + "<input type=\"text\" id=\"dummy\" name=\"dummy\" class=\"login_dummy_input\">"
            + "<input type=\"text\" id=\"username_input\" name=\"username\"  autofocus>"
            + "<input type=\"hidden\" id=\"tenant\" name=\"tenant\" value=\"u-aizu.ac.jp\">"
            + "<input type=\"password\" id=\"password_input\" name=\"password\" autofocus>"
            + "<input type=\"hidden\" id=\"op\" name=\"op\" value=\"login\">"
            + "<input type=\"hidden\" id=\"back\" name=\"back\" value=\"https://slink.secioss.com/saml/saml2/idp/SSOService.php?RequestID=_x&amp;tenant=u-aizu.ac.jp\">"
            + "<input type=\"hidden\" id=\"sessid\" name=\"sessid\" value=\"abc123\">"
            + "</form>";

    /** OTP の入力画面。SeciossIdPClient.login が読む項目 (sessid / back / username) を持ち、tenant は無い。 */
    private static final String OTP_FORM =
            "<form method=\"post\" action=\"allotplogin.cgi\">"
            + "<input type=\"hidden\" name=\"FunctionID\" id=\"FunctionID\" value=\"allotplogin\">"
            + "<input type=\"hidden\" name=\"username\" value=\"s1300000\">"
            + "<input type=\"password\" name=\"password\">"
            + "<input type=\"hidden\" name=\"back\" value=\"https://slink.secioss.com/saml/x\">"
            + "<input type=\"hidden\" name=\"sessid\" value=\"def456\">"
            + "</form>";

    @Test
    public void loginFormIsRecognised() {
        assertEquals(SeciossIdPClient.EntryPage.LOGIN_FORM, SeciossIdPClient.classifyEntryPage(LOGIN_FORM));
    }

    @Test
    public void otpFormFromAHalfFinishedSsoIsNotAMissingLoginForm() {
        assertEquals(SeciossIdPClient.EntryPage.OTP_FORM, SeciossIdPClient.classifyEntryPage(OTP_FORM));
    }

    @Test
    public void relayWithoutAFormPassesThrough() {
        String relay = "<form method=\"post\" action=\"https://csweb.u-aizu.ac.jp/Shibboleth.sso/SAML2/POST\">"
                + "<input type=\"hidden\" name=\"SAMLResponse\" value=\"x\"></form>"
                + "<script>document.forms[0].submit();</script>";
        assertEquals(SeciossIdPClient.EntryPage.NO_FORM, SeciossIdPClient.classifyEntryPage(relay));
    }

    @Test
    public void sessidWithoutTheOtherFieldsIsIncomplete() {
        String odd = "<input type=\"hidden\" name=\"sessid\" value=\"abc\"><input name=\"back\" value=\"x\">";
        assertEquals(SeciossIdPClient.EntryPage.INCOMPLETE, SeciossIdPClient.classifyEntryPage(odd));
    }

    @Test
    public void relativeBackIsResolvedButAbsoluteBackIsLeftAsIs() {
        String abs = "https://slink.secioss.com/saml/saml2/idp/SSOService.php?RequestID=_x&RelayState=ss%3Amem%3Aab";
        assertEquals(abs, SeciossIdPClient.absoluteAgainst("https://slink.secioss.com/pub/tenantlogin.cgi", abs));
        assertEquals("https://slink.secioss.com/saml/next",
                SeciossIdPClient.absoluteAgainst("https://slink.secioss.com/pub/tenantlogin.cgi", "/saml/next"));
    }

    @Test
    public void escapedAmpersandIsDecodedOnlyOnce() {
        assertEquals("&lt;", SeciossIdPClient.htmlUnescape("&amp;lt;"));
    }
}
