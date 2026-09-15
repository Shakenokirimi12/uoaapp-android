package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * stdmsv1.u-aizu.ac.jp (Dovecot, IMAP4rev1, AUTH=PLAIN、IMAPS/993) から IdP のワンタイムパスワード
 * メールを取り出す最小限の IMAP クライアント。iOS の IMAPOTPFetcher の移植。
 * RFC 3501 全体は実装せず、LOGIN -> SELECT -> (UID SEARCH を繰り返し) -> UID FETCH -> LOGOUT だけを
 * サポートする。AINS ID/パスワードは CampusSquare/IdP ログインと共通 (別のメールアカウント設定は無い)。
 * ID/PW/OTP コードの値はログに出さない。
 */
public final class ImapOtpFetcher {
    public static final class ImapException extends Exception {
        public ImapException(String message) { super(message); }
    }

    private static final String DEFAULT_HOST = "stdmsv1.u-aizu.ac.jp";
    private static final int DEFAULT_PORT = 993;
    private static final String OTP_SENDER = "idp-apply@u-aizu.ac.jp";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long RETRY_INTERVAL_MS = 3_000L;
    private static final int MAX_RECONNECTS = 3;

    private final String host;
    private final int port;

    public ImapOtpFetcher() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public ImapOtpFetcher(@NonNull String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * motp.cgi でメール送信を依頼した直後に呼ぶ想定。配信まで数秒かかることがあるので、timeout 内は
     * 数秒おきに探す (IDLE で待ち受ける方式は使わない)。
     * 接続は 1 本だけ張り、LOGIN/SELECT は最初の 1 回。以降は UID SEARCH だけを繰り返す。以前は
     * ポーリングごとに TLS 接続と LOGIN をやり直していて、メールサーバーに 30 秒で 10 回ログインしていた。
     * 接続が切れた (IOException) ときだけ張り直し、それも MAX_RECONNECTS 回まで。
     */
    @NonNull
    public String fetchOtpCode(@NonNull String uid, @NonNull String pass, long timeoutSeconds) throws Exception {
        return fetchOtpCode(uid, pass, timeoutSeconds, System.currentTimeMillis());
    }

    /**
     * @param requestedAt motp.cgi にメール送信を頼んだ時刻 (epoch ミリ秒)。この時刻以降に届いたメールだけを
     *                    今回のコードとみなす。入力ダイアログから後追いで自動取得へ切り替えた場合など、
     *                    送信からこの呼び出しまでに間が空くときは必ず渡すこと (呼び出し時刻を使うと、
     *                    その間に届いた目当てのメールを「古い」と判定して取りこぼす)。
     */
    @NonNull
    public String fetchOtpCode(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                               long requestedAt) throws Exception {
        long startedAt = requestedAt;
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Exception lastError = new ImapException("mailNotFound");
        int reconnects = 0;
        ImapStream stream = null;
        // 「今から届くメール」だけを受け取るための下限。motp.cgi を叩いた直後に呼ばれるので、この時点で
        // 受信箱にある OTP メールは全部古い = 使えないコード。
        // 既読 (\Seen) かどうかで絞らないのは、ユーザーがメールアプリで先に開くと UNSEEN 検索に掛からず、
        // 取得も削除もできなくなっていたため (2026-09-15 実測: 受信箱に既読の OTP メールが 8 件残っていた)。
        int floorUid = -1;
        try {
            while (true) {
                try {
                    if (stream == null) {
                        // LOGIN/SELECT の失敗 (ImapException) はここで抜ける。ID/PW 誤りを 3 秒おきに
                        // 試し直すとアカウントロックの危険があるので再試行しない。
                        stream = openAndSelect(uid, pass);
                    }
                    if (floorUid < 0) floorUid = resolveFloorUid(stream, startedAt);
                    String code = searchAndFetch(stream, floorUid);
                    if (code != null) return code;
                    lastError = new ImapException("mailNotFound");
                } catch (IOException e) {
                    lastError = e;
                    closeQuietly(stream);
                    stream = null;
                    if (++reconnects > MAX_RECONNECTS) throw e;
                } catch (ImapException e) {
                    if (stream == null) throw e;
                    // codeNotFound 等: メールはあるが本文から取れない。配信途中の可能性もあるので待つ。
                    lastError = e;
                }
                if (System.currentTimeMillis() >= deadline) throw lastError;
                Thread.sleep(RETRY_INTERVAL_MS);
            }
        } finally {
            if (stream != null) {
                stream.sendQuietly(stream.nextTag() + " LOGOUT");
                stream.close();
            }
        }
    }

    /** 接続して LOGIN + SELECT INBOX まで済ませる。失敗したら接続を閉じてから投げる。 */
    private ImapStream openAndSelect(String uid, String pass) throws IOException, ImapException {
        ImapStream stream = new ImapStream(host, port);
        try {
            stream.readLine(); // "* OK ... Dovecot ready."

            String tag = stream.nextTag();
            stream.send(tag + " LOGIN " + quote(uid) + " " + quote(pass));
            stream.readUntilTagged(tag);

            tag = stream.nextTag();
            stream.send(tag + " SELECT INBOX");
            stream.readUntilTagged(tag);
            return stream;
        } catch (IOException | ImapException e) {
            stream.close();
            throw e;
        }
    }

    /**
     * 下限 UID を決める。原則は「今受信箱にある一番新しい OTP メール」だが、TLS ハンドシェイク +
     * LOGIN + SELECT を待つ間に目当てのメールが届いてしまうと、それ自身を下限にしてしまって
     * 「自分より新しい UID」が永久に現れず、コードが届いているのに 30 秒待って諦めることになる。
     * 最新メールの到着時刻 (INTERNALDATE) がこの呼び出しの開始以降なら、それが今要求したメール
     * なので 1 つ手前を下限にする。
     */
    private int resolveFloorUid(ImapStream stream, long startedAt) throws IOException, ImapException {
        String latest = searchLatestUid(stream);
        if (latest == null) return 0;
        int latestUid = Integer.parseInt(latest);
        Long arrivedAt = internalDate(stream, latest);
        // INTERNALDATE は秒精度なので、startedAt も秒に切り下げてから比べる。ミリ秒のまま比べると、
        // startedAt と同じ秒の中で届いたメールが「開始より前」に見えて取りこぼす。
        long startedSecond = startedAt / 1000L * 1000L;
        // 取れなかった (書式が想定外) ときは従来どおり最新メールを下限にする。誤って古いコードを
        // 使うより、手入力に落ちる方が安全。
        if (arrivedAt != null && arrivedAt >= startedSecond) return latestUid - 1;
        return latestUid;
    }

    /** 指定 UID のメールがサーバーに届いた時刻 (epoch ミリ秒)。取れなければ null。 */
    @Nullable
    private Long internalDate(ImapStream stream, String targetUid) throws IOException, ImapException {
        String tag = stream.nextTag();
        stream.send(tag + " UID FETCH " + targetUid + " (INTERNALDATE)");
        return parseInternalDate(stream.readUntilTagged(tag));
    }

    /** 受信箱にある OTP メールのうち一番新しい UID。無ければ null。 */
    @Nullable
    private String searchLatestUid(ImapStream stream) throws IOException, ImapException {
        String tag = stream.nextTag();
        stream.send(tag + " UID SEARCH FROM \"" + OTP_SENDER + "\" SINCE " + imapDate(new Date()));
        return lastUid(stream.readUntilTagged(tag));
    }

    /**
     * 選択済みの INBOX で OTP メールを 1 回探す。floorUid より新しいものが無ければ null。見つかれば
     * コードを返し、そのメールを削除する。
     */
    @Nullable
    private String searchAndFetch(ImapStream stream, int floorUid) throws IOException, ImapException {
        String targetUid = searchLatestUid(stream);
        if (targetUid == null) return null;
        // 下限以下は今回要求したコードではない (期限切れの古いメール)。
        if (Integer.parseInt(targetUid) <= floorUid) return null;

        String tag;

        tag = stream.nextTag();
        stream.send(tag + " UID FETCH " + targetUid + " (BODY.PEEK[TEXT])");
        String body = stream.readFetchBody(tag);

        String code = extractCode(body);
        if (code == null) throw new ImapException("codeNotFound");

        // 読み取り済みの OTP メールは削除する (ユーザー了承済み、2026-09-07)。
        // 抽出に失敗した場合は消さずに残す (デバッグのため)。
        try {
            tag = stream.nextTag();
            stream.send(tag + " UID STORE " + targetUid + " +FLAGS (\\Deleted)");
            stream.readUntilTagged(tag);
            tag = stream.nextTag();
            stream.send(tag + " EXPUNGE");
            stream.readUntilTagged(tag);
        } catch (Exception e) {
            // 削除に失敗してもコードは取れているので、ログインは続ける。ただし黙って捨てると
            // 「メールが消えない」ときに原因が分からなくなるので必ずログへ残す (コードは出さない)。
            android.util.Log.w("ImapOtpFetcher", "OTPメールの削除に失敗: " + e);
        }
        return code;
    }

    private static void closeQuietly(@Nullable ImapStream stream) {
        if (stream != null) stream.close();
    }

    // ---- Parsing helpers (pure, unit-tested) ----

    static String quote(@NonNull String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String imapDate(@NonNull Date date) {
        return new SimpleDateFormat("dd-MMM-yyyy", Locale.US).format(date);
    }

    /** "* SEARCH 12 34 56" 形式の行から一番大きい (最新の) UID を取る。 */
    @Nullable
    static String lastUid(@NonNull List<String> lines) {
        for (String line : lines) {
            if (!line.startsWith("* SEARCH")) continue;
            int max = -1;
            for (String token : line.substring("* SEARCH".length()).trim().split("\\s+")) {
                if (token.isEmpty()) continue;
                try {
                    max = Math.max(max, Integer.parseInt(token));
                } catch (NumberFormatException ignored) {
                    // 数字以外のトークンは UID ではない
                }
            }
            if (max >= 0) return String.valueOf(max);
        }
        return null;
    }

    /**
     * "* 5 FETCH (UID 123 INTERNALDATE \"15-Sep-2026 14:23:45 +0900\")" から到着時刻を取る。
     * 取れなければ null (呼び出し側が従来の下限にフォールバックする)。
     */
    @Nullable
    static Long parseInternalDate(@NonNull List<String> lines) {
        for (String line : lines) {
            Matcher m = Pattern.compile("INTERNALDATE \"([^\"]+)\"").matcher(line);
            if (!m.find()) continue;
            try {
                return new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US).parse(m.group(1)).getTime();
            } catch (java.text.ParseException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 本文テンプレート (idp-apply@u-aizu.ac.jp のメール) には他に数字だけの行が無いことを 2026-09-07 に
     * 実物で確認済み。単独行の 6〜10 桁の数字をコードとみなす。
     */
    @Nullable
    static String extractCode(@NonNull String body) {
        Matcher m = Pattern.compile("(?m)^\\s*(\\d{6,10})\\s*$").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * IMAP の応答は基本 CRLF 区切りの行だが、FETCH の BODY[...] だけは "{n}\r\n" の直後に n バイトの
     * 生データ (内部に \r\n を含みうる) が来る。この形だけ特別扱いする最小限のリーダ。
     */
    private static final class ImapStream implements AutoCloseable {
        private final SSLSocket socket;
        private final InputStream in;
        private final OutputStream out;
        private int tagSeq = 0;

        ImapStream(String host, int port) throws IOException {
            SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            // SSLSocket は既定ではホスト名検証をしない。iOS の NWConnection(TLS) と同じ強度にするため明示する。
            SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(params);
            s.startHandshake();
            socket = s;
            in = new BufferedInputStream(s.getInputStream());
            out = s.getOutputStream();
        }

        /** 1 接続で複数コマンドを送るので、タグは使い捨てにして応答の取り違えを防ぐ。 */
        String nextTag() {
            return "a" + (++tagSeq);
        }

        void send(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void sendQuietly(String line) {
            try {
                send(line);
            } catch (IOException ignored) {
                // LOGOUT 送信の失敗は結果に影響しない
            }
        }

        /** tag で始まる応答 ("a1 OK ..." 等) が来るまで、途中の未タグ行を集めて返す。"a1 NO"/"a1 BAD" ならエラー。 */
        List<String> readUntilTagged(String tag) throws IOException, ImapException {
            List<String> lines = new ArrayList<>();
            while (true) {
                String line = readLine();
                if (line.startsWith(tag + " OK")) return lines;
                if (line.startsWith(tag + " NO") || line.startsWith(tag + " BAD")) {
                    // 応答行に資格情報が含まれることはないが、念のため先頭の状態語だけ残す。
                    throw new ImapException("commandFailed(" + tag + "): " + line.substring(0, Math.min(line.length(), 40)));
                }
                lines.add(line);
            }
        }

        /** "* n FETCH (... BODY[TEXT] {123}" の直後に n バイトの生データが続く形を読む。 */
        String readFetchBody(String tag) throws IOException, ImapException {
            while (true) {
                String line = readLine();
                if (line.startsWith(tag + " OK")) throw new ImapException("mailNotFound");
                if (line.startsWith(tag + " NO") || line.startsWith(tag + " BAD")) {
                    throw new ImapException("commandFailed(" + tag + ")");
                }
                int open = line.lastIndexOf('{');
                int close = line.lastIndexOf('}');
                if (open < 0 || close < 0 || open >= close) continue;
                int byteCount;
                try {
                    byteCount = Integer.parseInt(line.substring(open + 1, close));
                } catch (NumberFormatException e) {
                    continue;
                }
                byte[] raw = readBytes(byteCount);
                readUntilTagged(tag); // 残りの ")" と "tag OK" を読み捨てる
                return decode(raw);
            }
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int prev = -1;
            while (true) {
                int b = in.read();
                if (b < 0) throw new IOException("connectionFailed");
                if (prev == '\r' && b == '\n') {
                    byte[] bytes = buf.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
                }
                buf.write(b);
                prev = b;
            }
        }

        private byte[] readBytes(int n) throws IOException {
            byte[] result = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(result, off, n - off);
                if (r < 0) throw new IOException("connectionFailed");
                off += r;
            }
            return result;
        }

        private static String decode(byte[] raw) {
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                return new String(raw, Charset.forName("Shift_JIS"));
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 閉じる途中の失敗は無視してよい
            }
        }
    }
}
