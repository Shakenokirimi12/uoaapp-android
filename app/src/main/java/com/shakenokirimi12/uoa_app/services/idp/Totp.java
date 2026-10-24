package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238)。IdP の認証アプリ登録で受け取った otpauth URL から、ログインのコードをアプリが作る。
 * 既定は SHA1・6 桁・30 秒で、URL に algorithm / digits / period があればそれに従う。
 * シークレットはログにもエラーメッセージにも出さない。
 */
public final class Totp {
    private Totp() {}

    public static final class Params {
        @NonNull final byte[] key;
        @NonNull final String algorithm;
        final int digits;
        final int periodSeconds;

        Params(@NonNull byte[] key, @NonNull String algorithm, int digits, int periodSeconds) {
            this.key = key;
            this.algorithm = algorithm;
            this.digits = digits;
            this.periodSeconds = periodSeconds;
        }

        public int periodSeconds() { return periodSeconds; }
    }

    /** otpauth://totp/...?secret=...。読めなければ null (理由は出さない: URL にシークレットが入っているため)。 */
    @Nullable
    public static Params parse(@Nullable String otpauthUrl) {
        if (otpauthUrl == null) return null;
        try {
            String query = totpQuery(otpauthUrl);
            if (query == null) return null;
            String secret = null;
            String algorithm = "SHA1";
            int digits = 6;
            int period = 30;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).toLowerCase(Locale.ROOT);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
                switch (name) {
                    case "secret": secret = value; break;
                    case "algorithm": algorithm = value.toUpperCase(Locale.ROOT); break;
                    case "digits": digits = Integer.parseInt(value); break;
                    case "period": period = Integer.parseInt(value); break;
                    default: break;
                }
            }
            byte[] key = secret != null ? base32Decode(secret) : null;
            if (key == null || key.length == 0) return null;
            if (!algorithm.equals("SHA1") && !algorithm.equals("SHA256") && !algorithm.equals("SHA512")) return null;
            if (digits < 6 || digits > 8 || period <= 0) return null;
            return new Params(key, algorithm, digits, period);
        } catch (Exception e) {
            return null;
        }
    }

    /** otpauth URL のシークレット (Base32) を、手で入力しやすいよう 4 文字ずつ区切って返す。無ければ null。 */
    @Nullable
    public static String groupedSecret(@Nullable String otpauthUrl) {
        if (otpauthUrl == null) return null;
        try {
            String query = totpQuery(otpauthUrl);
            if (query == null) return null;
            for (String pair : query.split("&")) {
                if (!pair.toLowerCase(Locale.ROOT).startsWith("secret=")) continue;
                String s = URLDecoder.decode(pair.substring(7), StandardCharsets.UTF_8.name())
                        .replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < s.length(); i += 4) {
                    if (out.length() > 0) out.append(' ');
                    out.append(s, i, Math.min(s.length(), i + 4));
                }
                return out.length() > 0 ? out.toString() : null;
            }
        } catch (Exception ignored) {
            // 読めなければ出さない
        }
        return null;
    }

    /**
     * otpauth://totp/<ラベル>?<クエリ> のクエリ部分。java.net.URI はラベルに空白などがあると読めないので、
     * 文字列として切り出す。totp でなければ null。
     */
    @Nullable
    private static String totpQuery(@NonNull String otpauthUrl) {
        String t = otpauthUrl.trim();
        if (!t.toLowerCase(Locale.ROOT).startsWith("otpauth://totp/")) return null;
        int q = t.indexOf('?');
        if (q < 0 || q == t.length() - 1) return null;
        int hash = t.indexOf('#', q);
        return hash < 0 ? t.substring(q + 1) : t.substring(q + 1, hash);
    }

    /** timeMillis のコード。 */
    @NonNull
    public static String code(@NonNull Params p, long timeMillis) {
        return code(p.key, p.algorithm, p.digits, Math.floorDiv(timeMillis / 1000L, p.periodSeconds));
    }

    /** code が timeMillis の窓か、その前後 1 つの窓のコードと一致するか (時計のずれを 1 窓まで許す)。 */
    public static boolean matches(@NonNull Params p, @NonNull String code, long timeMillis) {
        // 認証アプリは "123 456" のように区切って表示するので、空白は無視する。
        String c = code.replace(" ", "").trim();
        long step = Math.floorDiv(timeMillis / 1000L, p.periodSeconds);
        for (long d = -1; d <= 1; d++) {
            if (code(p.key, p.algorithm, p.digits, step + d).equals(c)) return true;
        }
        return false;
    }

    /** 今の窓が終わるまでのミリ秒。 */
    public static long millisLeftInWindow(@NonNull Params p, long timeMillis) {
        long periodMs = p.periodSeconds * 1000L;
        return periodMs - Math.floorMod(timeMillis, periodMs);
    }

    @NonNull
    static String code(@NonNull byte[] key, @NonNull String algorithm, int digits, long counter) {
        try {
            Mac mac = Mac.getInstance("Hmac" + algorithm);
            mac.init(new SecretKeySpec(key, "Hmac" + algorithm));
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (counter & 0xff);
                counter >>>= 8;
            }
            byte[] h = mac.doFinal(msg);
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
            int mod = 1;
            for (int i = 0; i < digits; i++) mod *= 10;
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % mod);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable: " + algorithm);
        }
    }

    /** RFC 4648 の Base32 (大文字小文字・空白・パディングは無視)。不正な文字があれば null。 */
    @Nullable
    static byte[] base32Decode(@NonNull String input) {
        String s = input.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(s.charAt(i));
            if (v < 0) return null;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
