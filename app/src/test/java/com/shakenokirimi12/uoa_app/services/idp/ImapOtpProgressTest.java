package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher.Progress.Stage;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** 中継サーバへ送る detail。どの段階で何が起きたかだけで、ホスト名・IP・応答の本文は入れない。 */
public class ImapOtpProgressTest {
    private static String detail(Exception e, Stage s, int reconnects) {
        return ImapOtpFetcher.Progress.detail(e, s, reconnects);
    }

    @Test
    public void mapsFailuresToStageCodes() {
        assertEquals("mail-timeout", detail(new ImapOtpFetcher.ImapException("mailNotFound"), Stage.IDLE, 0));
        assertEquals("code-not-found", detail(new ImapOtpFetcher.ImapException("codeNotFound"), Stage.FETCH, 0));
        assertEquals("login-no:unavailable", detail(new ImapOtpFetcher.ImapException(
                "commandFailed(a1): a1 NO [UNAVAILABLE] Temporary failure"), Stage.LOGIN, 0));
        assertEquals("select-bad", detail(new ImapOtpFetcher.ImapException("commandFailed(a2): a2 BAD x"), Stage.SELECT, 0));
        assertEquals("fetch-no", detail(new ImapOtpFetcher.ImapException("commandFailed(a7)"), Stage.FETCH, 0));
        assertEquals("connect-timeout", detail(new SocketTimeoutException("failed to connect to host/1.2.3.4"), Stage.CONNECT, 0));
        assertEquals("select-stall:r2", detail(new SocketTimeoutException("Read timed out"), Stage.SELECT, 2));
        assertEquals("connect-failed:dns", detail(new UnknownHostException("stdmsv1.u-aizu.ac.jp"), Stage.CONNECT, 0));
        assertEquals("connect-failed:tls", detail(new javax.net.ssl.SSLHandshakeException("x"), Stage.CONNECT, 0));
        assertEquals("search-closed:r1", detail(new IOException("connectionFailed"), Stage.SEARCH, 1));
        assertEquals("cancelled", detail(new InterruptedException(), Stage.IDLE, 0));
    }

    @Test
    public void keepsOnlyTheErrnoName_notAddresses() {
        String d = detail(new ConnectException(
                "failed to connect to stdmsv1.u-aizu.ac.jp/163.143.1.2 (port 993) from /10.0.2.16 (port 4567) "
                        + "after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)"), Stage.CONNECT, 0);
        assertEquals("connect-failed:econnrefused", d);
        assertEquals("greeting-error", detail(new IOException("something at 10.0.0.1"), Stage.GREETING, 0));
    }

    @Test
    public void detail_usesTheStageWhereTheErrorWasRecorded() {
        ImapOtpFetcher.Progress p = new ImapOtpFetcher.Progress();
        p.enter(Stage.SEARCH);
        IOException e = new IOException("connectionFailed");
        p.recordFailure(e);
        p.reconnected();
        p.enter(Stage.CONNECT);
        assertEquals("search-closed", p.detail(e));
        // 記録していないエラーは今の段階で作る。
        assertEquals("connect-closed:r1", p.detail(new IOException("connectionFailed")));
    }

    @Test
    public void sanitize_fitsTheRelayPattern() {
        String s = ImapOtpFetcher.Progress.sanitize("-Weird Code!" + "x".repeat(100));
        assertTrue(s.matches("^[a-z0-9][a-z0-9._:-]{0,79}$"));
    }
}
