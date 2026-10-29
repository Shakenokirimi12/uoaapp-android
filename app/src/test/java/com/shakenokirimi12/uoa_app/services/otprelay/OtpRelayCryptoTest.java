package com.shakenokirimi12.uoa_app.services.otprelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** SPEC.md「10.3 鍵と暗号」と iOS の OTPRelayCryptoTests と同じベクタ。 */
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
        String box = OtpRelayCrypto.seal("048213", OtpRelayCrypto.PAGE_MAIL, "REQ", OtpRelayCrypto.keys(K));
        // 12 (nonce) + 96 ({"code":"048213","method":"mail"} を空白で 96 バイトに埋めたもの) + 16 (tag)
        assertEquals(124, OtpRelayCrypto.decode(box).length);
    }

    /** 拡張と iOS と同じ値: 乱数 0xc0〜0xcb に「totp」「mail」の目印を付けた requestId。 */
    private static final String TOTP_REQUEST_ID = "wMHCw8TFxsfIycrLBdeCIA";
    private static final String MAIL_REQUEST_ID = "wMHCw8TFxsfIycrL7lC2sA";

    @Test
    public void readsThePageFromTheRequestIdLikeTheExtension() {
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(K);
        assertEquals(OtpRelayCrypto.PAGE_TOTP, OtpRelayCrypto.page(TOTP_REQUEST_ID, keys));
        assertEquals(OtpRelayCrypto.PAGE_MAIL, OtpRelayCrypto.page(MAIL_REQUEST_ID, keys));
        // 目印の無い (古い拡張の) requestId、形の違う requestId、ほかの連携の鍵では読めない。
        assertNull(OtpRelayCrypto.page("AAAAAAAAAAAAAAAAAAAAAA", keys));
        assertNull(OtpRelayCrypto.page("REQ0000000000000000000", keys));
        assertNull(OtpRelayCrypto.page("short", keys));
        assertNull(OtpRelayCrypto.page(TOTP_REQUEST_ID, OtpRelayCrypto.keys("H" + K.substring(1))));
    }

    @Test
    public void sealsTheSameV2BoxesAsIos() throws Exception {
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (0xa0 + i);
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(K);
        assertEquals("oKGio6SlpqeoqaqrML4K6LX4cIhtPN_P_Ebrh8I5-94QuposHVSmQWLrAGtlJpmI5WL2Cb1twZI95aw5oEnjvntVt_I-2TCxQHKeI3jcmneQtAhLtJEEjBrN7LAeTOMvs8jxkiU2p7G_VSgHXrkT5ywc6owmfetRMzMqKw",
                OtpRelayCrypto.sealJson(OtpRelayCrypto.padded(OtpRelayCrypto.json("code", "048213", "method", "totp")),
                        TOTP_REQUEST_ID, keys, nonce));
        assertEquals("oKGio6SlpqeoqaqrML4M9aPyIJB1LoaSuh-3wcN2_8gJs4ErV0yoF2D6BCF3YpuS5za5Xe1vnJI95aw5oEnjvntVt_I-2TCxQHKeI3jcmneQtAhLtJEEjBrN7LAeTOMvs8jxkiU2p7G_VSgHwHPq6GHjwgF_vTEVFmnHUA",
                OtpRelayCrypto.sealJson(OtpRelayCrypto.padded(OtpRelayCrypto.json("error", "method-mismatch", "method", "totp")),
                        MAIL_REQUEST_ID, keys, nonce));
        assertEquals(96, OtpRelayCrypto.padded("{}").length());
        assertEquals("{\"error\":\"no-totp-key\"}", OtpRelayCrypto.json("error", "no-totp-key", "method", null));
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
