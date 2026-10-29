package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.shakenokirimi12.uoa_app.services.idp.SeciossError;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * セッションの再利用と「ログインしてよいか」の判断を固定する。
 *
 * ログイン 1 回につき OTP メールが 1 通飛ぶ。ここが緩むと、画面を見ていない間にメールが
 * 積み上がる (iOS で 2026-09-25 に発生。対策は SPEC.md「6. バックグラウンドログイン」を参照)。
 */
public class SessionCoordinatorTest {

    private static final Object METHOD = "idp";

    /** バックグラウンドでは新規ログインを始めない。login 自体が呼ばれないことまで見る。 */
    @Test
    public void background_doesNotStartNewLogin() {
        SessionCoordinator<String> coordinator = new SessionCoordinator<>();
        AtomicInteger logins = new AtomicInteger();

        try {
            coordinator.session(METHOD, AuthorizationContext.BACKGROUND, null,
                    session -> null,
                    () -> { logins.incrementAndGet(); return "new"; });
            fail("バックグラウンドでログインしてはいけない");
        } catch (Exception e) {
            assertEquals(SeciossError.class, e.getClass());
            assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, ((SeciossError) e).kind);
        }
        assertEquals(0, logins.get());
    }

    /** 保存済みセッションが生きていれば、バックグラウンドでもそれを使う。 */
    @Test
    public void background_reusesStoredSessionWhenAlive() throws Exception {
        SessionCoordinator<String> coordinator = new SessionCoordinator<>();
        AtomicInteger logins = new AtomicInteger();

        String result = coordinator.session(METHOD, AuthorizationContext.BACKGROUND, "stored",
                session -> session,
                () -> { logins.incrementAndGet(); return "new"; });

        assertEquals("stored", result);
        assertEquals(0, logins.get());
    }

    /** 保存済みが死んでいれば、対話文脈ではログインする。 */
    @Test
    public void interactive_logsInWhenStoredSessionIsDead() throws Exception {
        SessionCoordinator<String> coordinator = new SessionCoordinator<>();
        AtomicInteger logins = new AtomicInteger();

        String result = coordinator.session(METHOD, AuthorizationContext.INTERACTIVE, "dead",
                session -> null,
                () -> { logins.incrementAndGet(); return "new"; });

        assertEquals("new", result);
        assertEquals(1, logins.get());
    }

    /** 直近に検証済みなら probe すらしない (リクエスト 0)。 */
    @Test
    public void withinTrustWindow_doesNotProbeAgain() throws Exception {
        SessionCoordinator<String> coordinator = new SessionCoordinator<>();
        AtomicInteger probes = new AtomicInteger();

        coordinator.session(METHOD, AuthorizationContext.INTERACTIVE, null,
                session -> { probes.incrementAndGet(); return session; },
                () -> "new");
        int afterFirst = probes.get();

        String again = coordinator.session(METHOD, AuthorizationContext.INTERACTIVE, null,
                session -> { probes.incrementAndGet(); return session; },
                () -> "new2");

        assertEquals("new", again);
        assertEquals(afterFirst, probes.get());
    }

    /** 同時に来た要求で二重ログイン (= 二重 OTP メール) にしない。 */
    @Test
    public void concurrentRequests_loginRunsOnce() throws Exception {
        SessionCoordinator<String> coordinator = new SessionCoordinator<>();
        AtomicInteger logins = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        SessionCoordinator.Login<String> login = () -> {
            logins.incrementAndGet();
            started.countDown();
            release.await();
            return "new";
        };

        String[] results = new String[2];
        Thread first = new Thread(() -> {
            try {
                results[0] = coordinator.session(METHOD, AuthorizationContext.INTERACTIVE, null,
                        session -> null, login);
            } catch (Exception ignored) {
            }
        });
        Thread second = new Thread(() -> {
            try {
                started.await();
                results[1] = coordinator.session(METHOD, AuthorizationContext.INTERACTIVE, null,
                        session -> null, login);
            } catch (Exception ignored) {
            }
        });

        first.start();
        started.await();
        second.start();
        // 後発が inFlight を掴むまで待ってから解放する。
        Thread.sleep(100);
        release.countDown();
        first.join(5000);
        second.join(5000);

        assertEquals("new", results[0]);
        assertEquals("new", results[1]);
        assertEquals(1, logins.get());
    }

    /** ID/PW 誤りはバックオフの対象にしない (呼び出し側が同期ごと止めるため)。 */
    @Test
    public void invalidCredentials_isNotBackedOff() {
        SessionCoordinator<String> coordinator = new SessionCoordinator<>();
        AtomicInteger attempts = new AtomicInteger();
        SessionCoordinator.Login<String> login = () -> {
            attempts.incrementAndGet();
            throw new Exception(AuthErrors.INVALID_CREDENTIALS_MESSAGE);
        };

        for (int i = 0; i < 2; i++) {
            try {
                coordinator.session(METHOD, AuthorizationContext.INTERACTIVE, null,
                        session -> null, login);
                fail("資格情報エラーが投げられるはず");
            } catch (Exception ignored) {
            }
        }
        assertEquals(2, attempts.get());
    }

    /** 障害系の失敗は、バックグラウンドからの再試行を抑える前にそもそもログインへ進ませない。 */
    @Test
    public void backgroundAfterFailure_stillDoesNotLogIn() throws Exception {
        SessionCoordinator<String> coordinator = new SessionCoordinator<>();
        AtomicInteger logins = new AtomicInteger();
        try {
            coordinator.session(METHOD, AuthorizationContext.INTERACTIVE, null,
                    session -> null,
                    () -> { logins.incrementAndGet(); throw new Exception("network down"); });
            fail("失敗が投げられるはず");
        } catch (Exception ignored) {
        }
        assertEquals(1, logins.get());

        try {
            coordinator.session(METHOD, AuthorizationContext.BACKGROUND, null,
                    session -> null,
                    () -> { logins.incrementAndGet(); return "new"; });
            fail("バックグラウンドでログインしてはいけない");
        } catch (Exception e) {
            assertSame(SeciossError.class, e.getClass());
        }
        assertEquals(1, logins.get());
    }
}
