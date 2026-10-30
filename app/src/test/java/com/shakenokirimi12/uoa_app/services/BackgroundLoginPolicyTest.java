package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.services.idp.LoginEvidence;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;

import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ログインの規則 (SPEC.md「6. バックグラウンドログイン」「7.1 ID とパスワードの誤り」) を、SessionCoordinator と
 * 組にして固定する (iOS の BackgroundLoginPolicyTests と同じ規則)。
 * - 認証アプリ方式: 止めていない鍵、最後に成功したパスワード、サービスごと 30 分に 1 回
 * - メール方式: 端末全体で 6 時間に 1 回 (送信を頼む直前に取る)。SSO を持っていれば 2 本目は入ってよい
 * - どの失敗でも止めた印を立てる。アプリ内の事情は除く。外すのは前面の成功と認証アプリの登録の成功
 * - 誤りの印は資格情報の指紋に付き、指紋が変わるまで前面でもログインしない。通知は指紋ごとに 1 回
 */
public class BackgroundLoginPolicyTest {
    private static final Object METHOD = "idp";
    private static final String UID = "s1300000";
    private static final String PASS = "pw";

    /** 保存先 (プロセスをまたいで残る側) の代わり。 */
    private static final class MemoryStore implements BackgroundLoginPolicy.Store {
        String fingerprint = "";
        boolean blocked;
        long lastMailDispatch;
        final Map<String, Long> attempts = new HashMap<>();
        String invalid = "";
        final Set<String> notified = new HashSet<>();
        String savedUid = UID;
        String savedPass = PASS;
        @NonNull @Override public String verifiedFingerprint() { return fingerprint; }
        @Override public void setVerifiedFingerprint(@NonNull String f) { fingerprint = f; }
        @Override public boolean isBlocked() { return blocked; }
        @Override public void setBlocked(boolean b) { blocked = b; }
        @Override public long lastAttempt(@NonNull String scope) { return attempts.getOrDefault(scope, 0L); }
        @Override public void setLastAttempt(@NonNull String scope, long at) { attempts.put(scope, at); }
        @Override public long lastMailDispatch() { return lastMailDispatch; }
        @Override public void setLastMailDispatch(long at) { lastMailDispatch = at; }
        @Override public boolean isCredentialsInvalid(@NonNull String uid, @NonNull String pass) {
            return invalid.equals(BackgroundLoginPolicy.fingerprint(uid, pass));
        }
        @Override public boolean markCredentialsInvalid(@NonNull String uid, @NonNull String pass) {
            invalid = BackgroundLoginPolicy.fingerprint(uid, pass);
            return !notified.contains(invalid);
        }
        @Override public void markCredentialsInvalidNotified(@NonNull String uid, @NonNull String pass) {
            notified.add(BackgroundLoginPolicy.fingerprint(uid, pass));
        }
        @Override public boolean isSaved(@NonNull String uid, @NonNull String pass) {
            return uid.equals(savedUid) && pass.equals(savedPass);
        }
    }

    /** IdP にパスワードを送って通ったログイン (SSO だけで通ったものと区別する)。 */
    private static SessionCoordinator.Login<String> passwordLogin(String result) {
        return () -> {
            LoginEvidence.notePasswordAccepted();
            return result;
        };
    }

    private final MemoryStore store = new MemoryStore();
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final AtomicInteger mailNotices = new AtomicInteger();
    private final AtomicInteger invalidNotices = new AtomicInteger();
    /** バックグラウンドで使える方式 (null は「人の操作が要る」: 控えが無い・鍵が無いか止めている)。 */
    @Nullable private OtpMethod unattendedMethod = OtpMethod.AUTHENTICATOR;
    private boolean holdsSso;

    private BackgroundLoginPolicy policy() {
        return new BackgroundLoginPolicy(store,
                new BackgroundLoginPolicy.Accounts() {
                    @Nullable @Override public OtpMethod unattendedMethod(@NonNull String uid) { return unattendedMethod; }
                    @Override public boolean holdsIdpSso(@NonNull String uid) { return holdsSso; }
                },
                new BackgroundLoginPolicy.Notices() {
                    @Override public void mailDispatch() { mailNotices.incrementAndGet(); }
                    @Override public void credentialsInvalid() { invalidNotices.incrementAndGet(); }
                },
                clock::get);
    }

