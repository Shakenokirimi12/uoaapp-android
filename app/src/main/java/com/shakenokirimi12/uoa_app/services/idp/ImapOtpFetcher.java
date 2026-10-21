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
    private static final String UNIVERSITY_DOMAIN = "@u-aizu.ac.jp";
    /**
     * 1 回の検索で From を確かめに行くメールの数の上限。同じ送信者名を名乗る偽メールを大量に送られても、
     * ヘッダの取得で待ち続けない (そのときは見つからない扱いになり、手入力に落ちる)。
     */
    private static final int MAX_FROM_CHECKS = 20;
    /** 使い終わったメールの削除で From を確かめる数の上限 (直近 7 日分が対象なので検索より多め)。 */
    private static final int MAX_DELETE_CHECKS = 50;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long RETRY_INTERVAL_MS = 3_000L;
    private static final int MAX_RECONNECTS = 3;

    private final String host;
    private final int port;
    /** どこまで進んだか (失敗したときの切り分け用)。取得の動きには影響しない。 */
    @NonNull private final Progress progress;

    public ImapOtpFetcher() {
        this(DEFAULT_HOST, DEFAULT_PORT, new Progress());
    }

    public ImapOtpFetcher(@NonNull Progress progress) {
        this(DEFAULT_HOST, DEFAULT_PORT, progress);
    }

    public ImapOtpFetcher(@NonNull String host, int port) {
        this(host, port, new Progress());
    }

    public ImapOtpFetcher(@NonNull String host, int port, @NonNull Progress progress) {
        this.host = host;
        this.port = port;
        this.progress = progress;
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
        return fetch(uid, pass, timeoutSeconds, requestedAt, true).code;
    }

    /** 見つけた OTP メール。uid は後で使用済みとして消すときの上限。 */
    public static final class OtpMail {
        @NonNull public final String code;
        public final int uid;

        OtpMail(@NonNull String code, int uid) {
            this.code = code;
            this.uid = uid;
        }
    }

    /**
     * PC 中継用。fetchOtpCode と同じ探し方だが、メールは消さない。中継サーバがコードを受け取った
     * (204) と分かってから {@link #deleteUsedMails} で消す (送れなかったときに手で使えるように)。
     */
    @NonNull
    public OtpMail fetchOtpMail(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                                long requestedAt) throws Exception {
        return fetch(uid, pass, timeoutSeconds, requestedAt, false);
    }

    /**
     * 使い終わった OTP メールを消す: 直近 7 日の OTP メールのうち UID が upToUid 以下のもの。
     * iOS の deleteUsedMails と同じ。readTimeoutMs で打ち切る (失敗しても結果には影響させない)。
     */
    public void deleteUsedMails(@NonNull String uid, @NonNull String pass, int upToUid, int readTimeoutMs)
            throws Exception {
        ImapStream stream = new ImapStream(host, port, readTimeoutMs);
        try {
            stream.readLine();
            String tag = stream.nextTag();
            stream.send(tag + " LOGIN " + quote(uid) + " " + quote(pass));
            stream.readUntilTagged(tag);
            tag = stream.nextTag();
            stream.send(tag + " SELECT INBOX");
            stream.readUntilTagged(tag);

            tag = stream.nextTag();
            Date since = new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000);
            stream.send(tag + " UID SEARCH FROM \"" + OTP_SENDER + "\" SINCE " + imapDate(since));
            List<Integer> uids = allUids(stream.readUntilTagged(tag));
            uids.sort(java.util.Collections.reverseOrder());
            // 消すのは From が OTP の送信元と完全に一致したものだけ (送信者名で名乗っただけのメールは消さない)。
            StringBuilder set = new StringBuilder();
            int checks = 0;
            for (int u : uids) {
                if (u > upToUid) continue;
                if (checks++ >= MAX_DELETE_CHECKS) break;
                if (!isFromOtpSender(stream, u)) continue;
                if (set.length() > 0) set.append(',');
                set.append(u);
            }
            if (set.length() > 0) {
                tag = stream.nextTag();
                stream.send(tag + " UID STORE " + set + " +FLAGS (\\Deleted)");
                stream.readUntilTagged(tag);
                tag = stream.nextTag();
                stream.send(tag + " EXPUNGE");
                stream.readUntilTagged(tag);
            }
            stream.sendQuietly(stream.nextTag() + " LOGOUT");
        } finally {
            stream.close();
        }
    }

    @NonNull
    private OtpMail fetch(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                          long requestedAt, boolean deleteAfterRead) throws Exception {
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
        // From を確かめた結果 (UID ごと)。数秒おきに探し直すたびに同じメールのヘッダを取りに行かない。
        java.util.Map<Integer, Boolean> checked = new java.util.HashMap<>();
        int connections = 0;
        try {
            while (true) {
                try {
                    if (stream == null) {
                        // 2 本目からは繋ぎ直し。detail に回数を付ける。
                        if (connections++ > 0) progress.reconnected();
                        // LOGIN/SELECT の失敗 (ImapException) はここで抜ける。ID/PW 誤りを 3 秒おきに
                        // 試し直すとアカウントロックの危険があるので再試行しない。
                        stream = openAndSelect(uid, pass);
                    }
                    if (floorUid < 0) floorUid = resolveFloorUid(stream, startedAt);
                    OtpMail mail = searchAndFetch(stream, floorUid, deleteAfterRead, checked);
                    if (mail != null) return mail;
                    lastError = new ImapException("mailNotFound");
                    progress.recordFailure(lastError);
                } catch (IOException e) {
                    progress.recordFailure(e);
                    lastError = e;
                    closeQuietly(stream);
                    stream = null;
                    checked.clear();
                    if (++reconnects > MAX_RECONNECTS) throw e;
                } catch (ImapException e) {
                    progress.recordFailure(e);
                    if (stream == null) throw e;
                    // codeNotFound 等: メールはあるが本文から取れない。配信途中の可能性もあるので待つ。
                    lastError = e;
                }
                if (System.currentTimeMillis() >= deadline) throw lastError;
                // 受信箱を開いたままメールを待つ間だけ idle にする。繋ぎ直しを待つ間は、失敗した段階のまま
                // (中継の見張りが「メールが来ない」と取り違えないように)。
                if (stream != null) progress.enter(Progress.Stage.IDLE);
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
        progress.enter(Progress.Stage.CONNECT);
        ImapStream stream = new ImapStream(host, port);
        try {
            progress.enter(Progress.Stage.GREETING);
            stream.readLine(); // "* OK ... Dovecot ready."

            progress.enter(Progress.Stage.LOGIN);
            String tag = stream.nextTag();
            stream.send(tag + " LOGIN " + quote(uid) + " " + quote(pass));
            stream.readUntilTagged(tag);

            progress.enter(Progress.Stage.SELECT);
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
        // 下限は From を確かめる前の候補 (SEARCH FROM の結果) で決める。今ある候補はどれも今回のコードより
        // 古いので、本物かどうかに関係なく下限にしてよい。確かめた本物だけで決めると、偽メールが多くて
        // 本物まで確かめきれなかったときに下限が 0 になり、期限切れの古い本物を新しいコードとして使ってしまう。
        progress.enter(Progress.Stage.SEARCH);
        String tag = stream.nextTag();
        stream.send(tag + " UID SEARCH FROM \"" + OTP_SENDER + "\" SINCE "
                + imapDate(new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)));
        int latestUid = -1;
        for (int uid : allUids(stream.readUntilTagged(tag))) latestUid = Math.max(latestUid, uid);
        if (latestUid < 0) return 0;
        Long arrivedAt = internalDate(stream, String.valueOf(latestUid));
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

    /**
     * 受信箱にある OTP メールのうち、aboveUid より新しく From が送信元と完全に一致する一番新しい UID。
     * 無ければ null。
     *
     * IMAP の SEARCH FROM は From ヘッダの部分一致 (送信者名 "idp-apply@u-aizu.ac.jp" <x@attacker> にも当たる)
     * なので候補を絞るだけに使い、From のアドレスは 1 通ずつ取って確かめる (CLAUDE-SECURITY F20)。
     * From は送り手が書く値なので、送信元を完全に名乗ったメールまでは区別できない
     * (大学のメールサーバの認証結果ヘッダは、実物のメールで確かめられていないので使っていない)。
     */
    @Nullable
    private Integer latestGenuineUid(ImapStream stream, int aboveUid, java.util.Map<Integer, Boolean> checked)
            throws IOException, ImapException {
        progress.enter(Progress.Stage.SEARCH);
        String tag = stream.nextTag();
        // 前日から探す。今日だけだと、日付をまたいだ直後 (23:55 に届いたメールを 0:01 に探す、PC 中継の
        // 「10 分前」を含む) に見つからない。新しさは UID と INTERNALDATE で判定しているので広げても困らない。
        stream.send(tag + " UID SEARCH FROM \"" + OTP_SENDER + "\" SINCE "
                + imapDate(new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)));
        List<Integer> uids = allUids(stream.readUntilTagged(tag));
        uids.sort(java.util.Collections.reverseOrder());
        int checks = 0;
        for (int uid : uids) {
            if (uid <= aboveUid) break;
            Boolean genuine = checked.get(uid);
            if (genuine == null) {
                if (checks++ >= MAX_FROM_CHECKS) break;
                genuine = isFromOtpSender(stream, uid);
                checked.put(uid, genuine);
            }
            if (genuine) return uid;
        }
        return null;
    }

    /** そのメールの From ヘッダのアドレスが OTP の送信元と完全に一致するか。 */
    private boolean isFromOtpSender(ImapStream stream, int uid) throws IOException, ImapException {
        String tag = stream.nextTag();
        stream.send(tag + " UID FETCH " + uid + " (BODY.PEEK[HEADER.FIELDS (FROM)])");
        String header;
        try {
            header = stream.readFetchBody(tag);
        } catch (ImapException e) {
            if ("mailNotFound".equals(e.getMessage())) return false; // 消された等
            throw e;
        }
        String address = fromAddress(header);
        return address != null && address.equalsIgnoreCase(OTP_SENDER);
    }

    /**
     * 選択済みの INBOX で OTP メールを 1 回探す。floorUid より新しいものが無ければ null。見つかれば
     * コードを返し、そのメールを削除する。
     */
    @Nullable
    private OtpMail searchAndFetch(ImapStream stream, int floorUid, boolean deleteAfterRead,
                                   java.util.Map<Integer, Boolean> checked)
            throws IOException, ImapException {
        // 下限以下は今回要求したコードではない (期限切れの古いメール)。
        Integer genuine = latestGenuineUid(stream, floorUid, checked);
        if (genuine == null) return null;
        String targetUid = String.valueOf(genuine);

        String tag;

        progress.enter(Progress.Stage.FETCH);
        tag = stream.nextTag();
        stream.send(tag + " UID FETCH " + targetUid + " (BODY.PEEK[TEXT])");
        String body = stream.readFetchBody(tag);

        String code = extractCode(body);
        if (code == null) throw new ImapException("codeNotFound");
        OtpMail mail = new OtpMail(code, Integer.parseInt(targetUid));
        if (!deleteAfterRead) return mail;

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
        return mail;
    }

    private static void closeQuietly(@Nullable ImapStream stream) {
        if (stream != null) stream.close();
    }

    /**
     * IMAP で読む受信箱 (stdmsv1 に uid でログインする) のメールアドレス。uid は LOGIN と同じく前後の空白を除く。
     * uid が空なら null。
     */
    @Nullable
    public static String mailboxAddress(@Nullable String uid) {
        String trimmed = uid != null ? uid.trim() : "";
        return trimmed.isEmpty() ? null : trimmed + UNIVERSITY_DOMAIN;
    }

    /**
     * LOGIN の NO が ID / パスワードの誤りか。Dovecot は資格情報の誤りに
     * {@code NO [AUTHENTICATIONFAILED] Authentication failed.} を返す (RFC 5530)。同時接続数の超過などの
     * 一時的な NO ([UNAVAILABLE] など) は誤りではない。iOS の IMAPOTPFetcher.isAuthenticationFailure と同じ。
     */
    public static boolean isAuthenticationFailure(@NonNull String response) {
        return response.contains("[AUTHENTICATIONFAILED]")
                || response.toLowerCase(Locale.ROOT).contains("authentication failed");
    }

    /**
     * 自動取得がどの段階まで進み、どこで止まったか。PC への中継で失敗を返すとき、中継サーバへ送る
     * detail の材料にする (iOS の IMAPOTPProgress と同じ)。取得の動きには影響しない。
     * 中継の時間切れの見張り (別のスレッド) からも読むので synchronized にする。
     * メールの中身・アドレス・コード・ID は持たない。
     */
    public static final class Progress {
        public enum Stage {
            CONNECT, GREETING, LOGIN, SELECT, SEARCH, FETCH, IDLE;

            @NonNull
            public String code() { return name().toLowerCase(Locale.ROOT); }
        }

        private Stage stage = Stage.CONNECT;
        private int reconnects;
        @Nullable private Exception failure;
        private Stage failureStage = Stage.CONNECT;
        private int failureReconnects;

        synchronized void enter(@NonNull Stage s) { stage = s; }

        synchronized void reconnected() { reconnects++; }

        /** エラーを受け取った時点の段階を残す。最後に投げるエラーと組にして detail を作る。 */
        synchronized void recordFailure(@NonNull Exception e) {
            failure = e;
            failureStage = stage;
            failureReconnects = reconnects;
        }

        @NonNull
        public synchronized Stage stage() { return stage; }

        public synchronized int reconnects() { return reconnects; }

        /** fetchOtpMail が投げたエラーの detail。記録したエラーと違うもの (割り込みなど) なら、今の段階で作る。 */
        @NonNull
        public String detail(@NonNull Exception error) {
            Stage s;
            int r;
            synchronized (this) {
                boolean same = failure == error;
                s = same ? failureStage : stage;
                r = same ? failureReconnects : reconnects;
            }
            return detail(error, s, r);
        }

        /**
         * 中継サーバの detail ({@code ^[a-z0-9][a-z0-9._:-]{0,79}$}) にする。どの段階で何が起きたかと、
         * 繋ぎ直した回数 (":r2" など)。サーバの応答は角括弧の応答コード ([INUSE] など、英字だけ) しか使わない。
         * 例外の文面はホスト名や IP を含みうるので、errno の名前 (ECONNREFUSED など) だけを取り出す。
         */
        @NonNull
        static String detail(@NonNull Exception error, @NonNull Stage s, int reconnects) {
            String stage = s.code();
            String code;
            String message = String.valueOf(error.getMessage());
            if (error instanceof InterruptedException) {
                code = "cancelled";
            } else if (error instanceof ImapException && message.equals("mailNotFound")) {
                code = "mail-timeout";
            } else if (error instanceof ImapException && message.equals("codeNotFound")) {
                code = "code-not-found";
            } else if (error instanceof ImapException && message.startsWith("commandFailed(")) {
                // "commandFailed(a1): a1 NO [UNAVAILABLE] ..." (応答行が無い形もある)
                int colon = message.indexOf("): ");
                String line = colon >= 0 ? message.substring(colon + 3) : "";
                String[] words = line.split(" ");
                code = stage + "-" + (words.length > 1 && words[1].equalsIgnoreCase("BAD") ? "bad" : "no");
                Matcher m = Pattern.compile("\\[([A-Za-z-]{1,24})[\\] ]").matcher(line);
                if (m.find()) code += ":" + m.group(1).toLowerCase(Locale.ROOT);
            } else if (error instanceof java.net.SocketTimeoutException) {
                // 接続 (TCP + TLS) の時間切れか、読み取りの無応答 (setSoTimeout)。
                code = s == Stage.CONNECT ? "connect-timeout" : stage + "-stall";
            } else if (error instanceof java.net.UnknownHostException) {
                code = stage + "-failed:dns";
            } else if (error instanceof javax.net.ssl.SSLException) {
                code = stage + "-failed:tls";
            } else if (error instanceof IOException && message.equals("connectionFailed")) {
                code = stage + "-closed";
            } else if (error instanceof IOException) {
                Matcher m = Pattern.compile("\\b(E[A-Z]{2,15})\\b").matcher(message);
                code = stage + (m.find() ? "-failed:" + m.group(1).toLowerCase(Locale.ROOT) : "-error");
            } else {
                code = stage + "-error";
            }
            if (reconnects > 0) code += ":r" + reconnects;
            return sanitize(code);
        }

        @NonNull
        static String sanitize(@NonNull String raw) {
            StringBuilder b = new StringBuilder();
            for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
                if (b.length() >= 80) break;
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == ':' || c == '-';
                b.append(ok ? c : '-');
            }
            if (b.length() == 0 || !Character.isLetterOrDigit(b.charAt(0))) {
                if (b.length() >= 80) b.setLength(79);
                b.insert(0, 'x');
            }
            return b.toString();
        }
    }

    // ---- Parsing helpers (pure, unit-tested) ----

    static String quote(@NonNull String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String imapDate(@NonNull Date date) {
        return new SimpleDateFormat("dd-MMM-yyyy", Locale.US).format(date);
    }

    /** "* SEARCH 1 2 3" の UID を全部。該当なしなら空。 */
    @NonNull
    static List<Integer> allUids(@NonNull List<String> lines) {
        List<Integer> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("* SEARCH")) continue;
            for (String token : line.substring("* SEARCH".length()).trim().split("\\s+")) {
                try {
                    out.add(Integer.parseInt(token));
                } catch (NumberFormatException ignored) {
                    // 空行 (該当なし) など
                }
            }
        }
        return out;
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
     * From ヘッダ (BODY[HEADER.FIELDS (FROM)] の中身) から送信元のアドレスを 1 つ取り出す。
     * 送信者名 (引用符の中・コメント・エンコードされた語) に書かれたアドレスは使わない。From が無い・
     * 2 つ以上ある・アドレスが 2 つ以上ある・形が読めないときは null (= 送信元と認めない)。
     */
    @Nullable
    static String fromAddress(@NonNull String headers) {
        // 折り返し (CRLF + 空白) を戻してから 1 行ずつ見る。
        String unfolded = headers.replaceAll("\\r?\\n[ \\t]+", " ");
        String value = null;
        for (String line : unfolded.split("\\r?\\n")) {
            if (line.regionMatches(true, 0, "From:", 0, 5)) {
                if (value != null) return null;
                value = line.substring(5);
            }
        }
        if (value == null) return null;
        // 引用符の中とコメントを消す (どちらにも < > や @ を書ける)。
        StringBuilder plain = new StringBuilder();
        boolean quoted = false;
        int comment = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((quoted || comment > 0) && c == '\\') { i++; continue; }
            if (comment == 0 && c == '"') { quoted = !quoted; continue; }
            if (quoted) continue;
            if (c == '(') { comment++; continue; }
            if (c == ')' && comment > 0) { comment--; continue; }
            if (comment > 0) continue;
            plain.append(c);
        }
        if (quoted || comment > 0) return null;
        String rest = plain.toString().trim();
        if (rest.indexOf(',') >= 0) return null;
        String address;
        int open = rest.indexOf('<');
        if (open >= 0) {
            int close = rest.indexOf('>', open);
            if (close < 0 || rest.indexOf('<', open + 1) >= 0 || rest.indexOf('>', close + 1) >= 0) return null;
            address = rest.substring(open + 1, close).trim();
        } else {
            address = rest;
        }
        if (address.isEmpty() || address.indexOf(' ') >= 0 || address.indexOf('@') <= 0
                || address.indexOf('@') != address.lastIndexOf('@')) {
            return null;
        }
        return address;
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
            this(host, port, READ_TIMEOUT_MS);
        }

        ImapStream(String host, int port, int readTimeoutMs) throws IOException {
            SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            s.connect(new InetSocketAddress(host, port), Math.min(CONNECT_TIMEOUT_MS, Math.max(readTimeoutMs, 1000)));
            s.setSoTimeout(readTimeoutMs);
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
