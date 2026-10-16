package com.shakenokirimi12.uoa_app.services.otprelay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

/** 依頼が来たときに承認なしでメールを読んでよいか (連携ごとの同意)。 */
public class OtpRelayPairingTest {
    private static final Gson GSON = new Gson();

    @Test
    public void confirmedPairing_recordsConsent() {
        OtpRelayPairing p = new OtpRelayPairing("P", "K", "Mac の Chrome", 1_000L);
        assertTrue(p.hasMailConsent());
    }

    @Test
    public void pairingSavedBeforeConsentExisted_neverReadsMailWithoutApproval() {
        // 同意の項目ができる前に保存された連携。自動の設定が true でも、承認されるまでメールを読まない。
        OtpRelayPairing p = GSON.fromJson(
                "{\"pairingId\":\"P\",\"key\":\"K\",\"browserName\":\"B\",\"pairedAt\":1,\"autoApprove\":true}",
                OtpRelayPairing.class);
        assertFalse(p.hasMailConsent());
        assertFalse(p.readsMailWithoutApproval());
    }

    @Test
    public void consentAndAuto_readsMailWithoutApproval() {
        OtpRelayPairing p = new OtpRelayPairing("P", "K", "B", 1_000L);
        p.autoApprove = true;
        assertTrue(p.readsMailWithoutApproval());
        p.autoApprove = false;
        assertFalse(p.readsMailWithoutApproval());
    }
}
