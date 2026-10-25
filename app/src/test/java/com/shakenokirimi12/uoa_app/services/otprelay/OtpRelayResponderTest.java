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

    /** この端末の方式と、ID ごとのセットアップキー (RFC 6238 の SHA1 の鍵を s1234567 だけが持つ)。 */
    private boolean authenticator = false;
    private long clockNow = NOW;
    private final List<Long> slept = new ArrayList<>();
    /** アプリのログインと共有する窓の台帳 (テストではメモリ)。 */
    private final com.shakenokirimi12.uoa_app.services.idp.TotpStepLedger ledger =
            com.shakenokirimi12.uoa_app.services.idp.TotpStepLedger.inMemory();
    private static final String RFC_OTPAUTH = "otpauth://totp/u-aizu:s1234567?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

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
                () -> clockNow,
                new OtpRelayResponder.TotpSource() {
                    @Override public boolean authenticatorMethod() { return authenticator; }
                    @Override public com.shakenokirimi12.uoa_app.services.idp.Totp.Params keyFor(@NonNull String uid) {
                        return uid.equals("s1234567") ? com.shakenokirimi12.uoa_app.services.idp.Totp.parse(RFC_OTPAUTH) : null;
                    }
                    @Override public boolean claimStep(long step) { return ledger.claim(step); }
                },
                ms -> slept.add(ms));
    }

    private static final String TOTP_REQUEST_ID = "wMHCw8TFxsfIycrLBdeCIA";
    private static final String MAIL_REQUEST_ID = "wMHCw8TFxsfIycrL7lC2sA";

    private OtpRelayResponder.Outcome respondTo(String requestId) {
        OtpRelayRequest r = new OtpRelayRequest(pairing.pairingId, requestId, NOW - 5_000, NOW + 115_000);
        return responder().fetchAndRespond(pairing, r, NOW, 18_000, new OtpRelayResponder.Attempt());
    }

    private JsonObject postBodyFor(String requestId) {
        return JsonParser.parseString(server.first("/v1/requests/" + requestId + "/response").body).getAsJsonObject();
    }

    /** box を開いた中身 (JSON の文字列)。 */
    private static String openBox(String box, String requestId) throws Exception {
        byte[] bytes = OtpRelayCrypto.decode(box);
        byte[] encKey = OtpRelayCrypto.keys(K).encKey;
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(encKey, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, bytes, 0, 12));
        cipher.updateAAD(requestId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String plain = new String(cipher.doFinal(bytes, 12, bytes.length - 12), java.nio.charset.StandardCharsets.UTF_8);
        // 方式つきの box は空白で 96 バイトに埋めてある。
        assertEquals(96, plain.length());
        return plain.trim();
    }

    @Test
    public void routesByPageAndMethod() {
        com.shakenokirimi12.uoa_app.services.idp.Totp.Params key = com.shakenokirimi12.uoa_app.services.idp.Totp.parse(RFC_OTPAUTH);
        assertEquals(OtpRelayResponder.Route.Kind.TOTP, OtpRelayResponder.route("totp", true, key).kind);
        OtpRelayResponder.Route noKey = OtpRelayResponder.route("totp", true, null);
        assertEquals(OtpRelayResponder.NO_TOTP_KEY, noKey.reason);
        OtpRelayResponder.Route mailPhone = OtpRelayResponder.route("totp", false, null);
        assertEquals(OtpRelayResponder.METHOD_MISMATCH, mailPhone.reason);
        assertEquals("mail", mailPhone.method);
        OtpRelayResponder.Route totpPhone = OtpRelayResponder.route("mail", true, key);
        assertEquals(OtpRelayResponder.METHOD_MISMATCH, totpPhone.reason);
        assertEquals("totp", totpPhone.method);
        assertEquals(OtpRelayResponder.Route.Kind.MAIL, OtpRelayResponder.route("mail", false, null).kind);
        // 目印の無い依頼 (古い拡張) は、認証アプリ方式でも以前と同じくメールを読む。
        assertEquals(OtpRelayResponder.Route.Kind.MAIL, OtpRelayResponder.route(null, true, key).kind);
    }

    /** 認証アプリ方式の画面: 今の ID の鍵でコードを作って返す。メールは読まない。 */
    @Test
    public void totpPage_sendsTheCodeFromTheHeldKey_withoutReadingMail() throws Exception {
        authenticator = true;
        // RFC 6238 付録 B: 1234567890 秒のコードは 89005924 (6 桁なら 005924)。周期の始めなので待たない。
        clockNow = 1_234_567_890_000L;
        OtpRelayResponder.Outcome outcome = respondTo(TOTP_REQUEST_ID);

        assertEquals(OtpRelayResponder.Kind.SENT, outcome.kind);
        assertTrue(mailSince.isEmpty());
        assertTrue(slept.isEmpty());
        JsonObject body = postBodyFor(TOTP_REQUEST_ID);
        assertEquals("approved", body.get("result").getAsString());
        assertEquals("{\"code\":\"005924\",\"method\":\"totp\"}", openBox(body.get("box").getAsString(), TOTP_REQUEST_ID));
    }

    @Test
    public void totpPage_nearTheEndOfTheStep_waitsForTheNextCode() throws Exception {
        authenticator = true;
        clockNow = 1_234_567_918_000L; // 周期の残り 2 秒
        respondTo(TOTP_REQUEST_ID);
        assertEquals(Long.valueOf(2_300), slept.get(0));
        // 次の窓のコード (1234567920 秒の窓) を返し、その窓を使用済みにする。
        com.shakenokirimi12.uoa_app.services.idp.Totp.Params key = com.shakenokirimi12.uoa_app.services.idp.Totp.parse(RFC_OTPAUTH);
        long next = com.shakenokirimi12.uoa_app.services.idp.Totp.step(key, 1_234_567_920_000L);
        String expected = com.shakenokirimi12.uoa_app.services.idp.Totp.codeAtStep(key, next);
        assertEquals("{\"code\":\"" + expected + "\",\"method\":\"totp\"}",
                openBox(postBodyFor(TOTP_REQUEST_ID).get("box").getAsString(), TOTP_REQUEST_ID));
        assertFalse(ledger.claim(next));
    }

    /** アプリのログインが今の窓を使っていたら次の窓を待つ。待てない (15 秒を超える) ときは断る。 */
    @Test
    public void totpPage_whenTheStepWasUsedByALogin_waitsOrRefuses() throws Exception {
        authenticator = true;
        clockNow = 1_234_567_890_000L; // 窓の始め (残り 30 秒)
        com.shakenokirimi12.uoa_app.services.idp.Totp.Params key = com.shakenokirimi12.uoa_app.services.idp.Totp.parse(RFC_OTPAUTH);
        assertTrue(ledger.claim(com.shakenokirimi12.uoa_app.services.idp.Totp.step(key, clockNow)));

        OtpRelayResponder.Outcome outcome = respondTo(TOTP_REQUEST_ID);

        assertEquals(OtpRelayResponder.TOTP_STEP_USED, outcome.reason);
        assertTrue(slept.isEmpty());
        assertEquals("{\"error\":\"totp-step-used\",\"method\":\"totp\"}",
                openBox(postBodyFor(TOTP_REQUEST_ID).get("box").getAsString(), TOTP_REQUEST_ID));
    }

    /** 以前の文面 (メールだけ) で作った連携には、確かめるまでコードを作らない。 */
    @Test
    public void totpPage_withMailOnlyConsent_refusesUntilConfirmed() throws Exception {
        authenticator = true;
        pairing.totpConsentAt = null;
        OtpRelayResponder.Outcome outcome = respondTo(TOTP_REQUEST_ID);

        assertEquals(OtpRelayResponder.TOTP_CONSENT_REQUIRED, outcome.reason);
        assertTrue(mailSince.isEmpty());
        assertEquals("{\"error\":\"totp-consent-required\",\"method\":\"totp\"}",
                openBox(postBodyFor(TOTP_REQUEST_ID).get("box").getAsString(), TOTP_REQUEST_ID));
    }

    /** 認証アプリの経路では、時間切れの見張りがメールの理由 (mail-timeout) で割り込まない。 */
    @Test
    public void expiryWatchdog_doesNotInterruptTheTotpPath() {
        authenticator = true;
        OtpRelayResponder.Attempt attempt = new OtpRelayResponder.Attempt();
        attempt.markAnsweringWithoutMail();
        assertEquals(OtpRelayResponder.ExpiryResult.NOT_CLAIMED,
                responder().respondExpired(pairing, request, attempt, 1_000));
    }

    /** 鍵が別の ID のもの (設定で ID を書き換えた) なら、コードを作らずに理由を box で返す。 */
    @Test
    public void totpPage_withoutAKeyForThisUser_refusesInTheBox() throws Exception {
        authenticator = true;
        username = "s7654321";
        OtpRelayResponder.Outcome outcome = respondTo(TOTP_REQUEST_ID);

        assertEquals(OtpRelayResponder.NO_TOTP_KEY, outcome.reason);
        assertTrue(mailSince.isEmpty());
        JsonObject body = postBodyFor(TOTP_REQUEST_ID);
        assertEquals("approved", body.get("result").getAsString());
        assertEquals("{\"error\":\"no-totp-key\",\"method\":\"totp\"}", openBox(body.get("box").getAsString(), TOTP_REQUEST_ID));
    }

    /** 方式の食い違い (メール方式の画面なのに、この端末は認証アプリ方式): メールを読まずに断る。 */
    @Test
    public void mailPage_onAnAuthenticatorPhone_refusesWithoutReadingMail() throws Exception {
        authenticator = true;
        OtpRelayResponder.Outcome outcome = respondTo(MAIL_REQUEST_ID);

        assertEquals(OtpRelayResponder.METHOD_MISMATCH, outcome.reason);
        assertTrue(mailSince.isEmpty());
        assertEquals("{\"error\":\"method-mismatch\",\"method\":\"totp\"}",
                openBox(postBodyFor(MAIL_REQUEST_ID).get("box").getAsString(), MAIL_REQUEST_ID));
    }

    @Test
    public void totpPage_onAMailPhone_refusesWithoutReadingMail() throws Exception {
        OtpRelayResponder.Outcome outcome = respondTo(TOTP_REQUEST_ID);

        assertEquals(OtpRelayResponder.METHOD_MISMATCH, outcome.reason);
        assertTrue(mailSince.isEmpty());
        assertEquals("{\"error\":\"method-mismatch\",\"method\":\"mail\"}",
                openBox(postBodyFor(TOTP_REQUEST_ID).get("box").getAsString(), TOTP_REQUEST_ID));
    }

    /** 目印の無い依頼 (古い拡張) は、認証アプリ方式の端末でも今までどおりメールを読む。 */
    @Test
    public void untaggedRequest_onAnAuthenticatorPhone_stillReadsMail() {
        authenticator = true;
        OtpRelayResponder.Outcome outcome = respond(pairing);
        assertEquals(OtpRelayResponder.Kind.SENT, outcome.kind);
        assertEquals(1, mailSince.size());
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
        // {"code":"048213","method":"mail"}
        // {"code":"048213","method":"mail"} を空白で 96 バイトに埋めたもの
        assertEquals(12 + 96 + 16, OtpRelayCrypto.decode(body.get("box").getAsString()).length);
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
        assertEquals("mail-timeout", OtpRelayResponder.expiredReason(ImapOtpFetcher.Progress.Stage.RECHECK));
        // メールを読む途中で止まったのはサーバが答えなかった。
        assertEquals("imap-error", OtpRelayResponder.expiredReason(ImapOtpFetcher.Progress.Stage.SCAN));
        assertEquals("imap-error", OtpRelayResponder.expiredReason(ImapOtpFetcher.Progress.Stage.FETCH));
        assertEquals("imap-error", OtpRelayResponder.expiredReason(ImapOtpFetcher.Progress.Stage.SELECT));
        assertEquals("expired:select:r2", OtpRelayResponder.expiredDetail(ImapOtpFetcher.Progress.Stage.SELECT, 2));
        assertEquals("expired:recheck:t0.4-l0.9-s1.2-f22.6", OtpRelayResponder.expiredDetail(
                ImapOtpFetcher.Progress.Stage.RECHECK, 0, ImapOtpFetcher.Progress.timing(400, 900, 1_200, 22_600)));
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
        api.register(pairing.pairingId, OtpRelayCrypto.keys(K), "fcm-token", "Pixel 9", "Mac の Chrome", true, "7K3M-9QXA");
        LoopbackHttpServer.Recorded r = server.first("/v1/pairings");
        assertNull(r.header("Authorization"));
        JsonObject body = JsonParser.parseString(r.body).getAsJsonObject();
        assertEquals("fcm", body.get("platform").getAsString());
        assertEquals("fcm-token", body.get("fcmToken").getAsString());
        assertEquals("Pixel 9", body.get("deviceName").getAsString());
        assertEquals("Mac の Chrome", body.get("browserLabel").getAsString());
        assertTrue(body.get("updateOnly").getAsBoolean());
        assertEquals("7K3M-9QXA", body.get("supportId").getAsString());
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
