package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Dovecot の形の FETCH の応答 (src/test/resources/imap-fetch-*.txt、iOS の uoa-appTests/Fixtures と同じもの) を、
 * 取得と同じ読み方で読む。改行は LF で保存してあり (git の改行の扱いに左右されないように)、ここで IMAP の CRLF に戻す。
 * リテラルの長さは CRLF で数えてある。
 */
public class ImapFetchFixtureTest {
    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = ImapFetchFixtureTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') out.write('\r');
                out.write(b);
            }
            return out.toByteArray();
        }
    }

    /** 完了 (タグ付きの行) までの応答。完了の行は最後に入れる。 */
    private static List<ImapOtpFetcher.Response> readAll(byte[] data) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        List<ImapOtpFetcher.Response> out = new ArrayList<>();
        while (in.available() > 0) out.add(ImapOtpFetcher.readResponse(in));
        return out;
    }

    private static List<ImapOtpFetcher.FetchedMail> mails(String name) throws IOException {
        return ImapOtpFetcher.parseFetchedMails(readAll(fixture(name)));
    }

    private static long date(String text) {
        Long parsed = ImapOtpFetcher.parseInternalDate(Collections.singletonList("INTERNALDATE \"" + text + "\""));
        assertNotNull(parsed);
        return parsed;
    }

    private static List<Integer> uids(List<ImapOtpFetcher.FetchedMail> mails) {
        List<Integer> out = new ArrayList<>();
        for (ImapOtpFetcher.FetchedMail m : mails) out.add(m.uid);
        return out;
    }

    @Test
    public void headerScan_parsesDovecotFetchResponses() throws IOException {
        List<ImapOtpFetcher.Response> responses = readAll(fixture("imap-fetch-headers.txt"));
        // FLAGS だけの FETCH・4 通の FETCH・EXISTS・完了。
        assertEquals(7, responses.size());
        assertEquals("a10 OK Fetch completed (0.002 + 0.000 + 0.001 secs).", responses.get(6).lines.get(0));
        List<ImapOtpFetcher.FetchedMail> mails = ImapOtpFetcher.parseFetchedMails(responses);
        // UID の無い FETCH (勝手に届いた FLAGS の変化) は捨てる。
        assertEquals(Arrays.asList(13030, 13038, 13041, 13043), uids(mails));
        Map<Integer, ImapOtpFetcher.FetchedMail> byUid = new HashMap<>();
        for (ImapOtpFetcher.FetchedMail m : mails) byUid.put(m.uid, m);
        assertEquals(Long.valueOf(date("25-Oct-2026 14:55:22 +0900")), byUid.get(13043).arrivedAt);
        // 折り返した From も読める。表示名に送信元を書いただけのメールは OTP メールではない (F20)。
        assertTrue(ImapOtpFetcher.isOtpSender(byUid.get(13043).fromHeader));
        assertTrue(ImapOtpFetcher.isOtpSender(byUid.get(13038).fromHeader));
        assertFalse(ImapOtpFetcher.isOtpSender(byUid.get(13041).fromHeader));
        assertFalse(ImapOtpFetcher.isOtpSender(byUid.get(13030).fromHeader));
    }

    /** 依頼の時刻以降に届いたか、受信箱を開いたあとに届いた OTP メールのうち、一番新しいもの。 */
    @Test
    public void pickOtpMail_usesArrivalTimeAndUid() throws IOException {
        List<ImapOtpFetcher.FetchedMail> mails = mails("imap-fetch-headers.txt");
        HashSet<Integer> none = new HashSet<>();
        // 依頼 (PC の拡張が 5 秒前にずらした時刻) のあとに届いた本物。偽の差出人 (13041) と前回のメール (13038) は使わない。
        assertEquals(13043, ImapOtpFetcher.pickOtpMail(mails, date("25-Oct-2026 14:55:17 +0900"), 13045, none).uid);
        // INTERNALDATE は秒精度。同じ秒の中の依頼でも取りこぼさない。
        assertEquals(13043, ImapOtpFetcher.pickOtpMail(mails, date("25-Oct-2026 14:55:22 +0900") + 700, 13045, none).uid);
        // 依頼より前に届いたものしか無ければ待つ。
        assertNull(ImapOtpFetcher.pickOtpMail(mails, date("25-Oct-2026 14:55:30 +0900"), 13045, none));
        // 受信箱を開いたあとに届いたものは、時計がずれていても今回のもの。
        assertEquals(13043, ImapOtpFetcher.pickOtpMail(mails, date("25-Oct-2026 14:55:30 +0900"), 13042, none).uid);
        // 「既に送信しました」(10 分前) なら前回のメールも条件に入る。一番新しいものを使う。
        assertEquals(13043, ImapOtpFetcher.pickOtpMail(mails, date("25-Oct-2026 14:45:17 +0900"), 13045, none).uid);
        // 一番新しいメールのコードが読めなければ、古い方 (使えなくなったコード) には戻らず、より新しいものを待つ。
        assertNull(ImapOtpFetcher.pickOtpMail(mails, date("25-Oct-2026 14:45:17 +0900"), 13045,
                new HashSet<>(Collections.singletonList(13043))));
        assertEquals(13043, ImapOtpFetcher.pickOtpMail(mails, date("25-Oct-2026 14:45:17 +0900"), 13045,
                new HashSet<>(Collections.singletonList(13038))).uid);
    }

    /** 直近の範囲のメールが全部依頼のあとに届いていたら、その手前も見る (「既に送信しました」で 20 通を超えて届いていた場合)。 */
    @Test
    public void needsOlderScan_onlyWhenTheWindowMayNotReachTheRequest() throws IOException {
        List<ImapOtpFetcher.FetchedMail> mails = mails("imap-fetch-headers.txt");
        // 一番古い 13030 は 14:40:00 着。
        assertFalse(ImapOtpFetcher.needsOlderScan(mails, date("25-Oct-2026 14:55:17 +0900")));
        assertFalse(ImapOtpFetcher.needsOlderScan(mails, date("25-Oct-2026 14:40:01 +0900")));
        assertTrue(ImapOtpFetcher.needsOlderScan(mails, date("25-Oct-2026 14:40:00 +0900")));
        assertTrue(ImapOtpFetcher.needsOlderScan(mails, date("25-Oct-2026 14:35:17 +0900")));
        assertTrue(ImapOtpFetcher.needsOlderScan(new ArrayList<>(), date("25-Oct-2026 14:55:17 +0900")));
        assertEquals(200, ImapOtpFetcher.RECENT_UID_WINDOW + ImapOtpFetcher.OLDER_UID_WINDOW);
    }

    /** 新着の確認で期限が来て、読み取りが切られただけなら、メールが来なかった (mail-timeout) として返す。 */
    @Test
    public void endedByDeadline_onlyWhileWaitingForNewMail() {
        java.net.SocketTimeoutException stall = new java.net.SocketTimeoutException("Read timed out");
        long deadline = 100_000;
        long late = deadline - 1_200;
        assertTrue(ImapOtpFetcher.endedByDeadline(stall, ImapOtpFetcher.Progress.Stage.RECHECK, deadline, late, deadline + 100));
        // 期限の前の無応答、通信断、メールを読む途中 (SCAN / FETCH) の無応答は、サーバが答えなかった。
        assertFalse(ImapOtpFetcher.endedByDeadline(stall, ImapOtpFetcher.Progress.Stage.RECHECK, deadline, late, deadline - 100));
        assertFalse(ImapOtpFetcher.endedByDeadline(new IOException("connectionFailed"),
                ImapOtpFetcher.Progress.Stage.RECHECK, deadline, late, deadline + 100));
        assertFalse(ImapOtpFetcher.endedByDeadline(stall, ImapOtpFetcher.Progress.Stage.SCAN, deadline, late, deadline + 100));
        assertFalse(ImapOtpFetcher.endedByDeadline(stall, ImapOtpFetcher.Progress.Stage.FETCH, deadline, late, deadline + 100));
        // 期限よりずっと前に始めた確認に、期限まで答えが無かった: サーバが止まった (imap-error のまま)。
        assertFalse(ImapOtpFetcher.endedByDeadline(stall, ImapOtpFetcher.Progress.Stage.RECHECK, deadline,
                deadline - 10_000, deadline + 100));
    }

    @Test
    public void textFetch_extractsTheCodeFromIso2022JpBody() throws IOException {
        List<ImapOtpFetcher.FetchedMail> mails = mails("imap-fetch-text.txt");
        assertEquals(1, mails.size());
        assertEquals(13043, mails.get(0).uid);
        assertEquals("482913", ImapOtpFetcher.extractCode(mails.get(0).text));
    }

    /** リテラルの中の CRLF で区切らない。長さを知らせる行の {n} だけを読む。 */
    @Test
    public void literalLength_onlyAtTheEndOfTheLine() {
        assertEquals(Integer.valueOf(104), ImapOtpFetcher.literalLength("* 1 FETCH (UID 2 BODY[TEXT] {104}"));
        assertNull(ImapOtpFetcher.literalLength("* OK [ALERT] {not a literal}"));
        assertNull(ImapOtpFetcher.literalLength("a10 OK Fetch completed."));
    }

    /** 新着の確認 (UID FETCH 13045:*) で新しいメールが無いと、サーバは最大の UID のメールを返す。範囲の外は使わない。 */
    @Test
    public void recheck_ignoresTheHighestUidOutsideTheRange() throws IOException {
        List<ImapOtpFetcher.FetchedMail> fetched = mails("imap-fetch-recheck.txt");
        assertEquals(Collections.singletonList(13043), uids(fetched));
        List<ImapOtpFetcher.FetchedMail> inRange = ImapOtpFetcher.mailsInRange(fetched, 13045, null);
        assertTrue(inRange.isEmpty());
        assertEquals(13045, ImapOtpFetcher.nextUnseenUid(inRange, 13045, 13045));
        // 最初の範囲を見たあとは、見たものの次と UIDNEXT の大きい方から見る。
        List<ImapOtpFetcher.FetchedMail> scanned = mails("imap-fetch-headers.txt");
        assertEquals(13045, ImapOtpFetcher.nextUnseenUid(scanned, 13025, 13045));
        assertEquals(13044, ImapOtpFetcher.nextUnseenUid(scanned, 13025, null));
        assertEquals(13025, ImapOtpFetcher.nextUnseenUid(new ArrayList<>(), 13025, null));
    }
}
