package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 認証アプリにある鍵を取り込むための読み取り。IdP に登録し直さずに、この端末にも同じ鍵を持たせる。iOS の OTPAuthImport と同じ。
 * <ul>
 *     <li>Google 認証システムの「アカウントを移行」の QR ({@code otpauth-migration://offline?data=<base64 の protobuf>})</li>
 *     <li>1 件の鍵の QR ({@code otpauth://totp/...})</li>
 *     <li>貼り付けたセットアップキーか otpauth の URL ({@link #accountsFromPasted})</li>
 * </ul>
 * どの鍵を取り込むかは利用者が選ぶ。コードの照合はしない (同じ鍵から作るので確かめにならない)。鍵が IdP の登録と
 * 合っているかは次のログインで確かめる (SPEC.md 3.3)。
 * 鍵も名前もログに出さない。
 */
public final class OtpAuthImport {
    private OtpAuthImport() {}

    public static final class Account {
        /** 認証アプリに出ている名前 (アカウント名と発行元)。どれを取り込むかを選ぶのに使う。 */
        @NonNull public final String name;
        @NonNull public final String issuer;
        /** 保存用の otpauth URL。Totp.parse で読める。 */
        @NonNull public final String otpauthUrl;

        Account(@NonNull String name, @NonNull String issuer, @NonNull String otpauthUrl) {
            this.name = name;
            this.issuer = issuer;
            this.otpauthUrl = otpauthUrl;
        }

        /** 鍵が入っているので、URL は出さない。 */
        @NonNull
        @Override
        public String toString() {
            return "Account(<redacted>)";
        }
    }

    /**
     * QR の文字列から、取り込める TOTP の鍵を読む。読めなければ null、読めても TOTP が無ければ空。
     * HOTP (回数で進む鍵) や、桁数・方式がこのアプリで扱えない鍵は外す。
     */
    @Nullable
    public static List<Account> accounts(@Nullable String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("otpauth://")) {
            if (Totp.parse(trimmed) == null) return null;
            String[] label = label(trimmed);
            String issuer = query(trimmed, "issuer");
            return Collections.singletonList(new Account(label[1], issuer != null ? issuer : label[0], trimmed));
        }
        if (!lower.startsWith("otpauth-migration://")) return null;
        String raw = query(trimmed, "data");
        if (raw == null) return null;
        try {
            // クエリの `+` が空白に化けて届くことがあるので戻す。
            byte[] data = Base64.getDecoder().decode(padded(raw.replace(' ', '+')));
            return migrationAccounts(data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** カンマは URL の中 (ラベルの「Doe,John」など) にも、CSV の欄の区切りにもなるので、次の URL の手前でだけ切る。 */
    private static final Pattern PASTED_URL = Pattern.compile(
            "otpauth(?:-migration)?://(?:[^\\s\"'<>,]|,(?!\\s*otpauth))+", Pattern.CASE_INSENSITIVE);

    /**
     * 貼り付けの中で拾った URL を読む。CSV の行なら後ろにカンマ区切りの欄が続くので、カンマの手前で切った
     * 短いものから順に試し、最初に読めたものを使う (ラベルにカンマがあれば、短いものは鍵が無く読めないので先へ進む)。
     */
    @Nullable
    private static List<Account> accountsFromPastedUrl(@NonNull String candidate) {
        for (int end = candidate.indexOf(','); end >= 0; end = candidate.indexOf(',', end + 1)) {
            List<Account> parsed = accounts(candidate.substring(0, end));
            if (parsed != null) return parsed;
        }
        List<Account> parsed = accounts(candidate);
        if (parsed != null) return parsed;
        // HOTP の URL は TOTP として読めない。読めない文字ではなく「TOTP が無い」(空) として返し、
        // 移行用の QR に HOTP しか無いときと同じ案内にする (iOS と同じ)。
        return candidate.regionMatches(true, 0, "otpauth://hotp", 0, 14) ? new ArrayList<>() : null;
    }

    /**
     * 貼り付けた文字から鍵を読む (QR を出せず、鍵をコピーできるアプリ用)。iOS の accounts(fromPasted:) と同じ。
     * otpauth:// / otpauth-migration:// の URL が含まれていれば、文中からすべて拾って読む (書き出したファイルの行や JSON を
     * そのまま貼っても読めるように)。URL が無ければ、全体をセットアップキー (Base32) として読み、方式・桁数・周期は
     * Google 認証システムと同じ既定 (SHA1 / 6 桁 / 30 秒) にする。違っていれば次のログインで弾かれ、2 回続けば鍵を止める。
     * 読めなければ null、読めても TOTP が無ければ空。
     */
    @Nullable
    public static List<Account> accountsFromPasted(@Nullable String text) {
        if (text == null) return null;
        Matcher m = PASTED_URL.matcher(text);
        List<String> urls = new ArrayList<>();
        while (m.find()) urls.add(m.group());
        if (!urls.isEmpty()) {
            List<Account> result = null;
            for (String url : urls) {
                List<Account> parsed = accountsFromPastedUrl(url);
                if (parsed == null) continue;
                if (result == null) result = new ArrayList<>();
                result.addAll(parsed);
            }
            return result;
        }
        // 区切るなら 4 文字ずつ (認証アプリの表示の形)。英字だけの文章 (「please copy the code」) を鍵と取り違えない。
        // 区切りは Unicode の空白 (全角空白・NBSP を含む) とハイフン。iOS の isWhitespace と同じ範囲にする。
        List<String> groups = new ArrayList<>();
        for (String g : text.split("[\\s\\p{Z}-]+")) if (!g.isEmpty()) groups.add(g);
        for (int i = 0; i < groups.size() - 1; i++) if (groups.get(i).length() != 4) return null;
        String key = String.join("", groups).toUpperCase(Locale.ROOT);
        while (key.endsWith("=")) key = key.substring(0, key.length() - 1);
        // 80 ビット (Base32 で 16 文字) より短いものはセットアップキーとみなさない。1 / 3 / 6 文字余る長さは Base32 にならない。
        int rest = key.length() % 8;
        if (key.length() < 16 || rest == 1 || rest == 3 || rest == 6) return null;
        byte[] secret = Totp.base32Decode(key);
        if (secret == null || secret.length == 0) return null;
        String url = "otpauth://totp/?secret=" + base32Encode(secret);
        if (Totp.parse(url) == null) return null;
        return Collections.singletonList(new Account("", "", url));
    }

    /**
     * 候補の並び。大学のアカウントらしいもの (AINS のユーザー名か大学 / IdP の名前を含む) を先に出す。iOS の ordered と同じ。
     * 自動では選ばない。名前は利用者が付け直せるので、当てにならない (選ぶのは利用者)。
     */
    @NonNull
    public static List<Account> ordered(@NonNull List<Account> accounts, @Nullable String username) {
        List<String> hints = new ArrayList<>();
        Collections.addAll(hints, "u-aizu", "secioss", "aizu");
        if (username != null && !username.isEmpty()) hints.add(username.toLowerCase(Locale.ROOT));
        List<Account> likely = new ArrayList<>();
        List<Account> others = new ArrayList<>();
        for (Account a : accounts) {
            String text = (a.name + " " + a.issuer).toLowerCase(Locale.ROOT);
            boolean hit = false;
            for (String h : hints) if (text.contains(h)) hit = true;
            (hit ? likely : others).add(a);
        }
        likely.addAll(others);
        return likely;
    }

    // ---- otpauth-migration (protobuf) ----

    /** MigrationPayload の 1 番 (otp_parameters) を全部読む。壊れたデータなら null。 */
    @Nullable
    static List<Account> migrationAccounts(@NonNull byte[] data) {
        ProtoReader reader = new ProtoReader(data);
        List<Account> accounts = new ArrayList<>();
        while (!reader.atEnd()) {
            long tag = reader.tag();
            if (tag < 0) return null;
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                byte[] bytes = reader.lengthDelimited();
                if (bytes == null) return null;
                Account account = otpParameters(bytes);
                if (account == INVALID) return null;
                if (account != null) accounts.add(account);
            } else if (!reader.skip(wire)) {
                return null;
            }
        }
        return accounts;
    }

    private static final Account INVALID = new Account("", "", "");

    /**
     * OtpParameters。algorithm: 1 SHA1 / 2 SHA256 / 3 SHA512 / 4 MD5、digits: 1 = 6 桁 / 2 = 8 桁、type: 1 HOTP / 2 TOTP。
     * 壊れていれば INVALID、取り込めない鍵 (HOTP など) なら null。
     */
    @Nullable
    private static Account otpParameters(@NonNull byte[] bytes) {
        ProtoReader reader = new ProtoReader(bytes);
        byte[] secret = new byte[0];
        String name = "";
        String issuer = "";
        long algorithm = 0;
        long digits = 0;
        long type = 0;
        boolean unsupported = false;
        while (!reader.atEnd()) {
            long tag = reader.tag();
            if (tag < 0) return INVALID;
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 2 && field >= 1 && field <= 3) {
                byte[] v = reader.lengthDelimited();
                if (v == null) return INVALID;
                if (field == 1) secret = v;
                else if (field == 2) name = new String(v, StandardCharsets.UTF_8);
                else issuer = new String(v, StandardCharsets.UTF_8);
            } else if (wire == 0 && field >= 4 && field <= 6) {
                long v = reader.varint();
                if (v < 0 && !reader.lastVarintTooLong) return INVALID;
                // 63 ビットを超える値 (負の enum など) は扱えない鍵として外す。iOS も同じ鍵だけを外す。
                if (v < 0) {
                    unsupported = true;
                    continue;
                }
                if (field == 4) algorithm = v;
                else if (field == 5) digits = v;
                else type = v;
            } else if (!reader.skip(wire)) {
                return INVALID;
            }
        }
        if (unsupported || type != 2 || secret.length == 0) return null;
        String alg;
        if (algorithm == 0 || algorithm == 1) alg = "SHA1";
        else if (algorithm == 2) alg = "SHA256";
        else if (algorithm == 3) alg = "SHA512";
        else return null;
        int digitCount;
        if (digits == 0 || digits == 1) digitCount = 6;
        else if (digits == 2) digitCount = 8;
        else return null;
        String labelText = issuer.isEmpty() ? name : issuer + ":" + name;
        String url = "otpauth://totp/" + encode(labelText)
                + "?secret=" + base32Encode(secret)
                + "&issuer=" + encode(issuer)
                + "&algorithm=" + alg
                + "&digits=" + digitCount
                + "&period=30";
        if (Totp.parse(url) == null) return null;
        // 移行の QR の名前は「発行元:アカウント」の形のことがあるので、発行元を外して出す。
        String shown = !issuer.isEmpty() && name.startsWith(issuer + ":") ? name.substring(issuer.length() + 1) : name;
        return new Account(shown, issuer, url);
    }

    /** protobuf の最小限の読み取り。読めなければ -1 / null / false。 */
    private static final class ProtoReader {
        private final byte[] buffer;
        private int index = 0;
        /** 直前の varint() が、10 バイトの (読み飛ばせるが 63 ビットに収まらない) 値で -1 になったか。 */
        boolean lastVarintTooLong = false;

        ProtoReader(byte[] buffer) { this.buffer = buffer; }

        boolean atEnd() { return index >= buffer.length; }

        /** 63 ビットまでの値 (この形式で読む欄はどれも小さい)。それを超えるか途中で切れていれば -1。 */
        long varint() {
            lastVarintTooLong = false;
            int start = index;
            if (!skipVarint()) return -1;
            long result = 0;
            for (int i = start, shift = 0; i < index; i++, shift += 7) result |= (long) (buffer[i] & 0x7f) << shift;
            if (index - start > 9 || result < 0) {
                lastVarintTooLong = true;
                return -1;
            }
            return result;
        }

        /**
         * 値を読まずに 10 バイトまでの varint を進める。読み飛ばす欄 (batch_id など) には負の int32 が入りうり、
         * protobuf はそれを 10 バイトにする (iOS は 64 ビットまで読むので同じ QR を取り込める)。
         */
        boolean skipVarint() {
            for (int n = 0; n < 10 && index < buffer.length; n++) {
                if ((buffer[index++] & 0x80) == 0) return true;
            }
            return false;
        }

        long tag() {
            long v = varint();
            if (v < 0 || (v >>> 3) == 0 || (v >>> 3) > Integer.MAX_VALUE) return -1;
            return v;
        }

        @Nullable
        byte[] lengthDelimited() {
            long length = varint();
            if (length < 0 || length > buffer.length - index) return null;
            byte[] out = new byte[(int) length];
            System.arraycopy(buffer, index, out, 0, out.length);
            index += out.length;
            return out;
        }

        boolean skip(int wire) {
            switch (wire) {
                case 0: return skipVarint();
                case 1: if (buffer.length - index < 8) return false; index += 8; return true;
                case 2: return lengthDelimited() != null;
                case 5: if (buffer.length - index < 4) return false; index += 4; return true;
                default: return false;
            }
        }
    }

    // ---- 細かいもの ----

    private static String padded(String base64) {
        int r = base64.length() % 4;
        return r == 0 ? base64 : base64 + "====".substring(r);
    }

    /** RFC 4648 の Base32 (パディングなし)。 */
    @NonNull
    static String base32Encode(@NonNull byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    /** クエリの値 (URL デコード済み)。無ければ null。 */
    @Nullable
    private static String query(@NonNull String url, @NonNull String key) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        int hash = url.indexOf('#', q);
        String query = url.substring(q + 1, hash < 0 ? url.length() : hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !pair.substring(0, eq).equalsIgnoreCase(key)) continue;
            try {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** {@code otpauth://totp/発行元:アカウント} のラベルを {発行元, アカウント} に分ける。 */
    @NonNull
    private static String[] label(@NonNull String url) {
        int start = url.indexOf("://") + 3;
        int slash = url.indexOf('/', start);
        int q = url.indexOf('?');
        String path = slash < 0 ? "" : url.substring(slash + 1, q < 0 ? url.length() : q);
        try {
            path = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            // デコードできないラベルはそのまま出す (名前は選ぶための表示にしか使わない)。
        }
        int colon = path.indexOf(':');
        if (colon >= 0) return new String[]{path.substring(0, colon), path.substring(colon + 1).trim()};
        return new String[]{"", path};
    }
}
