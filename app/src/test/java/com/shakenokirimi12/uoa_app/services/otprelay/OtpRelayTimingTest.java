package com.shakenokirimi12.uoa_app.services.otprelay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** iOS の effectiveRequestedAt のテストと同じ境界値 (秒単位のずれ = requestedAt - deliveredAt)。 */
public class OtpRelayTimingTest {
    private static final long DELIVERED = 1_800_000_000_000L;

    private static long effective(long offsetSeconds) {
        return OtpRelayTiming.effectiveRequestedAt(DELIVERED + offsetSeconds * 1000, DELIVERED);
    }

    @Test
    public void trustedOffsets_areUsedAsIs() {
        for (long s : new long[] {-8, -615, -540, 60, -60, -660, 0}) {
            assertEquals("offset " + s, DELIVERED + s * 1000, effective(s));
        }
    }

    @Test
    public void untrustedOffsets_fallBackTo20sBeforeDelivery() {
        for (long s : new long[] {-661, -10800, -61, -300, -539, 61}) {
            assertEquals("offset " + s, DELIVERED - 20_000, effective(s));
        }
    }

    @Test
    public void mailTimeout_isCappedByExpiry_butAtLeast5s() {
        long now = DELIVERED;
        assertEquals(18, OtpRelayTiming.mailTimeoutSeconds(now, now + 18_000, now + 120_000));
        assertEquals(7, OtpRelayTiming.mailTimeoutSeconds(now, now + 18_000, now + 10_000));
        assertEquals(5, OtpRelayTiming.mailTimeoutSeconds(now, now + 18_000, now + 2_000));
    }

    @Test
    public void respondDeadline_is24sAfterStart_butBeforeExpiry() {
        long start = DELIVERED;
        assertEquals(start + 24_000, OtpRelayTiming.respondDeadline(start, start + 115_000));
        // 配信が遅れて期限間際に始まったら、期限の 5 秒前 (過ぎていれば、すぐ)。
        assertEquals(start + 10_000 - 5_000, OtpRelayTiming.respondDeadline(start, start + 10_000));
    }
}
