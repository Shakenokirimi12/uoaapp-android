package com.shakenokirimi12.uoa_app.services.otprelay;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 拡張が QR に出す連携用リンク {@code uoa-app://otp-pair#p=<pairingId>&k=<K>&n=<ブラウザ名>}。
 * iOS の OTPRelayService.parsePairingURL / sanitizeLabel と同じ規則。
 * 秘密 (K) はフラグメントに入れてある (サーバへ送られる部分に載せないため)。
 */
public final class OtpRelayLink {
    private OtpRelayLink() {}

    static final String DEFAULT_NAME = "PCのブラウザ";
    private static final int MAX_NAME_CODE_POINTS = 40;

    public static final class Pending {
        @NonNull public final String pairingId;
        @NonNull public final String key;
        @NonNull public final String browserName;

        Pending(@NonNull String pairingId, @NonNull String key, @NonNull String browserName) {
            this.pairingId = pairingId;
            this.key = key;
            this.browserName = browserName;
        }
    }

    /** 連携用のリンクでなければ null。p は 16 バイト、k は 32 バイトであること。 */
    @Nullable
    public static Pending parse(@Nullable String link) {
        if (link == null) return null;
        URI uri;
        try {
            uri = new URI(link.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"uoa-app".equals(uri.getScheme()) || !"otp-pair".equals(uri.getHost())) return null;
        String fragment = uri.getRawFragment();
        if (fragment == null) return null;
        // パーセントエンコードのまま & で割り、値ごとに戻す。先に戻すと名前に & や % が入ったときに崩れる。
        Map<String, String> items = new HashMap<>();
        for (String part : fragment.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String value = percentDecode(part.substring(eq + 1));
            items.put(part.substring(0, eq), value != null ? value : part.substring(eq + 1));
        }
        String p = items.get("p");
        String k = items.get("k");
        byte[] pb = OtpRelayCrypto.decode(p);
        byte[] kb = OtpRelayCrypto.decode(k);
        if (pb == null || pb.length != 16 || kb == null || kb.length != 32) return null;
        String name = sanitizeLabel(items.containsKey("n") ? items.get("n") : "");
        return new Pending(p, k, name.isEmpty() ? DEFAULT_NAME : name);
    }

    /**
     * 通知や確認ダイアログに出す名前。制御文字・書式文字 (改行、RTL 上書き U+202E、ゼロ幅文字など) を除き、
     * 40 コードポイントで切って前後の空白を落とす。中継サーバ側も同じ規則で切る。
     */
    @NonNull
    public static String sanitizeLabel(@NonNull String raw) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < raw.length() && count < MAX_NAME_CODE_POINTS; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT) continue;
            sb.appendCodePoint(cp);
            count++;
        }
        return trimWhitespace(sb.toString());
    }

    private static String trimWhitespace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) start++;
        while (end > start && isSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** %XX を UTF-8 として戻す ("+" は空白にしない。iOS の removingPercentEncoding と同じ)。不正なら null。 */
    @Nullable
    static String percentDecode(@NonNull String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) return null;
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return null;
                out.write(hi * 16 + lo);
                i += 3;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                i++;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
