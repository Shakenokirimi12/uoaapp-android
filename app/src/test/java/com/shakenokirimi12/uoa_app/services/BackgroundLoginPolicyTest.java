package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.services.idp.SeciossError;

import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * バックグラウンド (SyncWorker) の再ログインの規則を、SessionCoordinator と組にして固定する (iOS の
 * BackgroundTOTPLoginTests と同じ)。メール方式では決してログインしない (ログイン 1 回 = OTP メール 1 通) ことと、
 * 認証アプリ方式でこのアプリが鍵を持つときだけ、間隔の制限と失敗後の停止の範囲でログインすることを見る。
 */
public class BackgroundLoginPolicyTest {
    private static final Object METHOD = "idp";
    private static final String UID = "s1300000";
    private static final String PASS = "pw";

    /** 保存先 (プロセスをまたいで残る側) の代わり。 */
    private static final class MemoryStore implements BackgroundLoginPolicy.Store {
        String fingerprint = "";
        boolean blocked;
        final Map<String, Long> attempts = new HashMap<>();
        @NonNull @Override public String verifiedFingerprint() { return fingerprint; }
        @Override public void setVerifiedFingerprint(@NonNull String f) { fingerprint = f; }
        @Override public boolean isBlocked() { return blocked; }
        @Override public void setBlocked(boolean b) { blocked = b; }
        @Override public long lastAttempt(@NonNull String scope) { return attempts.getOrDefault(scope, 0L); }
        @Override public void setLastAttempt(@NonNull String scope, long at) { attempts.put(scope, at); }
    }

    private final MemoryStore store = new MemoryStore();
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);

    /** canRunUnattended は OtpProvider の答え (メール方式は false、鍵を持つ認証アプリ方式は true)。 */
    private BackgroundLoginPolicy policy(boolean canRunUnattended) {
        return new BackgroundLoginPolicy(store, (u, p) -> canRunUnattended, clock::get);
    }

    private static String backgroundSession(SessionCoordinator<String> c, BackgroundLoginPolicy policy,
                                            AtomicInteger logins, Exception failure) throws Exception {
        return c.session(METHOD, AuthorizationContext.BACKGROUND, "dead", s -> null, () -> {
            logins.incrementAndGet();
            if (failure != null) throw failure;
            return "new-" + logins.get();
        }, policy.forAccount("moodle", UID, PASS));
    }

    private static void assertLoginRequired(Exception e) {
        assertEquals(SeciossError.class, e.getClass());
        assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, ((SeciossError) e).kind);
    }

    @Test
    public void mail_neverLogsInInBackground_evenWithVerifiedPassword() {
        BackgroundLoginPolicy policy = policy(false);
        policy.succeeded(UID, PASS);
        AtomicInteger logins = new AtomicInteger();
        try {
            backgroundSession(new SessionCoordinator<>(), policy, logins, null);
            fail("メール方式はバックグラウンドでログインしてはいけない");
        } catch (Exception e) {
            assertLoginRequired(e);
        }
        assertEquals(0, logins.get());
    }

    @Test
    public void authenticatorWithKey_logsInInBackground_afterAForegroundSuccess() throws Exception {
        BackgroundLoginPolicy policy = policy(true);
        SessionCoordinator<String> c = new SessionCoordinator<>();
        AtomicInteger logins = new AtomicInteger();
        // 前面のログインが成功して、このパスワードが通ると分かる。
        c.forceLogin(METHOD, () -> "foreground", policy.forAccount("moodle", UID, PASS));

        String result = backgroundSession(new SessionCoordinator<>(), policy, logins, null);
        assertEquals("new-1", result);
        assertEquals(1, logins.get());
    }

    @Test
    public void unverifiedPassword_doesNotLogInInBackground() {
        BackgroundLoginPolicy policy = policy(true);
        AtomicInteger logins = new AtomicInteger();
        try {
            backgroundSession(new SessionCoordinator<>(), policy, logins, null);
            fail("成功したことのないパスワードを裏で試してはいけない");
        } catch (Exception e) {
            assertLoginRequired(e);
        }
        assertEquals(0, logins.get());
    }

    /** 失敗したら前面で成功するまで止める。止めたことは保存先に残る (次のプロセスでも止まる)。 */
    @Test
    public void backgroundFailure_blocksUntilForegroundSuccess() throws Exception {
        BackgroundLoginPolicy policy = policy(true);
        policy.succeeded(UID, PASS);
        AtomicInteger logins = new AtomicInteger();
        try {
            backgroundSession(new SessionCoordinator<>(), policy, logins, new SeciossError(SeciossError.Kind.INVALID_OTP));
            fail();
        } catch (SeciossError e) {
            assertEquals(SeciossError.Kind.INVALID_OTP, e.kind);
        }
        assertTrue(store.blocked);

        clock.addAndGet(2 * 60 * 60 * 1000L);
        BackgroundLoginPolicy restarted = policy(true);
        try {
            backgroundSession(new SessionCoordinator<>(), restarted, logins, null);
            fail("止めている間はログインしない");
        } catch (Exception e) {
            assertLoginRequired(e);
        }
        assertEquals(1, logins.get());

        // 前面のログインが通ると戻る。
        new SessionCoordinator<String>().session(METHOD, AuthorizationContext.INTERACTIVE, null, s -> null,
                () -> "foreground", restarted.forAccount("moodle", UID, PASS));
        assertFalse(restarted.isBlocked());
    }

    /** 通信の失敗では止めない (大学側は答えていない)。間隔の制限だけが効く。 */
    @Test
    public void transportFailure_doesNotBlock() {
        BackgroundLoginPolicy policy = policy(true);
        policy.unattendedFailed(new IOException("timeout"));
        policy.unattendedFailed(new Exception("接続エラー", new java.net.SocketTimeoutException()));
        assertFalse(store.blocked);
        policy.unattendedFailed(new Exception("unexpected page"));
        assertTrue(store.blocked);
    }

    /** 同じサービスのバックグラウンドの新規ログインは 30 分に 1 回まで。サービスごとに別の枠。 */
    @Test
    public void backgroundLogins_areRateLimitedPerService() throws Exception {
        BackgroundLoginPolicy policy = policy(true);
        policy.succeeded(UID, PASS);
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
        assertTrue(policy.claimSlot("campussquare"));

        clock.addAndGet(20 * 60 * 1000L);
        backgroundSession(new SessionCoordinator<>(), policy, logins, null);
        assertEquals(2, logins.get());
    }
}
