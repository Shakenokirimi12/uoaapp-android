package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECField;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 時間割共有 v3 の署名 (docs/timetable-share-v3.md の 3・10.2)。ECDSA P-256 / SHA-256。
 * サーバー (uoa-app-push src/timetable-share.ts) と iOS と同じ規則で読み書きする。Android の API に依存させない
 * (java.util.Base64 と java.security だけ。JVM の単体テストで動かすため)。
 */
public final class ShareCrypto {
    public static final String SHARE_SIGNING_PREFIX = "uoa-timetable-share-v3\n";
    public static final String FEED_SIGNING_PREFIX = "uoa-timetable-feed\n";
    /** サーバーの importShareKey と同じ上限。 */
    private static final int MAX_PUBLIC_KEY_BYTES = 200;
    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");

    // P-256 (secp256r1) の値。体の大きさだけでは secp256k1 も 256 ビットなので、曲線そのものを比べる。
    private static final BigInteger P256_P =
            new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger P256_A =
            new BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16);
    private static final BigInteger P256_B =
            new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    private static final BigInteger P256_N =
            new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);

    private ShareCrypto() {}

    // ── base64url (パディングなし) ──

    @NonNull
    public static String base64url(@NonNull byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** パディングなしの base64url だけを読む (サーバーの base64urlDecode と同じ)。読めなければ null。 */
    @Nullable
    public static byte[] base64urlDecode(@Nullable String text) {
        if (text == null || !BASE64URL.matcher(text).matches() || text.length() % 4 == 1) return null;
        try {
            return Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── 公開鍵 ──

    /**
     * 受け取った公開鍵 (SPKI DER の base64url) を読む。P-256 で、書き出し直して同じバイト列になるものだけ。
     * 同じ鍵が同じ文字列になるようにするため (同じ人の判定は文字列で比べる)。
     */
    @Nullable
    public static PublicKey parsePublicKey(@Nullable String publicKey) {
        byte[] der = base64urlDecode(publicKey);
        if (der == null || der.length > MAX_PUBLIC_KEY_BYTES) return null;
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof ECPublicKey) || !isP256(((ECPublicKey) key).getParams())) return null;
            return Arrays.equals(key.getEncoded(), der) ? key : null;
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isP256(@Nullable ECParameterSpec params) {
        if (params == null) return false;
        ECField field = params.getCurve().getField();
        return field instanceof ECFieldFp && field.getFieldSize() == 256
                && ((ECFieldFp) field).getP().equals(P256_P)
                && params.getCurve().getA().equals(P256_A)
                && params.getCurve().getB().equals(P256_B)
                && params.getOrder().equals(P256_N);
    }

    // ── 署名 ──

    /** P1363 (r||s の 64 バイト、base64url) の署名を確かめる。64 バイトでなければ false。 */
    public static boolean verify(@NonNull PublicKey key, @Nullable String signature, @NonNull byte[] data) {
        byte[] p1363 = base64urlDecode(signature);
        if (p1363 == null || p1363.length != 64) return false;
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(p1363ToDer(p1363));
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** 秘密鍵で署名し、P1363 の 64 バイトにして返す。Java の SHA256withECDSA は DER を返すため直す。 */
    @NonNull
    public static byte[] sign(@NonNull PrivateKey key, @NonNull byte[] data) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(data);
        return derToP1363(signer.sign());
    }

    /** DER (SEQUENCE { INTEGER r, INTEGER s }) を P1363 (r||s、それぞれ 32 バイトに左を 0 で詰める) に直す。 */
    @NonNull
    public static byte[] derToP1363(@NonNull byte[] der) throws GeneralSecurityException {
        int[] pos = {0};
        if (der.length < 8 || der[pos[0]++] != 0x30) throw new GeneralSecurityException("not a DER sequence");
        int seqLen = readLength(der, pos);
        if (pos[0] + seqLen != der.length) throw new GeneralSecurityException("bad DER length");
        byte[] r = readInteger(der, pos);
        byte[] s = readInteger(der, pos);
        if (pos[0] != der.length) throw new GeneralSecurityException("trailing DER bytes");
        byte[] out = new byte[64];
        copyFixed(r, out, 0);
        copyFixed(s, out, 32);
        return out;
    }

    /** P1363 (64 バイト) を DER に直す。確かめるとき (Java の Signature は DER を受ける) に使う。 */
    @NonNull
    public static byte[] p1363ToDer(@NonNull byte[] p1363) throws GeneralSecurityException {
        if (p1363.length != 64) throw new GeneralSecurityException("P1363 signature must be 64 bytes");
        byte[] r = derInteger(Arrays.copyOfRange(p1363, 0, 32));
        byte[] s = derInteger(Arrays.copyOfRange(p1363, 32, 64));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.write(r.length + s.length); // 最大 70 バイトなので、長さは 1 バイトで足りる
        out.write(r, 0, r.length);
        out.write(s, 0, s.length);
        return out.toByteArray();
    }

    private static int readLength(@NonNull byte[] der, int[] pos) throws GeneralSecurityException {
        if (pos[0] >= der.length) throw new GeneralSecurityException("short DER");
        int first = der[pos[0]++] & 0xff;
        if (first < 0x80) return first;
        if (first != 0x81 || pos[0] >= der.length) throw new GeneralSecurityException("unsupported DER length");
        return der[pos[0]++] & 0xff;
    }

    @NonNull
    private static byte[] readInteger(@NonNull byte[] der, int[] pos) throws GeneralSecurityException {
        if (pos[0] >= der.length || der[pos[0]++] != 0x02) throw new GeneralSecurityException("not a DER integer");
        int len = readLength(der, pos);
        if (len == 0 || pos[0] + len > der.length) throw new GeneralSecurityException("bad DER integer");
        byte[] value = Arrays.copyOfRange(der, pos[0], pos[0] + len);
        pos[0] += len;
        return value;
    }

    /** 先頭の 0 を落として 32 バイトの右詰めに置く。32 バイトに収まらなければ壊れた署名。 */
    private static void copyFixed(@NonNull byte[] value, @NonNull byte[] out, int offset) throws GeneralSecurityException {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) start++;
        int len = value.length - start;
        if (len > 32) throw new GeneralSecurityException("integer too large");
        System.arraycopy(value, start, out, offset + 32 - len, len);
    }

    /** 32 バイトの符号なし整数を DER の INTEGER にする (先頭の 0 を落とし、最上位ビットが立てば 0 を足す)。 */
    @NonNull
    private static byte[] derInteger(@NonNull byte[] unsigned) {
        int start = 0;
        while (start < unsigned.length - 1 && unsigned[start] == 0) start++;
        boolean pad = (unsigned[start] & 0x80) != 0;
        int len = unsigned.length - start + (pad ? 1 : 0);
        byte[] out = new byte[2 + len];
        out[0] = 0x02;
        out[1] = (byte) len;
        if (pad) out[2] = 0;
        System.arraycopy(unsigned, start, out, 2 + (pad ? 1 : 0), unsigned.length - start);
        return out;
    }

    // ── 署名の対象 ──

    @NonNull
    public static byte[] shareSigningInput(@NonNull byte[] timetableJson) {
        byte[] prefix = SHARE_SIGNING_PREFIX.getBytes(StandardCharsets.UTF_8);
        byte[] out = Arrays.copyOf(prefix, prefix.length + timetableJson.length);
        System.arraycopy(timetableJson, 0, out, prefix.length, timetableJson.length);
        return out;
    }

    /**
     * フィードの操作の署名の対象 (10.1)。"uoa-timetable-feed\n" + op + "\n" + feedId + "\n" + 版番号 + "\n" + 中身。
     * 中身は create / put では共有の timetable (base64url の文字列のまま)、delete では空。
     */
    @NonNull
    public static byte[] feedSigningInput(@NonNull String op, @NonNull String feedId, long version, @NonNull String content) {
        return (FEED_SIGNING_PREFIX + op + "\n" + feedId + "\n" + version + "\n" + content).getBytes(StandardCharsets.UTF_8);
    }
}
