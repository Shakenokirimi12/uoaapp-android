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

/** 依頼への応答 (iOS の fetchAndRespond / approve と同じ結果になるか) と、中継サーバへ送る形。 */
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
                                                        long since) throws Exception {
                        mailSince.add(since);
                        if (mailError != null) throw mailError;
                        return newMail("048213", 42);
                    }

                    @Override
                    public void deleteUsed(@NonNull String uid, @NonNull String pass, int upToUid) {
                        deleted.add(upToUid);
                    }
                },
                new OtpRelayResponder.Credentials() {
                    @NonNull @Override public String username() { return username; }
                    @NonNull @Override public String password() { return "pw"; }
                },
                (ok, summary, detail) -> lastResults.add(ok + ":" + summary),
                () -> NOW);
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
        OtpRelayResponder.Outcome outcome = responder().fetchAndRespond(pairing, request, NOW, 18_000);

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
        assertEquals("true:PC にコードを送りました", lastResults.get(0));
    }

    @Test
    public void alreadyAnswered_409_stillDeletesTheMail() {
        responseStatus = 409;
        assertEquals(OtpRelayResponder.Kind.ALREADY_ANSWERED,
                responder().fetchAndRespond(pairing, request, NOW, 18_000).kind);
        assertEquals(1, deleted.size());
    }

    @Test
    public void serverError_needsApproval_andKeepsTheMail() {
        responseStatus = 500;
        assertEquals(OtpRelayResponder.Kind.NEEDS_APPROVAL,
                responder().fetchAndRespond(pairing, request, NOW, 18_000).kind);
        assertTrue(deleted.isEmpty());
        assertEquals("false:中継サーバの応答: 500", lastResults.get(0));
    }

    @Test
    public void mailTimeout_needsApproval_withoutTellingTheServer() {
        mailError = new ImapOtpFetcher.ImapException("mailNotFound");
        assertEquals(OtpRelayResponder.Kind.NEEDS_APPROVAL,
                responder().fetchAndRespond(pairing, request, NOW, 18_000).kind);
        assertTrue(server.requests().isEmpty());
    }

    @Test
    public void loginFailure_isReportedAsLoginFailed() {
        mailError = new ImapOtpFetcher.ImapException("commandFailed(a1): a1 NO [AUTHENTICATIONFAILED]");
        OtpRelayResponder.Outcome outcome = responder().fetchAndRespond(pairing, request, NOW, 18_000);
        assertEquals(OtpRelayResponder.Kind.FAILED, outcome.kind);
        assertEquals("login-failed", outcome.reason);
        JsonObject body = lastPostBody();
        assertEquals("failed", body.get("result").getAsString());
        assertEquals("login-failed", body.get("error").getAsString());
        assertFalse(body.has("box"));
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
        OtpRelayResponder.Outcome outcome = responder().fetchAndRespond(pairing, request, NOW, 18_000);
        assertEquals("no-credentials", outcome.reason);
        assertTrue(mailSince.isEmpty());
        assertEquals("no-credentials", lastPostBody().get("error").getAsString());
    }

    @Test
    public void approve_afterExpiry_doesNothing() {
        OtpRelayRequest expired = new OtpRelayRequest(request.pairingId, request.requestId, NOW - 200_000, NOW - 1);
        assertEquals("expired", responder().approve(pairing, expired).reason);
        assertTrue(mailSince.isEmpty());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    public void approve_whenMailNeverArrives_reportsMailTimeout() {
        mailError = new ImapOtpFetcher.ImapException("mailNotFound");
        OtpRelayResponder.Outcome outcome = responder().approve(pairing, request);
        assertEquals("mail-timeout", outcome.reason);
        assertEquals("mail-timeout", lastPostBody().get("error").getAsString());
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
