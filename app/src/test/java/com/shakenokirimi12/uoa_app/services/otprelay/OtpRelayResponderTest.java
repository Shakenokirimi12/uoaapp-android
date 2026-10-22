package com.shakenokirimi12.uoa_app.services.otprelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shakenokirimi12.uoa_app.services.NetworkClient;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;
import com.shakenokirimi12.uoa_app.testing.LoopbackHttpServer;
import com.shakenokirimi12.uoa_app.testing.LoopbackHttpServer.Reply;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 依頼への応答 (iOS の fetchAndRespond / respondExpired と同じ結果になるか) と、中継サーバへ送る形。 */
public class OtpRelayResponderTest {
    private static final String K = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final String RESPOND_TOKEN = "G5OzomdYgm_Yvhu8edKe3oOnlxQjj5tFLOYpMhRRXCE";
    private static final long NOW = 1_800_000_000_000L;

    private LoopbackHttpServer server;
    private int responseStatus = 204;
    private OtpRelayApi api;

    private Exception mailError;
    private final List<Long> mailSince = new ArrayList<>();
    private final List<Integer> deleted = new ArrayList<>();
    private String username = "s1234567";
    private final List<String> lastResults = new ArrayList<>();
    /** メールを読んでいる途中に割り込ませる処理 (見張りの期限が来た、など)。 */
    private Runnable duringFetch;

    private final OtpRelayPairing pairing = new OtpRelayPairing("PAIRING0000000000000000", K, "Mac の Chrome", NOW);
    private final OtpRelayRequest request = new OtpRelayRequest("PAIRING0000000000000000", "REQ0000000000000000000",
            NOW - 5_000, NOW + 115_000);

    @Before
    public void setUp() throws Exception {
        server = new LoopbackHttpServer(req -> new Reply(req.path.endsWith("/response") ? responseStatus : 201, "{}"));
        api = new OtpRelayApi("http://127.0.0.1:" + server.port(), NetworkClient.getNoRedirectClient());
    }

    @After
    public void tearDown() throws Exception {
        server.close();
    }

    private OtpRelayResponder responder() {
        return new OtpRelayResponder(api,
                new OtpRelayResponder.MailSource() {
                    @NonNull
                    @Override
                    public ImapOtpFetcher.OtpMail fetch(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                                                        long since, @NonNull ImapOtpFetcher.Progress progress)
                            throws Exception {
                        mailSince.add(since);
                        if (duringFetch != null) duringFetch.run();
                        if (mailError != null) throw mailError;
                        return newMail("048213", 42);
                    }

                    @Override
                    public void deleteUsed(@NonNull ImapOtpFetcher.OtpMail mail) {
                        deleted.add(mail.uid);
                    }
                },
                new OtpRelayResponder.Credentials() {
                    @NonNull @Override public String username() { return username; }
                    @NonNull @Override public String password() { return "pw"; }
                },
                (ok, summary, detail) -> lastResults.add(ok + ":" + summary),
                () -> NOW);
    }

    private OtpRelayResponder.Outcome respond(OtpRelayPairing p) {
        return responder().fetchAndRespond(p, request, NOW, 18_000, new OtpRelayResponder.Attempt());
    }

    private static ImapOtpFetcher.OtpMail newMail(String code, int uid) throws Exception {
        java.lang.reflect.Constructor<ImapOtpFetcher.OtpMail> c =
                ImapOtpFetcher.OtpMail.class.getDeclaredConstructor(String.class, int.class);
        c.setAccessible(true);
        return c.newInstance(code, uid);
    }

    private JsonObject lastPostBody() {
        LoopbackHttpServer.Recorded r = server.first("/v1/requests/" + request.requestId + "/response");
        return JsonParser.parseString(r.body).getAsJsonObject();
    }

    @Test
    public void autoSuccess_postsSealedBox_thenDeletesTheUsedMail() {
        OtpRelayResponder.Outcome outcome = respond(pairing);

        assertEquals(OtpRelayResponder.Kind.SENT, outcome.kind);
        LoopbackHttpServer.Recorded post = server.first("/v1/requests/" + request.requestId + "/response");
        assertEquals("Bearer " + RESPOND_TOKEN, post.header("Authorization"));
        JsonObject body = lastPostBody();
        assertEquals("approved", body.get("result").getAsString());
        assertEquals(pairing.pairingId, body.get("pairingId").getAsString());
        assertEquals(12 + 17 + 16, OtpRelayCrypto.decode(body.get("box").getAsString()).length);
        // 依頼時刻 (5 秒前) は信用できる範囲なので、そのまま探索の起点になる。
        assertEquals(Long.valueOf(NOW - 5_000), mailSince.get(0));
        assertEquals(Integer.valueOf(42), deleted.get(0));
        assertEquals("true:PC に OTP を送りました", lastResults.get(0));
    }

