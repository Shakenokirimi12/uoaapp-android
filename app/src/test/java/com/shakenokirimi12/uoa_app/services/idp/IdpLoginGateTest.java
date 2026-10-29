package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** IdP へのログインはサービスをまたいで 1 本ずつ。入力を取り消したら、待っていたログインは始めない (S-6, C-1)。 */
public class IdpLoginGateTest {
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final IdpLoginGate gate = new IdpLoginGate(clock::get);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @Test
    public void secondLogin_waitsForTheFirst() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        IdpLoginGate.Body<String> body = () -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            firstInside.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            inside.decrementAndGet();
            return "ok";
        };
        Future<String> first = pool.submit(() -> gate.run(body));
        assertTrue(firstInside.await(5, TimeUnit.SECONDS));
        Future<String> second = pool.submit(() -> gate.run(body));
        Thread.sleep(100);
        assertFalse("1 本目が終わるまで 2 本目は入らない", second.isDone());
        releaseFirst.countDown();
        assertEquals("ok", first.get(5, TimeUnit.SECONDS));
        assertEquals("ok", second.get(5, TimeUnit.SECONDS));
        assertEquals(1, maxInside.get());
        pool.shutdownNow();
    }

    @Test
    public void cancellingThePrompt_refusesLoginsThatWereWaiting() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        AtomicInteger secondRan = new AtomicInteger();
        Future<String> first = pool.submit(() -> gate.run(() -> {
            firstInside.countDown();
            Thread.sleep(200);
            gate.noteCancelled();
            throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, "otp prompt cancelled");
        }));
        assertTrue(firstInside.await(5, TimeUnit.SECONDS));
        Future<String> second = pool.submit(() -> gate.run(() -> {
            secondRan.incrementAndGet();
            return "second";
        }));
        try {
            second.get(5, TimeUnit.SECONDS);
            fail("取り消しのあとに、待っていたログインが入力を求め直してはいけない");
        } catch (java.util.concurrent.ExecutionException e) {
            assertEquals(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, ((SeciossError) e.getCause()).kind);
        }
        assertEquals(0, secondRan.get());
        try {
            first.get(5, TimeUnit.SECONDS);
            fail();
        } catch (java.util.concurrent.ExecutionException expected) {
            // 1 本目は取り消しで終わる
        }
        // 取り消しのあとに始めたログインは通常どおり入る。
        assertEquals("later", gate.run(() -> "later"));
        pool.shutdownNow();
    }

    @Test
    public void cancelledSince_reportsOnlyCancellationsAfterTheGivenTime() {
        assertFalse(gate.cancelledSince(0));
        clock.set(5_000L);
        gate.noteCancelled();
        assertTrue(gate.cancelledSince(4_000L));
        assertTrue(gate.cancelledSince(5_000L));
        assertFalse(gate.cancelledSince(6_000L));
    }
}
