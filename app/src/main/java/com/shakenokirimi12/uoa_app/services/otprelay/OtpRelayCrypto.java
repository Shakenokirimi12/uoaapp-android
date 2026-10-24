package com.shakenokirimi12.uoa_app.services.otprelay;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * PC 中継 (docs/otp-relay.md §3) の鍵導出と封緘。iOS の OTPRelayCrypto.swift と同じ計算。
 *
 * QR の K (32 バイト) から HMAC-SHA256 で 3 つを作る:
 *   requestToken = base64url(HMAC(K, "uoa-otp-relay/request"))  拡張が依頼に使う
 *   respondToken = base64url(HMAC(K, "uoa-otp-relay/respond"))  この端末が応答に使う
 *   encKey       = HMAC(K, "uoa-otp-relay/encrypt")             AES-256-GCM の鍵 (送らない)
 *   pageKey      = HMAC(K, "uoa-otp-relay/page")                requestId の目印を確かめる鍵 (送らない)
 * 中継サーバはトークンしか知らないので、コードを復号できず、目印も作れない。
 */
public final class OtpRelayCrypto {
    private OtpRelayCrypto() {}

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** PC の OTP 画面の種類 (requestId の目印)。値は box の method にもそのまま使う。 */
    public static final String PAGE_TOTP = "totp";
    public static final String PAGE_MAIL = "mail";

    public static final class Keys {
        @NonNull public final String requestToken;
        @NonNull public final String respondToken;
        @NonNull final byte[] encKey;
        @NonNull final byte[] pageKey;

        Keys(@NonNull String requestToken, @NonNull String respondToken, @NonNull byte[] encKey, @NonNull byte[] pageKey) {
            this.requestToken = requestToken;
            this.respondToken = respondToken;
            this.encKey = encKey;
            this.pageKey = pageKey;
        }
    }

    /** K (base64url) から導出する。32 バイトでなければ null。 */
    @Nullable
    public static Keys keys(@NonNull String keyBase64Url) {
        byte[] raw = decode(keyBase64Url);
        if (raw == null || raw.length != 32) return null;
        try {
            return new Keys(
                    encode(hmac(raw, "uoa-otp-relay/request")),
                    encode(hmac(raw, "uoa-otp-relay/respond")),
                    hmac(raw, "uoa-otp-relay/encrypt"),
                    hmac(raw, "uoa-otp-relay/page"));
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /**
     * requestId (16 バイト) = 乱数 12 バイト ‖ HMAC(pageKey, 乱数 ‖ page) の先頭 4 バイト なら、その page
     * (PAGE_TOTP / PAGE_MAIL)。目印が無い (古い拡張・画面の種類が読めなかった) か合わなければ null。
     */
    @Nullable
    public static String page(@NonNull String requestId, @NonNull Keys keys) {
        byte[] bytes = decode(requestId);
        if (bytes == null || bytes.length != 16) return null;
        byte[] tag = java.util.Arrays.copyOfRange(bytes, 12, 16);
        for (String page : new String[] {PAGE_TOTP, PAGE_MAIL}) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(keys.pageKey, "HmacSHA256"));
                mac.update(bytes, 0, 12);
                byte[] expected = java.util.Arrays.copyOf(mac.doFinal(page.getBytes(StandardCharsets.UTF_8)), 4);
                if (java.security.MessageDigest.isEqual(expected, tag)) return page;
            } catch (GeneralSecurityException e) {
                return null;
            }
        }
        return null;
    }

    /** box = base64url(nonce(12) ‖ ciphertext ‖ tag(16))。AAD は requestId。中身は {"code":"..","method":".."}。 */
    @NonNull
    public static String seal(@NonNull String code, @NonNull String method, @NonNull String requestId,
                              @NonNull Keys keys) throws GeneralSecurityException {
        return sealJson(json("code", code, "method", method), requestId, keys, randomNonce());
    }

    /**
     * コードを作らずに断った理由 ({"error":"..","method":".."}、method はこの端末の方式で省略可)。
     * 中継サーバの failed の理由は 5 種類に決まっているので、方式の食い違いは暗号文の中で伝える。
     */
    @NonNull
    public static String sealRefusal(@NonNull String error, @Nullable String method, @NonNull String requestId,
                                     @NonNull Keys keys) throws GeneralSecurityException {
        return sealJson(json("error", error, "method", method), requestId, keys, randomNonce());
    }

    private static byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    /** 以前の形 ({"code":".."} だけ)。テストベクタの突き合わせ用。 */
    @NonNull
    static String seal(@NonNull String code, @NonNull String requestId, @NonNull Keys keys, @NonNull byte[] nonce)
            throws GeneralSecurityException {
        return sealJson(plaintext(code), requestId, keys, nonce);
    }

    @NonNull
    static String sealJson(@NonNull String json, @NonNull String requestId, @NonNull Keys keys, @NonNull byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.encKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(requestId.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] box = new byte[nonce.length + sealed.length];
        System.arraycopy(nonce, 0, box, 0, nonce.length);
        System.arraycopy(sealed, 0, box, nonce.length, sealed.length);
        return encode(box);
    }

    /** {"code":"<OTP>"}。キーは 1 つなので sortedKeys と同じ形。 */
    @NonNull
    static String plaintext(@NonNull String code) {
        return json("code", code, null, null);
    }

    /**
     * {"k1":"v1","k2":"v2"} (v2 が null なら k2 は省く)。iOS の JSONSerialization(.sortedKeys) と同じ並びになるよう、
     * 呼び出し側はキーを辞書順で渡す。値は数字や英小文字だけだが、念のためエスケープする。
     */
    @NonNull
    static String json(@NonNull String k1, @NonNull String v1, @Nullable String k2, @Nullable String v2) {
        StringBuilder sb = new StringBuilder("{");
        appendPair(sb, k1, v1);
        if (k2 != null && v2 != null) appendPair(sb.append(','), k2, v2);
        return sb.append('}').toString();
    }

    private static void appendPair(StringBuilder sb, String key, String value) {
        appendString(sb, key);
        sb.append(':');
        appendString(sb, value);
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        sb.append('"');
    }

    private static byte[] hmac(byte[] key, String label) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    public static String encode(@NonNull byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** base64url (パディング有無どちらも可)。不正なら null。 */
    @Nullable
    public static byte[] decode(@Nullable String s) {
        if (s == null) return null;
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
