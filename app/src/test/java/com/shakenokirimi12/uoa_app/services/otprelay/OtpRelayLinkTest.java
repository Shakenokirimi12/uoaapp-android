package com.shakenokirimi12.uoa_app.services.otprelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** iOS の OTPRelayService のパーサのテストと同じ入力。 */
public class OtpRelayLinkTest {
    private static final String P = "AAAAAAAAAAAAAAAAAAAAAA";            // 16 バイト
    private static final String K = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"; // 32 バイト

    @Test
    public void parsesNameWithPercentEncoding() {
        OtpRelayLink.Pending p = OtpRelayLink.parse("uoa-app://otp-pair#p=" + P + "&k=" + K + "&n=Mac%20%E3%81%AE%20Chrome");
        assertNotNull(p);
        assertEquals(P, p.pairingId);
        assertEquals(K, p.key);
        assertEquals("Mac の Chrome", p.browserName);
    }

    @Test
    public void rejectsWrongLengthsSchemesAndHosts() {
        assertNull(OtpRelayLink.parse("uoa-app://otp-pair#p=short&k=" + K));
        assertNull(OtpRelayLink.parse("uoa-app://otp-pair#p=" + P + "&k=AAECAwQF"));
        assertNull(OtpRelayLink.parse("https://otp-pair#p=" + P + "&k=" + K));
        assertNull(OtpRelayLink.parse("uoa-app://other#p=" + P + "&k=" + K));
        assertNull(OtpRelayLink.parse("uoa-app://otp-pair?p=" + P + "&k=" + K));
        assertNull(OtpRelayLink.parse("garbage"));
        assertNull(OtpRelayLink.parse(null));
    }

    @Test
    public void missingName_usesDefault() {
        OtpRelayLink.Pending p = OtpRelayLink.parse("uoa-app://otp-pair#p=" + P + "&k=" + K);
        assertNotNull(p);
        assertEquals("PCのブラウザ", p.browserName);
    }

    @Test
    public void sanitizesControlAndFormatCharacters() {
        // & は残し、改行 (Cc) と RTL 上書き U+202E (Cf) を落とす。% はそのまま。
        OtpRelayLink.Pending p = OtpRelayLink.parse("uoa-app://otp-pair#p=" + P + "&k=" + K + "&n=A%26B%0A%E2%80%AEx%25");
        assertNotNull(p);
        assertEquals("A&Bx%", p.browserName);
    }

    @Test
    public void truncatesTo40CodePoints() {
        StringBuilder fifty = new StringBuilder();
        for (int i = 0; i < 50; i++) fifty.append('あ');
        assertEquals(40, OtpRelayLink.sanitizeLabel(fifty.toString()).codePointCount(0, 40));
        assertEquals(40, OtpRelayLink.sanitizeLabel(fifty.toString()).length());
        assertEquals("x", OtpRelayLink.sanitizeLabel("  x ​"));
    }
}
