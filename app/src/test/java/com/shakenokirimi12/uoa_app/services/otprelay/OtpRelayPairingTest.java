package com.shakenokirimi12.uoa_app.services.otprelay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

/** 依頼が来たときにメールを読んで返してよいか (連携ごとの同意) と、以前の版の保存データの読み方。 */
public class OtpRelayPairingTest {
    private static final Gson GSON = new Gson();

    @Test
    public void confirmedPairing_recordsConsent() {
        OtpRelayPairing p = new OtpRelayPairing("P", "K", "Mac の Chrome", 1_000L);
        assertTrue(p.hasMailConsent());
    }

    @Test
    public void legacyAutoApprove_isDropped_andConsentDecides() {
        // 以前の版の「毎回承認」(autoApprove false) でも、同意があれば自動で返す。
        OtpRelayPairing manual = GSON.fromJson(
                "{\"pairingId\":\"P\",\"key\":\"K\",\"browserName\":\"B\",\"pairedAt\":1,"
                        + "\"autoApprove\":false,\"mailConsentAt\":5}", OtpRelayPairing.class);
        assertTrue(manual.hasMailConsent());
        // 次に保存するときに autoApprove は消える。
        assertFalse(GSON.toJson(manual).contains("autoApprove"));
    }

    @Test
    public void pairingSavedBeforeConsentExisted_needsConsent() {
        // 同意の項目ができる前に保存された連携。自動の設定が true でも、確認が済むまでメールを読まない。
        OtpRelayPairing p = GSON.fromJson(
                "{\"pairingId\":\"P\",\"key\":\"K\",\"browserName\":\"B\",\"pairedAt\":1,\"autoApprove\":true}",
                OtpRelayPairing.class);
        assertFalse(p.hasMailConsent());
        p.mailConsentAt = 0L;
        assertFalse(p.hasMailConsent());
    }
}
