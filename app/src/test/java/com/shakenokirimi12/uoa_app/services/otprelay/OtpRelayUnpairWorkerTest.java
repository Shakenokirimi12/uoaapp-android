package com.shakenokirimi12.uoa_app.services.otprelay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ログアウト後の解除は、届くまで送り直す。送り直しても通らない応答だけで諦める。 */
public class OtpRelayUnpairWorkerTest {
    @Test
    public void removedOrAlreadyGoneIsSettled() {
        assertTrue(OtpRelayUnpairWorker.isSettled(204));
        assertTrue(OtpRelayUnpairWorker.isSettled(404));
    }

    @Test
    public void rejectedRequestsAreSettledBecauseRetryingCannotHelp() {
        assertTrue(OtpRelayUnpairWorker.isSettled(400));
        assertTrue(OtpRelayUnpairWorker.isSettled(401));
        assertTrue(OtpRelayUnpairWorker.isSettled(403));
    }

    @Test
    public void transientFailuresAreRetried() {
        assertFalse(OtpRelayUnpairWorker.isSettled(500));
        assertFalse(OtpRelayUnpairWorker.isSettled(503));
        assertFalse(OtpRelayUnpairWorker.isSettled(429));
    }
}
