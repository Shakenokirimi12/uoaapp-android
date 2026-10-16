package com.shakenokirimi12.uoa_app.services.otprelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** docs/otp-relay.md §3 と iOS の OTPRelayCryptoTests と同じベクタ。 */
public class OtpRelayCryptoTest {
    /** 0x00..0x1f */
    private static final String K = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Test
    public void derivesTheSameTokensAsIos() {
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(K);
        assertNotNull(keys);
        assertEquals("bbEmF7m--IEXYyof_ClXmkIOUhaVcBup4ZYLotntVWo", keys.requestToken);
        assertEquals("G5OzomdYgm_Yvhu8edKe3oOnlxQjj5tFLOYpMhRRXCE", keys.respondToken);
    }

    @Test
    public void sealsTheSameBoxAsIos() throws Exception {
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (0xa0 + i);
        String box = OtpRelayCrypto.seal("048213", "REQ0000000000000000000", OtpRelayCrypto.keys(K), nonce);
        assertEquals("oKGio6SlpqeoqaqrML4K6LX4cIhtPN_P_Ebrh5MAmOsWEJNzuO5aSWXCl_SE", box);
    }

    @Test
    public void randomNonce_boxHasNoncePlusTag() throws Exception {
        String box = OtpRelayCrypto.seal("048213", "REQ", OtpRelayCrypto.keys(K));
        // 12 (nonce) + 17 ({"code":"048213"}) + 16 (tag)
        assertEquals(45, OtpRelayCrypto.decode(box).length);
    }

    @Test
    public void rejectsKeysThatAreNot32Bytes() {
        assertNull(OtpRelayCrypto.keys("AAECAwQF"));
        assertNull(OtpRelayCrypto.keys("not base64 !!"));
    }

    @Test
    public void plaintextIsSortedKeyJson() {
        assertEquals("{\"code\":\"048213\"}", OtpRelayCrypto.plaintext("048213"));
    }
}