    private static String backgroundSession(SessionCoordinator<String> c, BackgroundLoginPolicy policy, String scope,
                                            AtomicInteger logins, Exception failure) throws Exception {
        return c.session(METHOD, AuthorizationContext.BACKGROUND, "dead", s -> null, () -> {
            logins.incrementAndGet();
            if (failure != null) throw failure;
            return "new-" + logins.get();
        }, policy.forAccount(scope, UID, PASS));
    }

    private static String backgroundSession(SessionCoordinator<String> c, BackgroundLoginPolicy policy,
                                            AtomicInteger logins, Exception failure) throws Exception {
        return backgroundSession(c, policy, "moodle", logins, failure);
    }

    private static void assertLoginRequired(Exception e) {
        assertEquals(SeciossError.class, e.getClass());
        assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, ((SeciossError) e).kind);
    }

    // ---- 認証アプリ方式 ----

    @Test
    public void authenticatorWithKey_logsInInBackground_afterAForegroundSuccess() throws Exception {
        BackgroundLoginPolicy policy = policy();
        AtomicInteger logins = new AtomicInteger();
        // 前面のログインが成功して、このパスワードが通ると分かる。
        new SessionCoordinator<String>().forceLogin(METHOD, passwordLogin("foreground"), policy.forAccount("moodle", UID, PASS));

        assertEquals("new-1", backgroundSession(new SessionCoordinator<>(), policy, logins, null));
        assertEquals(1, logins.get());
    }

    @Test
    public void authenticator_unverifiedPassword_doesNotLogInInBackground() {
        BackgroundLoginPolicy policy = policy();
        AtomicInteger logins = new AtomicInteger();
        try {
            backgroundSession(new SessionCoordinator<>(), policy, logins, null);
            fail("成功したことのないパスワードを裏で試してはいけない");
        } catch (Exception e) {
            assertLoginRequired(e);
        }
        assertEquals(0, logins.get());
    }

    /** 鍵が無い・止めている (unattendedMethod が null) ならバックグラウンドではログインしない。 */
    @Test
    public void noUsableKey_doesNotLogInInBackground() {
        BackgroundLoginPolicy policy = policy();
        policy.succeeded(UID, PASS, true);
        unattendedMethod = null;
        AtomicInteger logins = new AtomicInteger();
        try {
            backgroundSession(new SessionCoordinator<>(), policy, logins, null);
            fail("鍵を止めている間は裏でログインしない");
        } catch (Exception e) {
            assertLoginRequired(e);
        }
        assertEquals(0, logins.get());
    }

    /** 同じサービスのバックグラウンドの新規ログインは 30 分に 1 回まで。サービスごとに別の枠。 */
    @Test
    public void authenticator_backgroundLogins_areRateLimitedPerService() throws Exception {
        BackgroundLoginPolicy policy = policy();
        policy.succeeded(UID, PASS, true);
        AtomicInteger logins = new AtomicInteger();
        backgroundSession(new SessionCoordinator<>(), policy, logins, null);
        assertEquals(1, logins.get());

        clock.addAndGet(11 * 60 * 1000L);
        try {
            backgroundSession(new SessionCoordinator<>(), policy, logins, null);
            fail("30 分たっていない");
        } catch (Exception e) {
            assertLoginRequired(e);
        }
        assertEquals(1, logins.get());
        assertTrue(policy.claimSlot("campussquare", UID));

        clock.addAndGet(20 * 60 * 1000L);
        backgroundSession(new SessionCoordinator<>(), policy, logins, null);
        assertEquals(2, logins.get());
    }

    // ---- メール方式 ----

    /** メール方式も裏でログインする。送信を頼む直前に枠を取り、時刻を書いて通知を 1 回出す。 */
    @Test
    public void mail_logsInInBackground_andClaimsTheDispatchWindowBeforeSending() throws Exception {
        unattendedMethod = OtpMethod.MAIL;
        BackgroundLoginPolicy policy = policy();
        AtomicInteger logins = new AtomicInteger();
        // パスワードの確認 (前面の成功) は要らない。
        String session = new SessionCoordinator<String>().session(METHOD, AuthorizationContext.BACKGROUND, "dead",
                s -> null, () -> {
                    logins.incrementAndGet();
                    policy.claimMailDispatch();
                    return "mail-login";
                }, policy.forAccount("moodle", UID, PASS));
        assertEquals("mail-login", session);
        assertEquals(clock.get(), store.lastMailDispatch);
        assertEquals(1, mailNotices.get());
    }

    /** 送信の枠は端末全体で 6 時間に 1 回。空いていなければ送らずに止める (止めた印は立てない)。 */
    @Test
    public void mail_dispatchWindow_isSixHoursDeviceWide() throws Exception {
        unattendedMethod = OtpMethod.MAIL;
        BackgroundLoginPolicy policy = policy();
        policy.claimMailDispatch();
        clock.addAndGet(5 * 60 * 60 * 1000L);
        try {
            policy.claimMailDispatch();
            fail("6 時間たっていない");
        } catch (SeciossError e) {
            assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, e.kind);
        }
        assertEquals(1, mailNotices.get());
        clock.addAndGet(60 * 60 * 1000L);
        policy.claimMailDispatch();
        assertEquals(2, mailNotices.get());
    }

    /**
     * 6 時間の枠を使ったあとは、SSO を持っていなければ IdP に触れない (パスワードも送らない)。持っていれば
     * 2 本目 (CampusSquare) は 1 本目 (Moodle) の SSO で入ってよい。2 本目が新しいメールを要すれば
     * claimMailDispatch が止める。
     */
    @Test
    public void mail_secondServiceUsesTheSso_otherwiseWaitsForTheWindow() throws Exception {
        unattendedMethod = OtpMethod.MAIL;
        BackgroundLoginPolicy policy = policy();
        AtomicInteger logins = new AtomicInteger();
        policy.claimMailDispatch();

        holdsSso = true;
        assertEquals("new-1", backgroundSession(new SessionCoordinator<>(), policy, "campussquare", logins, null));

        holdsSso = false;
        clock.addAndGet(31 * 60 * 1000L);
        try {
            backgroundSession(new SessionCoordinator<>(), policy, "campussquare", logins, null);
            fail("枠が空いておらず SSO も無い");
        } catch (Exception e) {
            assertLoginRequired(e);
        }
        assertEquals(1, logins.get());

        // 2 本目が新しい OTP メールを要する状態: 送らずに止まり、止めた印は立てない。
        holdsSso = true;
        clock.addAndGet(31 * 60 * 1000L);
        try {
            new SessionCoordinator<String>().session(METHOD, AuthorizationContext.BACKGROUND, "dead", s -> null, () -> {
                policy.claimMailDispatch();
                return "sent";
            }, policy.forAccount("campussquare", UID, PASS));
            fail("6 時間の枠が空いていない");
        } catch (SeciossError e) {
            assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, e.kind);
        }
        assertFalse(store.blocked);
        assertEquals(1, mailNotices.get());
    }

    // ---- 止めた印 ----

    /** どの失敗でも止め、前面で成功するまで止めたまま。止めたことは保存先に残る (次のプロセスでも止まる)。 */
    @Test
    public void anyBackgroundFailure_blocksUntilForegroundSuccess() throws Exception {
        BackgroundLoginPolicy policy = policy();
        policy.succeeded(UID, PASS, true);
        Exception[] failures = {
                new IOException("timeout"),
                new SeciossError(SeciossError.Kind.MAINTENANCE, "maintenance").asLoginException(),
                new SeciossError(SeciossError.Kind.UNEXPECTED_STATUS, 503, "allotplogin.cgi"),
                new SeciossError(SeciossError.Kind.SESSION_TIMED_OUT),
                new SeciossError(SeciossError.Kind.INVALID_OTP),
                new SeciossError(SeciossError.Kind.OTP_FETCH_FAILED, "mail-timeout").asLoginException(),
        };
        for (Exception e : failures) {
            store.blocked = false;
            policy.failed(UID, PASS, e, true);
            assertTrue(e.toString(), store.blocked);
        }

        AtomicInteger logins = new AtomicInteger();
        clock.addAndGet(2 * 60 * 60 * 1000L);
        try {
            backgroundSession(new SessionCoordinator<>(), policy(), logins, null);
            fail("止めている間はログインしない");
        } catch (Exception e) {
            assertLoginRequired(e);
        }
        assertEquals(0, logins.get());

        new SessionCoordinator<String>().session(METHOD, AuthorizationContext.INTERACTIVE, null, s -> null,
                () -> "foreground", policy.forAccount("moodle", UID, PASS));
        assertFalse(policy.isBlocked());
    }

    /** アプリ内の事情 (人の操作が要る・使える TOTP の窓が来なかった) は失敗に数えない。前面の失敗でも止めない。 */
    @Test
    public void appInternalConditions_andForegroundFailures_doNotBlock() {
        BackgroundLoginPolicy policy = policy();
        policy.failed(UID, PASS, new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED), true);
        policy.failed(UID, PASS, new SeciossError(SeciossError.Kind.TOTP_STEP_UNAVAILABLE).asLoginException(), true);
        assertFalse(store.blocked);
        policy.failed(UID, PASS, new IOException("timeout"), false);
        assertFalse(store.blocked);
    }

    /** 認証アプリの登録 (し直し) の成功でも外れる。 */
    @Test
    public void registrationSuccess_clearsTheBlock() {
        BackgroundLoginPolicy policy = policy();
        store.blocked = true;
        policy.registrationSucceeded();
        assertFalse(policy.isBlocked());
    }

    /** バックグラウンドの失敗は、前面のログインを待たせない (S-1)。 */
    @Test
    public void backgroundFailure_doesNotDelayForegroundLogin() throws Exception {
        BackgroundLoginPolicy policy = policy();
        policy.succeeded(UID, PASS, true);
        SessionCoordinator<String> c = new SessionCoordinator<>();
        AtomicInteger logins = new AtomicInteger();
        try {
            backgroundSession(c, policy, logins, new IOException("timeout"));
            fail();
        } catch (IOException expected) {
            // 通信の失敗がそのまま返る
        }
        assertEquals("foreground", c.session(METHOD, AuthorizationContext.INTERACTIVE, null, s -> null,
                () -> "foreground", policy.forAccount("moodle", UID, PASS)));
    }

    // ---- ID / パスワードの誤り ----

    /** 誤りの印は指紋に付く。同じ値では前面でもログインせず、値が変わったら外れる。通知は指紋ごとに 1 回。 */
    @Test
    public void invalidCredentials_blockAllLogins_untilTheFingerprintChanges() throws Exception {
        BackgroundLoginPolicy policy = policy();
        policy.succeeded(UID, PASS, true);
        AtomicInteger logins = new AtomicInteger();
        try {
            backgroundSession(new SessionCoordinator<>(), policy, logins, new Exception(AuthErrors.invalidCredentialsMessage()));
            fail();
        } catch (Exception expected) {
            assertTrue(AuthErrors.isInvalidCredentials(expected.getMessage()));
        }
        assertEquals(1, invalidNotices.get());

        // 同じ値: 前面 (forceLogin = 「保存して同期」) でも始めない。
        try {
            new SessionCoordinator<String>().forceLogin(METHOD, () -> {
                logins.incrementAndGet();
                return "x";
            }, policy.forAccount("moodle", UID, PASS));
            fail("同じ誤った資格情報で試してはいけない");
        } catch (Exception e) {
            assertTrue(AuthErrors.isInvalidCredentials(e.getMessage()));
        }
        assertEquals(1, logins.get());

        // もう一度誤りと分かっても、同じ指紋では通知しない。
        policy.failed(UID, PASS, new Exception(AuthErrors.invalidCredentialsMessage()), true);
        assertEquals(1, invalidNotices.get());

        // パスワードを変えたら外れる。
        assertEquals("ok", new SessionCoordinator<String>().forceLogin(METHOD, () -> "ok",
                policy.forAccount("moodle", UID, "new-pw")));
    }

    /** 前面で誤りと分かったときも、同じ指紋で 1 回だけ通知する (D-8)。 */
    @Test
    public void invalidCredentialsInForeground_areNotifiedOnce() {
        BackgroundLoginPolicy policy = policy();
        policy.failed(UID, PASS, new Exception(AuthErrors.invalidCredentialsMessage()), false);
        assertTrue(policy.credentialsInvalid(UID, PASS));
        policy.failed(UID, PASS, new Exception(AuthErrors.invalidCredentialsMessage()), true);
        policy.markInvalid(UID, PASS);
        assertEquals(1, invalidNotices.get());
    }

    /**
     * 保存前の確認 (設定でのアカウントの入れ直し) で打ち間違えた値には印を付けない。付けると、保存している誤った値の
     * 印が外れて、バックグラウンドがそれを送り直す。
     */
    @Test
    public void invalidMark_staysOnTheSavedCredentials_whenAnUnsavedValueFails() {
        BackgroundLoginPolicy policy = policy();
        policy.failed(UID, PASS, new Exception(AuthErrors.invalidCredentialsMessage()), true);
        policy.failed(UID, "typo", new Exception(AuthErrors.invalidCredentialsMessage()), false);
        policy.markInvalid(UID, "typo");
        assertTrue(policy.credentialsInvalid(UID, PASS));
        assertFalse(policy.credentialsInvalid(UID, "typo"));
        assertEquals(1, invalidNotices.get());
    }

    /** SSO だけで通ったログインはパスワードを確かめていないので、最後に成功したパスワードとして記録しない。 */
    @Test
    public void ssoOnlySuccess_doesNotVerifyThePassword() throws Exception {
        BackgroundLoginPolicy policy = policy();
        new SessionCoordinator<String>().forceLogin(METHOD, () -> "sso", policy.forAccount("moodle", UID, PASS));
        assertFalse(policy.permits(UID, PASS));
        new SessionCoordinator<String>().forceLogin(METHOD, passwordLogin("password"), policy.forAccount("moodle", UID, PASS));
        assertTrue(policy.permits(UID, PASS));
    }

    /** 時計が戻ったら、今試したものとして今から間隔を数え直す (6 時間と 30 分の枠を開け直さない)。 */
    @Test
    public void clockGoingBack_restartsTheWindowsFromNow() throws Exception {
        unattendedMethod = OtpMethod.MAIL;
        BackgroundLoginPolicy policy = policy();
        long start = clock.get();
        assertTrue(policy.claimSlot("moodle", UID));
        policy.claimMailDispatch();

        clock.set(start - 7 * 60 * 60 * 1000L);
        try {
            policy.claimMailDispatch();
            fail("時計が戻っても 6 時間の枠を開け直さない");
        } catch (SeciossError e) {
            assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, e.kind);
        }
        assertFalse(policy.claimSlot("moodle", UID));
        assertEquals(clock.get(), store.lastMailDispatch);
        assertEquals(clock.get(), (long) store.attempts.get("moodle"));

        clock.addAndGet(5 * 60 * 60 * 1000L);
        try {
            policy.claimMailDispatch();
            fail("戻った時刻から 6 時間たっていない");
        } catch (SeciossError expected) {
            // 待つ
        }
        clock.addAndGet(60 * 60 * 1000L);
        policy.claimMailDispatch();
        assertEquals(2, mailNotices.get());
    }

    /** IdpLoginGate を待つ間に先のログインが誤りを見つけた・鍵を止めたなら、パスワードを送らない。 */
    @Test
    public void checkBeforeIdpLogin_refusesAfterTheStateChangedWhileWaiting() throws Exception {
        BackgroundLoginPolicy policy = policy();
        policy.succeeded(UID, PASS, true);
        policy.checkBeforeIdpLogin(UID, PASS, true);

        unattendedMethod = null; // 鍵を止めた
        try {
            policy.checkBeforeIdpLogin(UID, PASS, true);
            fail();
        } catch (SeciossError e) {
            assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, e.kind);
        }
        policy.checkBeforeIdpLogin(UID, PASS, false); // 前面は手入力で続けられる

        policy.failed(UID, PASS, new Exception(AuthErrors.invalidCredentialsMessage()), false);
        try {
            policy.checkBeforeIdpLogin(UID, PASS, false);
            fail("誤りと分かった資格情報は前面でも送らない");
        } catch (SeciossError e) {
            assertEquals(SeciossError.Kind.INVALID_CREDENTIALS, e.kind);
        }
    }
}
