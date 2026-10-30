package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher.Progress.Stage;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ImapOtpFetcherTest {

    @Test
    public void extractCode_standaloneDigitsLineOnly() {
        String body = "Your one-time password is below.\r\n\r\n  12345678  \r\n\r\nValid for 5 minutes (2026-09-14).";
        assertEquals("12345678", ImapOtpFetcher.extractCode(body));
    }

    @Test
    public void extractCode_ignoresInlineNumbersAndShortCodes() {
        assertNull(ImapOtpFetcher.extractCode("code: 123456 inline"));
        assertNull(ImapOtpFetcher.extractCode("12345\r\n"));
    }

    @Test
    public void fromAddress_takesTheRealAddress() {
        assertEquals("idp-apply@u-aizu.ac.jp", ImapOtpFetcher.fromAddress("From: idp-apply@u-aizu.ac.jp\r\n\r\n"));
        assertEquals("idp-apply@u-aizu.ac.jp",
                ImapOtpFetcher.fromAddress("from: =?UTF-8?B?SWRQ?= <idp-apply@u-aizu.ac.jp>\r\n\r\n"));
        // 折り返されたヘッダ。
        assertEquals("idp-apply@u-aizu.ac.jp",
                ImapOtpFetcher.fromAddress("From: \"IdP\"\r\n <idp-apply@u-aizu.ac.jp>\r\n\r\n"));
    }

    @Test
    public void fromAddress_ignoresTheDisplayName() {
        // CLAUDE-SECURITY F20: 送信者名に送信元を書いただけのメール。SEARCH FROM (部分一致) には当たる。
        assertEquals("x@attacker.example",
                ImapOtpFetcher.fromAddress("From: \"idp-apply@u-aizu.ac.jp\" <x@attacker.example>\r\n"));
        assertEquals("x@attacker.example",
                ImapOtpFetcher.fromAddress("From: \"<idp-apply@u-aizu.ac.jp>\" <x@attacker.example>\r\n"));
        assertEquals("x@attacker.example",
                ImapOtpFetcher.fromAddress("From: (idp-apply@u-aizu.ac.jp) x@attacker.example\r\n"));
        assertEquals("idp-apply@u-aizu.ac.jp.attacker.example",
                ImapOtpFetcher.fromAddress("From: <idp-apply@u-aizu.ac.jp.attacker.example>\r\n"));
    }

    @Test
    public void fromAddress_rejectsAmbiguousHeaders() {
        assertNull(ImapOtpFetcher.fromAddress(""));
        assertNull(ImapOtpFetcher.fromAddress("From: idp-apply@u-aizu.ac.jp\r\nFrom: x@attacker.example\r\n"));
        assertNull(ImapOtpFetcher.fromAddress("From: idp-apply@u-aizu.ac.jp, x@attacker.example\r\n"));
        assertNull(ImapOtpFetcher.fromAddress("From: <idp-apply@u-aizu.ac.jp> <x@attacker.example>\r\n"));
        assertNull(ImapOtpFetcher.fromAddress("From: \"unterminated <idp-apply@u-aizu.ac.jp>\r\n"));
    }

    @Test
    public void quote_escapesBackslashAndQuote() {
        assertEquals("\"a\\\\b\\\"c\"", ImapOtpFetcher.quote("a\\b\"c"));
    }

    @Test
    public void parsesInternalDate() {
        Long at = ImapOtpFetcher.parseInternalDate(java.util.Arrays.asList(
                "* 5 FETCH (UID 123 INTERNALDATE \"15-Sep-2026 14:23:45 +0900\")"));
        assertNotNull(at);
        // 2026-09-15 14:23:45 +0900 = 2026-09-15 05:23:45 UTC
        assertEquals(1789449825000L, (long) at);
    }

    @Test
    public void returnsNullForMissingOrMalformedInternalDate() {
        assertNull(ImapOtpFetcher.parseInternalDate(java.util.Arrays.asList("* 5 FETCH (UID 123)")));
        assertNull(ImapOtpFetcher.parseInternalDate(java.util.Arrays.asList(
                "* 5 FETCH (INTERNALDATE \"not a date\")")));
    }

    @Test
    public void parsesUidNext_fromSelectResponse() {
        java.util.List<String> lines = java.util.Arrays.asList(
                "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)",
                "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] Flags permitted.",
                "* 12873 EXISTS",
                "* 0 RECENT",
                "* OK [UIDVALIDITY 1600000000] UIDs valid",
                "* OK [UIDNEXT 13045] Predicted next UID");
        assertEquals(Integer.valueOf(13045), ImapOtpFetcher.parseUidNext(lines));
        assertNull(ImapOtpFetcher.parseUidNext(java.util.Arrays.asList(
                "* 12873 EXISTS", "* OK [UIDVALIDITY 1600000000] UIDs valid")));
    }

    /** 新着は SEARCH ではなく、直近の UID の範囲のヘッダを 1 回の FETCH で取って手元で探す (2026-10-25)。 */
    @Test
    public void headerScan_isBoundedByUidRange() {
        int from = ImapOtpFetcher.scanLowerBound(13045);
        assertEquals(13045 - ImapOtpFetcher.RECENT_UID_WINDOW, from);
        assertEquals("a5 UID FETCH " + from + ":* (UID INTERNALDATE BODY.PEEK[HEADER.FIELDS (FROM)])",
                ImapOtpFetcher.headerFetchCommand("a5", from, null));
        // 削除は両端を決める。
        assertEquals("a9 UID FETCH 12946:13024 (UID INTERNALDATE BODY.PEEK[HEADER.FIELDS (FROM)])",
                ImapOtpFetcher.headerFetchCommand("a9", 12946, 13024));
        // 小さな受信箱・UIDNEXT が無いときは 1 から。
        assertEquals(1, ImapOtpFetcher.scanLowerBound(10));
        assertEquals(1, ImapOtpFetcher.scanLowerBound(null));
    }

    /** ログインしたあとに止まったら繋ぎ直さない。繋ぎ直しは通信断か一時的な NO のときだけで、1 回まで。 */
    @Test
    public void reconnectPolicy() {
        java.net.SocketTimeoutException stall = new java.net.SocketTimeoutException("Read timed out");
        assertEquals(ImapOtpFetcher.ConnectionFailure.STALLED_AFTER_LOGIN, ImapOtpFetcher.classify(stall, Stage.SCAN));
        assertEquals(ImapOtpFetcher.ConnectionFailure.STALLED_AFTER_LOGIN, ImapOtpFetcher.classify(stall, Stage.RECHECK));
        assertEquals(ImapOtpFetcher.ConnectionFailure.STALLED_AFTER_LOGIN, ImapOtpFetcher.classify(stall, Stage.SELECT));
        assertEquals(ImapOtpFetcher.ConnectionFailure.STALLED_AFTER_LOGIN, ImapOtpFetcher.classify(stall, Stage.FETCH));
        assertEquals(ImapOtpFetcher.ConnectionFailure.TRANSPORT, ImapOtpFetcher.classify(stall, Stage.GREETING));
        assertEquals(ImapOtpFetcher.ConnectionFailure.TRANSPORT, ImapOtpFetcher.classify(stall, Stage.LOGIN));
        assertEquals(ImapOtpFetcher.ConnectionFailure.TRANSPORT, ImapOtpFetcher.classify(stall, Stage.CONNECT));
        assertEquals(ImapOtpFetcher.ConnectionFailure.TRANSPORT,
                ImapOtpFetcher.classify(new java.net.SocketException("Connection reset"), Stage.SCAN));
        assertEquals(ImapOtpFetcher.ConnectionFailure.TRANSPORT,
                ImapOtpFetcher.classify(new java.io.IOException("connectionFailed"), Stage.FETCH));
        assertEquals(ImapOtpFetcher.ConnectionFailure.AUTHENTICATION_REJECTED, ImapOtpFetcher.classify(
                new ImapOtpFetcher.ImapException("commandFailed(a1): a1 NO [AUTHENTICATIONFAILED] Authenticat"),
                Stage.LOGIN));
        assertEquals(ImapOtpFetcher.ConnectionFailure.REFUSED, ImapOtpFetcher.classify(
                new ImapOtpFetcher.ImapException("commandFailed(a1): a1 NO [UNAVAILABLE] Maximum number of co"),
                Stage.LOGIN));
        assertEquals(ImapOtpFetcher.ConnectionFailure.REFUSED, ImapOtpFetcher.classify(
                new ImapOtpFetcher.ImapException("commandFailed(a2): a2 NO [INUSE] x"), Stage.SELECT));

        assertNull(ImapOtpFetcher.reconnectDelay(ImapOtpFetcher.ConnectionFailure.STALLED_AFTER_LOGIN, 1, 15_000));
        assertNull(ImapOtpFetcher.reconnectDelay(ImapOtpFetcher.ConnectionFailure.AUTHENTICATION_REJECTED, 1, 15_000));
        assertEquals(Long.valueOf(ImapOtpFetcher.TRANSPORT_RETRY_DELAY_MS),
                ImapOtpFetcher.reconnectDelay(ImapOtpFetcher.ConnectionFailure.TRANSPORT, 1, 15_000));
        assertEquals(Long.valueOf(ImapOtpFetcher.REFUSED_RETRY_DELAY_MS),
                ImapOtpFetcher.reconnectDelay(ImapOtpFetcher.ConnectionFailure.REFUSED, 1, 15_000));
        // 2 本目のあとは繋ぎ直さない。
        assertNull(ImapOtpFetcher.reconnectDelay(ImapOtpFetcher.ConnectionFailure.TRANSPORT, 2, 15_000));
        assertNull(ImapOtpFetcher.reconnectDelay(ImapOtpFetcher.ConnectionFailure.REFUSED, 2, 15_000));
        // 待ったあとに試す時間が残らないなら、待たずに諦める。
        assertNull(ImapOtpFetcher.reconnectDelay(ImapOtpFetcher.ConnectionFailure.REFUSED, 1,
                ImapOtpFetcher.REFUSED_RETRY_DELAY_MS + 1_000));
        assertNull(ImapOtpFetcher.reconnectDelay(ImapOtpFetcher.ConnectionFailure.TRANSPORT, 1, 1_000));
    }

    /** 読み取りの待ちは、無応答の上限と期限の早い方。setSoTimeout(0) (無期限) にはならない。 */
    @Test
    public void readWait_isBoundedByDeadline() {
        assertEquals(4_000, ImapOtpFetcher.readWaitMs(4_000, 0, 1_000));
        assertEquals(3_000, ImapOtpFetcher.readWaitMs(18_000, 4_000, 1_000));
        // 期限を過ぎていても 0.5 秒は待つ。
        assertEquals(500, ImapOtpFetcher.readWaitMs(18_000, 1_000, 5_000));
        assertEquals(1, ImapOtpFetcher.readWaitMs(0, 0, 0));
    }

    /**
     * 取りやめ (見張りの時間切れ・仕事の停止) は、別のスレッドから今の接続を閉じ、繋ぎ直さない。
     * 黙ったままのサーバ (TLS の握手に答えない) に繋がせ、途中で取りやめる。
     */
    @Test(timeout = 10_000)
    public void abort_closesTheLiveConnection_andDoesNotReconnect() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<java.net.Socket> held = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        held.add(server.accept());
                        accepted.incrementAndGet();
                    }
                } catch (java.io.IOException ignored) {
                    // サーバを閉じた
                }
            });
            acceptor.start();
            ImapOtpFetcher.Progress progress = new ImapOtpFetcher.Progress();
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                }
                progress.abort();
            }).start();
            long started = System.currentTimeMillis();
            try {
                new ImapOtpFetcher("127.0.0.1", server.getLocalPort(), progress)
                        .fetchOtpMail("u", "p", 30, System.currentTimeMillis());
                org.junit.Assert.fail("expected the fetch to be aborted");
            } catch (ImapOtpFetcher.ImapException e) {
                assertEquals("aborted", e.getMessage());
            }
            // 握手の無応答の上限 (4 秒) を待たずに戻る。
            long elapsed = System.currentTimeMillis() - started;
            org.junit.Assert.assertTrue("elapsed " + elapsed, elapsed < ImapOtpFetcher.SETUP_STALL_LIMIT_MS);
            assertEquals(1, accepted.get());
            // 後ろに時間 (:f<取りやめた時点>) が付く。中身は実行の速さ次第なので形だけ確かめる。
            String detail = progress.detail(new ImapOtpFetcher.ImapException("aborted"));
            org.junit.Assert.assertTrue(detail, detail.matches("^cancelled(:f[0-9]+\\.[0-9])?$"));
            for (java.net.Socket s : held) s.close();
        }
    }

    /** 使ったメールの削除は、古い方へ CLEANUP_UID_CHUNK ずつ、UID 1 まで隙間も重なりもなく覆う (上限なし)。 */
    @Test
    public void cleanupChunks_coverEveryOlderUidDownToOne() {
        int below = 1_000;
        int expected = below - 1;
        int chunks = 0;
        for (int[] r = ImapOtpFetcher.cleanupChunkBelow(below); r != null; r = ImapOtpFetcher.cleanupChunkBelow(r[0])) {
            assertEquals(expected, r[1]);
            assertEquals(true, r[1] - r[0] + 1 <= ImapOtpFetcher.CLEANUP_UID_CHUNK);
            expected = r[0] - 1;
            chunks++;
        }
        assertEquals(0, expected);
        assertEquals(5, chunks);
        assertNull(ImapOtpFetcher.cleanupChunkBelow(1));
        assertEquals(Arrays.asList(1, 1), Arrays.asList(ImapOtpFetcher.cleanupChunkBelow(2)[0], ImapOtpFetcher.cleanupChunkBelow(2)[1]));
    }

    /** 同じ送信元でも、本文に OTP のコードが無いメールは消さない。 */
    @Test
    public void otpMailsWithCode_keepsMailsWithoutACode() {
        java.util.Map<Integer, String> texts = new java.util.HashMap<>();
        texts.put(7, "Your one-time password is below.\r\n\r\n12345678\r\n");
        texts.put(3, "OTP の登録を受け付けました。\r\n受付番号 2026-10-30\r\n");
        texts.put(5, "\r\n  654321  \r\n");
        assertEquals(Arrays.asList(5, 7), ImapOtpFetcher.otpMailsWithCode(texts));
    }
}
