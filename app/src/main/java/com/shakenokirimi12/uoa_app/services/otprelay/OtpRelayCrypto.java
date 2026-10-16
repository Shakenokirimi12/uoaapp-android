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
 * 中継サーバはトークンしか知らないので、コードを復号できない。
 */
public final class OtpRelayCrypto {
    private OtpRelayCrypto() {}

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class Keys {
        @NonNull public final String requestToken;
        @NonNull public final String respondToken;
        @NonNull final byte[] encKey;

        Keys(@NonNull String requestToken, @NonNull String respondToken, @NonNull byte[] encKey) {
            this.requestToken = requestToken;
            this.respondToken = respondToken;
            this.encKey = encKey;
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
                    hmac(raw, "uoa-otp-relay/encrypt"));
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /** box = base64url(nonce(12) ‖ ciphertext ‖ tag(16))。AAD は requestId。 */
    @NonNull
    public static String seal(@NonNull String code, @NonNull String requestId, @NonNull Keys keys)
            throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return seal(code, requestId, keys, nonce);
    }

    @NonNull
    static String seal(@NonNull String code, @NonNull String requestId, @NonNull Keys keys, @NonNull byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.encKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(requestId.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = cipher.doFinal(plaintext(code).getBytes(StandardCharsets.UTF_8));
        byte[] box = new byte[nonce.length + sealed.length];
        System.arraycopy(nonce, 0, box, 0, nonce.length);
        System.arraycopy(sealed, 0, box, nonce.length, sealed.length);
        return encode(box);
    }

    /** {"code":"<OTP>"}。キーは 1 つなので sortedKeys と同じ形。コードは数字だが念のためエスケープする。 */
    @NonNull
    static String plaintext(@NonNull String code) {
        StringBuilder sb = new StringBuilder("{\"code\":\"");
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.append("\"}").toString();
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
