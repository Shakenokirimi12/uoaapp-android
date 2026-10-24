package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** RFC 6238 付録 B のテストベクタ (8 桁) と、otpauth URL の読み取り。 */
public class TotpTest {
    private static final byte[] SHA1_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA256_KEY = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA512_KEY =
            "1234567890123456789012345678901234567890123456789012345678901234".getBytes(StandardCharsets.US_ASCII);

    private static String at(byte[] key, String alg, long seconds) {
        return Totp.code(key, alg, 8, seconds / 30);
    }

    @Test
    public void rfc6238Sha1Vectors() {
        assertEquals("94287082", at(SHA1_KEY, "SHA1", 59));
        assertEquals("07081804", at(SHA1_KEY, "SHA1", 1111111109L));
        assertEquals("14050471", at(SHA1_KEY, "SHA1", 1111111111L));
        assertEquals("89005924", at(SHA1_KEY, "SHA1", 1234567890L));
        assertEquals("69279037", at(SHA1_KEY, "SHA1", 2000000000L));
        assertEquals("65353130", at(SHA1_KEY, "SHA1", 20000000000L));
    }

    @Test
    public void rfc6238Sha256AndSha512Vectors() {
        assertEquals("46119246", at(SHA256_KEY, "SHA256", 59));
        assertEquals("91819424", at(SHA256_KEY, "SHA256", 1234567890L));
        assertEquals("90693936", at(SHA512_KEY, "SHA512", 59));
        assertEquals("93441116", at(SHA512_KEY, "SHA512", 1234567890L));
    }

    @Test
    public void otpauthUrlDefaultsToSha1SixDigitsThirtySeconds() {
        // "12345678901234567890" の Base32
        String url = "otpauth://totp/SECIOSS:s1300000?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=SECIOSS";
        Totp.Params p = Totp.parse(url);
        assertNotNull(p);
        // 8 桁の 94287082 の下 6 桁
        assertEquals("287082", Totp.code(p, 59_000L));
        assertEquals(1_000L, Totp.millisLeftInWindow(p, 59_000L));
    }

    @Test
    public void otpauthUrlParametersAreHonoured() {
        String url = "otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8&period=30&algorithm=SHA1";
        assertEquals("94287082", Totp.code(Totp.parse(url), 59_000L));
    }

    @Test
    public void unreadableUrlsAreRejected() {
        assertNull(Totp.parse("otpauth://hotp/x?secret=GEZDGNBV"));
        assertNull(Totp.parse("otpauth://totp/x?issuer=a"));
        assertNull(Totp.parse("otpauth://totp/x?secret=not-base32!"));
        assertNull(Totp.parse(null));
    }

    @Test
    public void secretIsGroupedForManualEntry() {
        assertEquals("GEZD GNBV GY3T QOJQ",
                Totp.groupedSecret("otpauth://totp/x?secret=gezdgnbvgy3tqojq&issuer=a"));
    }

    @Test
    public void matchesAllowsOneWindowOfClockSkewOnly() {
        Totp.Params p = Totp.parse("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8");
        assertTrue(Totp.matches(p, "07081804", 1111111109_000L));
        assertTrue(Totp.matches(p, "07081804", (1111111109L + 30) * 1000L));
        assertFalse(Totp.matches(p, "07081804", (1111111109L + 90) * 1000L));
    }
}