    @Test
    public void expired410_keepsTheMail_andIsNotReportedAsSent() {
        responseStatus = 410;
        OtpRelayResponder.Outcome outcome = respond(pairing);
        assertEquals(OtpRelayResponder.Kind.FAILED, outcome.kind);
        assertEquals("expired", outcome.reason);
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void conflict409_meansAnEarlierRunAnswered_andChangesNothing() {
        // WorkManager のやり直しで、前の実行が既に答えていた。前の結果 (送った) を上書きしない。
        responseStatus = 409;
        assertEquals(OtpRelayResponder.ALREADY_ANSWERED, respond(pairing).reason);
        assertTrue(deleted.isEmpty());
        assertTrue(lastResults.isEmpty());

        mailError = new ImapOtpFetcher.ImapException("mailNotFound");
        assertEquals(OtpRelayResponder.ALREADY_ANSWERED, respond(pairing).reason);
        assertTrue(lastResults.isEmpty());
        assertEquals(OtpRelayResponder.ExpiryResult.ALREADY_ANSWERED,
                responder().respondExpired(pairing, request, new OtpRelayResponder.Attempt(), 2_000));
        assertTrue(lastResults.isEmpty());
    }

    @Test
    public void serverError_isRelayUnreachable_andKeepsTheMail() {
        responseStatus = 500;
        OtpRelayResponder.Outcome outcome = respond(pairing);
        assertEquals("relay-unreachable", outcome.reason);
        // 500 は数字のまま残す (0 = 通信できなかった、のときだけ理由の文にする)。
        assertTrue(deleted.isEmpty());
        assertEquals("false:中継サーバの応答: 500", lastResults.get(0));
        // 返せなかった approved の 1 件だけ。失敗を重ねて送らない。
        assertEquals(1, server.requests().size());
    }

    @Test
    public void mailTimeout_isReportedToTheRelay_withDetail() {
        mailError = new ImapOtpFetcher.ImapException("mailNotFound");
        OtpRelayResponder.Outcome outcome = respond(pairing);
        assertEquals(OtpRelayResponder.Kind.FAILED, outcome.kind);
        assertEquals("mail-timeout", outcome.reason);
        JsonObject body = lastPostBody();
        assertEquals("failed", body.get("result").getAsString());
        assertEquals("mail-timeout", body.get("error").getAsString());
        // 中継サーバの形 (^[a-z0-9][a-z0-9._:-]{0,79}$) に合う detail を付ける。
        assertTrue(body.get("detail").getAsString().matches("^[a-z0-9][a-z0-9._:-]{0,79}$"));
    }

    @Test
    public void loginFailure_isReportedAsLoginFailed() {
        mailError = new ImapOtpFetcher.ImapException("commandFailed(a1): a1 NO [AUTHENTICATIONFAILED] Authenticat");
        OtpRelayResponder.Outcome outcome = respond(pairing);
        assertEquals(OtpRelayResponder.Kind.FAILED, outcome.kind);
        assertEquals("login-failed", outcome.reason);
        JsonObject body = lastPostBody();
        assertEquals("failed", body.get("result").getAsString());
        assertEquals("login-failed", body.get("error").getAsString());
        // 差し替えた MailSource は段階を進めないので connect のまま。
        assertEquals("connect-no:authenticationfailed", body.get("detail").getAsString());
        assertFalse(body.has("box"));
    }

    @Test
    public void transientLoginNo_isImapError_notLoginFailed() {
        // 同時接続数の超過など。パスワードは正しいので「ID かパスワードが違う」と言わない。
        assertEquals("imap-error", OtpRelayResponder.failureCode(
                new ImapOtpFetcher.ImapException("commandFailed(a1): a1 NO [UNAVAILABLE] Temporary failure")));
        assertEquals("imap-error", OtpRelayResponder.failureCode(
                new ImapOtpFetcher.ImapException("commandFailed(a1): a1 NO [INUSE] Mailbox in use")));
    }

    @Test
    public void failureCodes_matchIos() {
        assertEquals("code-not-found", OtpRelayResponder.failureCode(new ImapOtpFetcher.ImapException("codeNotFound")));
        assertEquals("imap-error", OtpRelayResponder.failureCode(new ImapOtpFetcher.ImapException("commandFailed(a3): x")));
        assertEquals("imap-error", OtpRelayResponder.failureCode(new java.io.IOException("connectionFailed")));
    }

    @Test
    public void noCredentials_isReportedWithoutReadingMail() {
        username = "";
        OtpRelayResponder.Outcome outcome = respond(pairing);
        assertEquals("no-credentials", outcome.reason);
        assertTrue(mailSince.isEmpty());
        assertEquals("no-credentials", lastPostBody().get("error").getAsString());
    }

    @Test
    public void pairingWithoutConsent_rejectsWithoutReadingMail() {
        // 同意を記録する前に作った連携 (以前の版の保存データ)。確認が済むまでメールを読まない。
        OtpRelayPairing legacy = new com.google.gson.Gson().fromJson(
                "{\"pairingId\":\"PAIRING0000000000000000\",\"key\":\"" + K + "\",\"browserName\":\"B\","
                        + "\"pairedAt\":1,\"autoApprove\":true}", OtpRelayPairing.class);
        OtpRelayResponder.Outcome outcome = respond(legacy);
        assertEquals(OtpRelayResponder.CONSENT_REQUIRED, outcome.reason);
        assertTrue(mailSince.isEmpty());
        JsonObject body = lastPostBody();
        assertEquals("rejected", body.get("result").getAsString());
        assertFalse(body.has("box"));
        assertEquals(1, server.requests().size());
    }

    @Test
    public void expiryAfterTheAnswerStarted_sendsNothing() {
        OtpRelayResponder.Attempt attempt = new OtpRelayResponder.Attempt();
        OtpRelayResponder r = responder();
        assertEquals(OtpRelayResponder.Kind.SENT, r.fetchAndRespond(pairing, request, NOW, 18_000, attempt).kind);
        assertEquals(OtpRelayResponder.ExpiryResult.NOT_CLAIMED, r.respondExpired(pairing, request, attempt, 2_000));
        assertEquals(1, server.requests().size());
    }

    @Test
    public void expiryDuringTheFetch_answersOnce_andKeepsTheMail() {
        OtpRelayResponder.Attempt attempt = new OtpRelayResponder.Attempt();
        OtpRelayResponder r = responder();
        // メールを読んでいる間に見張りの期限が来た (読み終わるのは期限の後)。
        List<OtpRelayResponder.ExpiryResult> expiry = new ArrayList<>();
        duringFetch = () -> expiry.add(r.respondExpired(pairing, request, attempt, 2_000));
        OtpRelayResponder.Outcome outcome = r.fetchAndRespond(pairing, request, NOW, 18_000, attempt);

        assertEquals(OtpRelayResponder.ExpiryResult.ACCEPTED, expiry.get(0));
        assertEquals(OtpRelayResponder.DEADLINE_PASSED, outcome.reason);
        // 中継サーバに答えたのは見張りの失敗の 1 件だけ。コードは送らず、メールも消さない (PC で手入力に使う)。
        assertEquals(1, server.requests().size());
        JsonObject body = lastPostBody();
        assertEquals("failed", body.get("result").getAsString());
        assertEquals("imap-error", body.get("error").getAsString());
        assertEquals("expired:connect", body.get("detail").getAsString());
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void expiryWhileWaitingForMail_isMailTimeout() {
        assertEquals("mail-timeout", OtpRelayResponder.expiredReason(ImapOtpFetcher.Progress.Stage.IDLE));
        assertEquals("mail-timeout", OtpRelayResponder.expiredReason(ImapOtpFetcher.Progress.Stage.SEARCH));
        assertEquals("imap-error", OtpRelayResponder.expiredReason(ImapOtpFetcher.Progress.Stage.SELECT));
        assertEquals("expired:select:r2", OtpRelayResponder.expiredDetail(ImapOtpFetcher.Progress.Stage.SELECT, 2));
    }

    @Test
    public void expiry_forPairingWithoutConsent_sendsNothing() {
        OtpRelayPairing legacy = new OtpRelayPairing("PAIRING0000000000000000", K, "B", NOW);
        legacy.mailConsentAt = null;
        assertEquals(OtpRelayResponder.ExpiryResult.NOT_CLAIMED,
                responder().respondExpired(legacy, request, new OtpRelayResponder.Attempt(), 2_000));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    public void register_sendsFcmPlatform_withoutAuthorization() throws Exception {
        api.register(pairing.pairingId, OtpRelayCrypto.keys(K), "fcm-token", "Pixel 9", "Mac の Chrome", true);
        LoopbackHttpServer.Recorded r = server.first("/v1/pairings");
        assertNull(r.header("Authorization"));
        JsonObject body = JsonParser.parseString(r.body).getAsJsonObject();
        assertEquals("fcm", body.get("platform").getAsString());
        assertEquals("fcm-token", body.get("fcmToken").getAsString());
        assertEquals("Pixel 9", body.get("deviceName").getAsString());
        assertEquals("Mac の Chrome", body.get("browserLabel").getAsString());
        assertTrue(body.get("updateOnly").getAsBoolean());
        assertEquals("bbEmF7m--IEXYyof_ClXmkIOUhaVcBup4ZYLotntVWo", body.get("requestToken").getAsString());
        assertEquals(RESPOND_TOKEN, body.get("respondToken").getAsString());
    }

    @Test
    public void parsesTheFcmPayload() {
        OtpRelayRequest r = OtpRelayRequest.parse(
                "{\"v\":1,\"pairingId\":\"AAAA\",\"requestId\":\"BBBB\",\"requestedAt\":1800000000000,\"expiresAt\":1800000120000}");
        assertEquals("AAAA", r.pairingId);
        assertEquals("BBBB", r.requestId);
        assertEquals(1_800_000_120_000L, r.expiresAt);
        assertNull(OtpRelayRequest.parse("{\"v\":2,\"pairingId\":\"A\",\"requestId\":\"B\",\"requestedAt\":1,\"expiresAt\":2}"));
        assertNull(OtpRelayRequest.parse("{\"pairingId\":\"A\",\"requestId\":\"../x\",\"requestedAt\":1,\"expiresAt\":2}"));
        assertNull(OtpRelayRequest.parse("not json"));
    }

}
