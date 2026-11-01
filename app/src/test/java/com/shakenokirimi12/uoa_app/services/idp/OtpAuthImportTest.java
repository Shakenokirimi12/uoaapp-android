package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Google 認証システムの移行 QR (otpauth-migration) と otpauth の読み取り。iOS の OTPAuthImportTests と同じ。
 * 鍵はすべて架空のもの (RFC 6238 のテスト用の鍵)。実在のアカウントの鍵を置かない。
 */
public class OtpAuthImportTest {
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    // ---- protobuf を組み立てる (テスト用) ----

    private static void varint(ByteArrayOutputStream out, long value) {
        long v = value;
        do {
            int b = (int) (v & 0x7f);
            v >>>= 7;
            if (v != 0) b |= 0x80;
            out.write(b);
        } while (v != 0);
    }

    private static byte[] bytesField(int number, byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, (long) number << 3 | 2);
        varint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }

    private static byte[] varintField(int number, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, (long) number << 3);
        varint(out, value);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] parameters(byte[] secret, String name, String issuer, long algorithm, long digits, long type) {
        return concat(bytesField(1, secret), bytesField(2, name.getBytes(StandardCharsets.UTF_8)),
                bytesField(3, issuer.getBytes(StandardCharsets.UTF_8)), varintField(4, algorithm),
                varintField(5, digits), varintField(6, type), varintField(7, 0));
    }

    private static byte[] totp(byte[] secret, String name, String issuer) {
        return parameters(secret, name, issuer, 1, 1, 2);
    }

    private static String migrationUrl(byte[]... entries) throws Exception {
        List<byte[]> parts = new ArrayList<>();
        for (byte[] e : entries) parts.add(bytesField(1, e));
        // version / batch_size / batch_index / batch_id (読み飛ばす欄)
        parts.add(concat(varintField(2, 1), varintField(3, 1), varintField(4, 0), varintField(5, 12345)));
        String base64 = Base64.getEncoder().encodeToString(concat(parts.toArray(new byte[0][])));
        return "otpauth-migration://offline?data=" + URLEncoder.encode(base64, StandardCharsets.UTF_8.name());
    }

    // ----

    @Test
    public void readsTotpAccountsFromAMigrationQr() throws Exception {
        String url = migrationUrl(totp(RFC_SECRET, "s1300000", "u-aizu"),
                parameters("another-fake-key-000".getBytes(StandardCharsets.US_ASCII), "someone@example.com", "Example", 2, 2, 2));
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accounts(url);
        assertNotNull(accounts);
        assertEquals(2, accounts.size());
        assertEquals("s1300000", accounts.get(0).name);
        assertEquals("u-aizu", accounts.get(0).issuer);
        Totp.Params first = Totp.parse(accounts.get(0).otpauthUrl);
        assertNotNull(first);
        assertArrayEquals(RFC_SECRET, first.key);
        assertEquals("SHA1", first.algorithm);
        assertEquals(6, first.digits);
        assertEquals(30, first.periodSeconds);
        Totp.Params second = Totp.parse(accounts.get(1).otpauthUrl);
        assertNotNull(second);
        assertEquals("SHA256", second.algorithm);
        assertEquals(8, second.digits);
        // RFC 6238 のベクタ (8 桁、T=59) と同じコードになる
        assertEquals("94287082", Totp.code(new Totp.Params(first.key, "SHA1", 8, 30), 59_000L));
    }

    @Test
    public void skipsHotpAndUnsupportedKeys() throws Exception {
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accounts(migrationUrl(
                parameters(RFC_SECRET, "hotp", "X", 1, 1, 1),
                parameters(RFC_SECRET, "md5", "X", 4, 1, 2),
                totp(RFC_SECRET, "ok", "X")));
        assertNotNull(accounts);
        assertEquals(1, accounts.size());
        assertEquals("ok", accounts.get(0).name);
    }

    @Test
    public void stripsTheIssuerPrefixFromTheName() throws Exception {
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accounts(migrationUrl(totp(RFC_SECRET, "u-aizu:s1300000", "u-aizu")));
        assertNotNull(accounts);
        assertEquals("s1300000", accounts.get(0).name);
    }

    @Test
    public void toleratesPlusSignsTurnedIntoSpaces() throws Exception {
        String original = migrationUrl(totp(RFC_SECRET, "s1300000", "u-aizu"));
        String mangled = original.replace("%2B", "+");
        List<OtpAuthImport.Account> a = OtpAuthImport.accounts(original);
        List<OtpAuthImport.Account> b = OtpAuthImport.accounts(mangled);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.get(0).otpauthUrl, b.get(0).otpauthUrl);
    }

    @Test
    public void rejectsBrokenPayloads() {
        assertNull(OtpAuthImport.accounts("otpauth-migration://offline?data=%%%"));
        assertNull(OtpAuthImport.accounts("otpauth-migration://offline"));
        // 長さが中身より長い
        String truncated = Base64.getEncoder().encodeToString(new byte[]{0x0a, 0x40, 0x01});
        assertNull(OtpAuthImport.accounts("otpauth-migration://offline?data=" + truncated));
        assertNull(OtpAuthImport.accounts("https://example.com/"));
        assertNull(OtpAuthImport.accounts("not a url"));
    }

    @Test
    public void readsASingleOtpauthQr() {
        String secret = OtpAuthImport.base32Encode(RFC_SECRET);
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accounts("otpauth://totp/u-aizu:s1300000?secret=" + secret + "&issuer=u-aizu");
        assertNotNull(accounts);
        assertEquals(1, accounts.size());
        assertEquals("s1300000", accounts.get(0).name);
        assertEquals("u-aizu", accounts.get(0).issuer);
        assertArrayEquals(RFC_SECRET, Totp.parse(accounts.get(0).otpauthUrl).key);
    }

    @Test
    public void listsLikelyUniversityAccountsFirst() throws Exception {
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accounts(migrationUrl(
                totp(RFC_SECRET, "someone@example.com", "Example"), totp(RFC_SECRET, "s1300000", "slink.secioss.com"),
                totp(RFC_SECRET, "other", "Other")));
        assertNotNull(accounts);
        List<OtpAuthImport.Account> ordered = OtpAuthImport.ordered(accounts, "s1300000");
        assertEquals("slink.secioss.com", ordered.get(0).issuer);
        assertEquals("Example", ordered.get(1).issuer);
        assertEquals("Other", ordered.get(2).issuer);
    }

    @Test
    public void readsAPayloadWithANegativeBatchId() throws Exception {
        // batch_id (int32) が負だと、protobuf は 10 バイトの varint にする。
        byte[] payload = concat(bytesField(1, totp(RFC_SECRET, "s1300000", "u-aizu")), varintField(5, -1L));
        String url = "otpauth-migration://offline?data="
                + URLEncoder.encode(Base64.getEncoder().encodeToString(payload), StandardCharsets.UTF_8.name());
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accounts(url);
        assertNotNull(accounts);
        assertEquals(1, accounts.size());
    }

    @Test
    public void skipsOnlyTheAccountWithAnOutOfRangeEnum() throws Exception {
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accounts(migrationUrl(
                parameters(RFC_SECRET, "bad", "X", -1L, 1, 2), totp(RFC_SECRET, "ok", "X")));
        assertNotNull(accounts);
        assertEquals(1, accounts.size());
        assertEquals("ok", accounts.get(0).name);
    }

    @Test
    public void descriptionDoesNotLeakTheKey() throws Exception {
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accounts(migrationUrl(totp(RFC_SECRET, "s1300000", "u-aizu")));
        assertNotNull(accounts);
        assertFalse(accounts.get(0).toString().contains(OtpAuthImport.base32Encode(RFC_SECRET)));
    }

    @Test
    public void base32EncodesRfc4648Vectors() {
        assertEquals("MZXW6YTBOI", OtpAuthImport.base32Encode("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MY", OtpAuthImport.base32Encode("f".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void readsAPastedSetupKeyWithDefaults() {
        // 2FAS などは鍵だけをコピーする。空白やハイフン、小文字が混ざっていても読む (iOS と同じ)。
        String secret = OtpAuthImport.base32Encode(RFC_SECRET);
        StringBuilder spaced = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) {
            spaced.append(Character.toLowerCase(secret.charAt(i)));
            if (i % 4 == 3) spaced.append(i % 8 == 7 ? '-' : ' ');
        }
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accountsFromPasted(spaced.toString());
        assertNotNull(accounts);
        assertEquals(1, accounts.size());
        Totp.Params params = Totp.parse(accounts.get(0).otpauthUrl);
        assertNotNull(params);
        assertArrayEquals(RFC_SECRET, params.key);
        assertEquals(6, params.digits);
        assertEquals(30, params.periodSeconds);
        assertNull(OtpAuthImport.accountsFromPasted("ABCDEFGH"));
        assertNull(OtpAuthImport.accountsFromPasted("not a key at all, sorry"));
        assertNull(OtpAuthImport.accountsFromPasted("please copy the code"));
        // 全角空白で区切った鍵も読む (iOS と同じ)。
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < secret.length(); i += 4) {
            if (i > 0) wide.append('\u3000');
            wide.append(secret, i, Math.min(i + 4, secret.length()));
        }
        assertNotNull(OtpAuthImport.accountsFromPasted(wide.toString()));
    }

    @Test
    public void picksOtpauthUrlsOutOfPastedText() {
        // Bitwarden Authenticator の書き出し (JSON / CSV) の一部をそのまま貼ったとき。
        String secret = OtpAuthImport.base32Encode(RFC_SECRET);
        String json = "{\"items\":[{\"login\":{\"totp\":\"otpauth://totp/u-aizu:s1300000?secret=" + secret
                + "&issuer=u-aizu\"}},{\"login\":{\"totp\":\"otpauth://totp/Other:x?secret=" + secret + "\"}}]}";
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accountsFromPasted(json);
        assertNotNull(accounts);
        assertEquals(2, accounts.size());
        assertEquals("s1300000", accounts.get(0).name);
        assertEquals("x", accounts.get(1).name);
    }

    /** HOTP の URL だけを貼ったら、読めない (null) ではなく TOTP が無い (空) にする (iOS と同じ)。 */
    @Test
    public void pastedHotpUrlMeansNoTotp() {
        String secret = OtpAuthImport.base32Encode(RFC_SECRET);
        List<OtpAuthImport.Account> hotp = OtpAuthImport.accountsFromPasted("otpauth://hotp/u-aizu:s1300000?secret=" + secret + "&counter=1");
        assertNotNull(hotp);
        assertTrue(hotp.isEmpty());
        assertNull(OtpAuthImport.accountsFromPasted("otpauth://totp/u-aizu:s1300000"));
    }

    @Test
    public void pastedUrlsKeepCommasInTheLabelAndStopAtCsvFields() {
        String secret = OtpAuthImport.base32Encode(RFC_SECRET);
        // ラベルにカンマがある URL も読む (iOS と同じ)。
        List<OtpAuthImport.Account> named = OtpAuthImport.accountsFromPasted("otpauth://totp/u-aizu:Doe,John?secret=" + secret);
        assertNotNull(named);
        assertEquals(1, named.size());
        assertEquals("Doe,John", named.get(0).name);
        // CSV の行: URL の後ろの欄は読まない。カンマで続く 2 つ目の URL も拾う。
        String csv = "u-aizu,otpauth://totp/u-aizu:s1300000?secret=" + secret + "&issuer=u-aizu,otpauth://totp/Other:x?secret="
                + secret + ",note";
        List<OtpAuthImport.Account> accounts = OtpAuthImport.accountsFromPasted(csv);
        assertNotNull(accounts);
        assertEquals(2, accounts.size());
        assertEquals("s1300000", accounts.get(0).name);
        assertEquals("x", accounts.get(1).name);
        assertArrayEquals(RFC_SECRET, Totp.parse(accounts.get(0).otpauthUrl).key);
    }
}
