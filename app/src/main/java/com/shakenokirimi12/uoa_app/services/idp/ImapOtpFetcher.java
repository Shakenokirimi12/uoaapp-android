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
        /** NO / BAD の応答行の全体。detail の大まかな理由 (maxconn など) を決めるためだけに使い、メッセージには入れない。 */
        @Nullable final String response;

        public ImapException(String message) { this(message, null); }

        ImapException(String message, @Nullable String response) {
            super(message);
            this.response = response;
        }
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
    /** 使い終わったメールの削除で From を確かめる数の上限 (CLEANUP_UID_WINDOW の範囲が対象なので検索より多め)。 */
    private static final int MAX_DELETE_CHECKS = 50;
    /** 自動取得以外 (削除だけ・期限なし) で読み取りの無応答を待つ上限。 */
    private static final int DEFAULT_STALL_LIMIT_MS = 15_000;
    private static final long RETRY_INTERVAL_MS = 3_000L;

    /**
     * 自動取得で、接続直後 (TCP + TLS・greeting・LOGIN) の無応答をどこまで待つか。ここで答えが無いのは経路が
     * 死んでいることが多いので、短く切って 1 回だけ繋ぎ直す (reconnectDelay)。SELECT から先は短く切らず、
     * 期限まで同じ接続で待つ。iOS の IMAPOTPFetcher.setupStallLimit と同じ。
     */
    static final long SETUP_STALL_LIMIT_MS = 4_000;
    /**
     * 1 回の取得で張る接続の上限 (最初の 1 本 + 繋ぎ直し 1 回)。
     * 以前は読み取りが 15 秒止まるたびに接続を捨てて (LOGOUT せずに) 最大 3 回繋ぎ直していた。捨てた接続は
     * サーバ側ではすぐには消えず、同じ利用者・同じ IP の同時接続数の上限 (Dovecot の
     * mail_max_userip_connections) に達して LOGIN が {@code NO [UNAVAILABLE]} で断られ、同じ受信箱を開く
     * セッションが増えて SELECT / SEARCH も遅くなる、という悪循環になっていた
     * (2026-10-22 の中継サーバの detail: search-stall:r2、login-no:unavailable:r3、select-stall:r2)。
     */
    static final int MAX_CONNECTIONS = 2;
    /** LOGIN を一時的に断られたときに、次を試すまで待つ時間。すぐに試し直しても、同時接続の上限は空かない。 */
    static final long REFUSED_RETRY_DELAY_MS = 5_000;
    /** 通信が切れたときに、繋ぎ直すまで待つ時間。 */
    static final long TRANSPORT_RETRY_DELAY_MS = 500;
    /** 繋ぎ直した接続で TLS・LOGIN・SELECT・SEARCH を済ませるのに要る時間。これより残りが短ければ繋ぎ直さない。 */
    static final long MIN_ATTEMPT_TIME_MS = 2_000;

    /**
     * 新着の OTP メールを探す範囲 (SELECT の UIDNEXT から何 UID 遡るか)。
     * 受信箱全体を {@code UID SEARCH FROM} で探すと、Dovecot は全メールのヘッダを見る。受信箱が大きいと
     * それだけで答えが返らず、search-stall になっていた。UID の範囲を先頭に置けば、サーバはその範囲の
     * メールだけを見る。PC の拡張が「既に送信しました」で 10 分前の依頼として送ってくる場合もあるので、
     * その間に他のメールが届いても外れないよう広めに取る。iOS の recentUIDWindow と同じ。
     */
    static final int RECENT_UID_WINDOW = 200;
    /**
     * 使ったあとに消す過去の OTP メールを、使ったメールの UID から何 UID 遡って探すか。以前は 7 日分を
     * FROM で探していたが、受信箱全体の SEARCH と同じく遅くなりうるので UID の範囲で絞る。
     * これより古く残ったものは消さない。iOS の cleanupUIDWindow と同じ。
     */
    static final int CLEANUP_UID_WINDOW = 500;

    private final String host;
    private final int port;
    /** どこまで進んだか (失敗したときの切り分け用) と、別のスレッドからの取りやめ (abort)。 */
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
     * 繋ぎ直すのは通信が切れたときと、LOGIN を一時的に断られたときだけで、1 回まで (reconnectDelay)。
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

    /**
     * 見つけた OTP メール。uid は後で使用済みとして消すときの上限。
     * PC 中継 (fetchOtpMail) で返すものは、見つけた接続を持ったままにする。{@link #deleteUsedMails} で
     * 同じ接続のまま消すか、{@link #close} で LOGOUT して閉じること (削除のために LOGIN し直さない)。
     */
    public static final class OtpMail implements AutoCloseable {
        @NonNull public final String code;
        public final int uid;
        @Nullable private ImapStream session;

        OtpMail(@NonNull String code, int uid) {
            this.code = code;
            this.uid = uid;
        }

        /** 見つけた接続を渡す。期限 (取得の待ち) はここで外す。削除は deleteUsedMails の打ち切りで切る。 */
        synchronized void attach(@NonNull ImapStream stream) {
            stream.setReadLimits(DEFAULT_STALL_LIMIT_MS, 0);
            session = stream;
        }

        @Nullable
        private synchronized ImapStream take() {
            ImapStream s = session;
            session = null;
            return s;
        }

        /**
         * 使い終わった OTP メールを消して、LOGOUT して閉じる。1 回だけ効く。消すのは、このメールと、
         * UID がこのメール以下で CLEANUP_UID_WINDOW の範囲にあり、From が送信元と完全に一致するもの。
         * timeoutMs を過ぎたら打ち切る (失敗しても結果には影響させない)。iOS の IMAPOTPMail.deleteUsedMails と同じ。
         */
        public void deleteUsedMails(long timeoutMs) {
            ImapStream stream = take();
            if (stream == null) return;
            try {
                stream.setReadLimits(timeoutMs, System.currentTimeMillis() + timeoutMs);
                List<Integer> toDelete = new ArrayList<>();
                // このメールの From は見つけたときに確かめてある。
                toDelete.add(uid);
                int fromUid = Math.max(1, uid - CLEANUP_UID_WINDOW + 1);
                try {
                    String tag = stream.nextTag();
                    stream.send(otpSearchCommand(tag, fromUid, uid));
                    List<Integer> older = allUids(stream.readUntilTagged(tag), fromUid);
                    older.sort(java.util.Collections.reverseOrder());
                    int checks = 0;
                    for (int u : older) {
                        if (u >= uid) continue;
                        if (checks++ >= MAX_DELETE_CHECKS) break;
                        // 消すのは From が OTP の送信元と完全に一致したものだけ (送信者名で名乗っただけのメールは消さない)。
                        if (isFromOtpSender(stream, u)) toDelete.add(u);
                    }
                } catch (ImapException e) {
                    // サーバは答えている。今回のメールだけ消す。
                    android.util.Log.w("ImapOtpFetcher", "過去分の検索に失敗 (今回分だけ消す): " + e);
                }
                StringBuilder set = new StringBuilder();
                for (int u : toDelete) {
                    if (set.length() > 0) set.append(',');
                    set.append(u);
                }
                String tag = stream.nextTag();
                stream.send(tag + " UID STORE " + set + " +FLAGS (\\Deleted)");
                stream.readUntilTagged(tag);
                tag = stream.nextTag();
                stream.send(tag + " EXPUNGE");
                stream.readUntilTagged(tag);
            } catch (Exception e) {
                android.util.Log.w("ImapOtpFetcher", "使用済みの OTP メールを消せなかった: " + e);
            } finally {
                stream.close();
            }
        }

        /** 消さずに手放す。LOGOUT して閉じる (サーバ側にセッションを残さない)。何度呼んでもよい。 */
        @Override
        public void close() {
            ImapStream stream = take();
            if (stream != null) stream.close();
        }
    }

    /**
     * PC 中継用。fetchOtpCode と同じ探し方だが、メールは消さない。中継サーバがコードを受け取った
     * (204) と分かってから {@link OtpMail#deleteUsedMails} で消す (送れなかったときに手で使えるように)。
     * 返した OtpMail は接続を持っているので、使い終わったら必ず閉じる。
     */
    @NonNull
    public OtpMail fetchOtpMail(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                                long requestedAt) throws Exception {
        return fetch(uid, pass, timeoutSeconds, requestedAt, false);
    }

    @NonNull
    private OtpMail fetch(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                          long requestedAt, boolean deleteAfterRead) throws Exception {
        long timeoutMs = timeoutSeconds * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception lastError = new ImapException("mailNotFound");
        // 「今から届くメール」だけを受け取るための下限。motp.cgi を叩いた直後に呼ばれるので、この時点で
        // 受信箱にある OTP メールは全部古い = 使えないコード。
        // 既読 (\Seen) かどうかで絞らないのは、ユーザーがメールアプリで先に開くと UNSEEN 検索に掛からず、
        // 取得も削除もできなくなっていたため (2026-09-15 実測: 受信箱に既読の OTP メールが 8 件残っていた)。
        int floorUid = -1;
        // 検索する UID の下限 (searchLowerBound)。最初の SELECT で決め、繋ぎ直しても変えない
        // (下限 UID と同じ範囲で探さないと、範囲の外の古いメールを新着と見誤りうる)。
        int searchFrom = -1;
        // From を確かめた結果 (UID ごと)。数秒おきに探し直すたびに同じメールのヘッダを取りに行かない。
        java.util.Map<Integer, Boolean> checked = new java.util.HashMap<>();
        int connections = 0;
        // 繋ぎ直す前に待つ時間。前の接続を閉じてから (LOGOUT を送ってから) 待つ。
        long pause = 0;
        while (true) {
            if (pause > 0 && progress.pause(pause)) throw aborted();
            if (progress.isAborted()) throw aborted();
            // 2 本目からは繋ぎ直し。detail に回数を付ける。
            if (connections++ > 0) progress.reconnected();
            ImapStream stream;
            try {
                stream = openAndSelect(uid, pass, SETUP_STALL_LIMIT_MS, timeoutMs, deadline);
            } catch (IOException | ImapException e) {
                if (progress.isAborted()) throw aborted();
                progress.recordFailure(e);
                // ID / パスワードの誤りは繋ぎ直さない (繰り返すとアカウントロックのおそれ)。
                pause = reconnectPause(e, connections, deadline);
                progress.enter(Progress.Stage.CONNECT);
                continue;
            }
            boolean handedOff = false;
            try {
                if (searchFrom < 0) searchFrom = searchLowerBound(stream.uidNext);
                while (true) {
                    try {
                        if (floorUid < 0) floorUid = resolveFloorUid(stream, requestedAt, searchFrom);
                        OtpMail mail = searchAndFetch(stream, floorUid, searchFrom, deleteAfterRead, checked);
                        if (mail != null) {
                            if (!deleteAfterRead) {
                                // 接続ごと渡す (中継サーバが受け取ったら、同じ接続で消す)。
                                mail.attach(stream);
                                handedOff = true;
                            }
                            return mail;
                        }
                        lastError = new ImapException("mailNotFound");
                        progress.recordFailure(lastError);
                    } catch (ImapException e) {
                        // サーバは答えている (コードが読めない・SEARCH を断られた)。同じ接続で続ける。
                        if (progress.isAborted()) throw aborted();
                        progress.recordFailure(e);
                        lastError = e;
                    } catch (IOException e) {
                        // 通信断、または期限まで答えが無かった。ログインしたあとの無応答は繋ぎ直さない。
                        if (progress.isAborted()) throw aborted();
                        progress.recordFailure(e);
                        pause = reconnectPause(e, connections, deadline);
                        // 繋ぎ直しを待つ間は connect にする。切れた段階 (search など) のままだと、中継の見張りが
                        // 「メールが来ない」と取り違える。投げるときの detail は記録した段階で作る。
                        progress.enter(Progress.Stage.CONNECT);
                        checked.clear();
                        break;
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) throw lastError;
                    // 受信箱を開いたままメールを待つ間だけ idle にする。
                    progress.enter(Progress.Stage.IDLE);
                    if (progress.pause(Math.min(RETRY_INTERVAL_MS, remaining))) throw aborted();
                }
            } finally {
                if (!handedOff) stream.close();
            }
        }
    }

    /** 繋ぎ直すなら待つ時間を返し、繋ぎ直さないなら error をそのまま投げる。 */
    private long reconnectPause(@NonNull Exception error, int connections, long deadline) throws Exception {
        ConnectionFailure failure = classify(error, progress.stage());
        Long delay = reconnectDelay(failure, connections, deadline - System.currentTimeMillis());
        if (delay == null) throw error;
        // ログは出さない (単体テストで android.util.Log が使えない)。繋ぎ直した回数は detail の :r1 に出る。
        return delay;
    }

    @NonNull
    private static ImapException aborted() {
        return new ImapException("aborted");
    }

    /** 繋ぎ直しの判断に使う、失敗の種類。iOS の IMAPOTPFetcher.ConnectionFailure と同じ。 */
    enum ConnectionFailure {
        /** ID かパスワードが違う。繰り返すとアカウントロックのおそれがある。 */
        AUTHENTICATION_REJECTED,
        /** LOGIN / SELECT を一時的に断られた ({@code NO [UNAVAILABLE]} など)。 */
        REFUSED,
        /** ログインしたあとのセッションが期限まで答えなかった。サーバが遅いだけで、接続は生きている。 */
        STALLED_AFTER_LOGIN,
        /** 繋がらない・切れた、または greeting / LOGIN に答えが無い (経路が死んでいる)。 */
        TRANSPORT
    }

    /** @param stage エラーを受け取ったときの段階。 */
    @NonNull
    static ConnectionFailure classify(@NonNull Exception error, @NonNull Progress.Stage stage) {
        String message = String.valueOf(error.getMessage());
        if (error instanceof ImapException && message.startsWith("commandFailed(")) {
            // a1 は LOGIN (接続して最初に送るコマンド)。
            return message.startsWith("commandFailed(a1)") && isAuthenticationFailure(message)
                    ? ConnectionFailure.AUTHENTICATION_REJECTED : ConnectionFailure.REFUSED;
        }
        if (error instanceof java.net.SocketTimeoutException) {
            switch (stage) {
                case SELECT:
                case SEARCH:
                case FETCH:
                case IDLE:
                    return ConnectionFailure.STALLED_AFTER_LOGIN;
                default:
                    break;
            }
        }
        return ConnectionFailure.TRANSPORT;
    }

    /**
     * 繋ぎ直す前に待つ時間。繋ぎ直さないなら null。
     * ログインしたあとに止まったら、同じ接続で期限まで待ったあとなので繋ぎ直さない (新しいセッションを
     * 増やしても、同じ受信箱の取り合いが増えるだけ)。
     *
     * @param connections これまでに張った接続の数 (今の接続を含む)。
     * @param remainingMs 期限までの残り。
     */
    @Nullable
    static Long reconnectDelay(@NonNull ConnectionFailure failure, int connections, long remainingMs) {
        if (connections >= MAX_CONNECTIONS) return null;
        long delay;
        switch (failure) {
            case REFUSED:
                delay = REFUSED_RETRY_DELAY_MS;
                break;
            case TRANSPORT:
                delay = TRANSPORT_RETRY_DELAY_MS;
                break;
            default:
                return null;
        }
        return remainingMs - delay >= MIN_ATTEMPT_TIME_MS ? delay : null;
    }

    /**
     * 接続して LOGIN + SELECT INBOX まで済ませる。失敗したら接続を閉じてから投げる。
     * SELECT が返した UIDNEXT は stream.uidNext に残す (返らなければ null)。
     *
     * @param setupStallMs   接続から LOGIN までの読み取りで無応答を待つ上限。止まったら繋ぎ直す。
     * @param sessionStallMs LOGIN のあと (SELECT 以降) の読み取りで無応答を待つ上限。
     * @param deadline       これを過ぎたら読み取りを待たない (epoch ミリ秒)。
     */
    private ImapStream openAndSelect(String uid, String pass, long setupStallMs, long sessionStallMs, long deadline)
            throws IOException, ImapException {
        progress.enter(Progress.Stage.CONNECT);
        ImapStream stream = new ImapStream(host, port, setupStallMs, deadline, progress);
        try {
            progress.enter(Progress.Stage.GREETING);
            stream.readLine(); // "* OK ... Dovecot ready."

            progress.enter(Progress.Stage.LOGIN);
            String tag = stream.nextTag();
            stream.send(tag + " LOGIN " + quote(uid) + " " + quote(pass));
            stream.readUntilTagged(tag);

            // LOGIN に答えたなら経路は生きている。ここから先が遅いのはサーバ側 (受信箱のロック待ちなど) なので、
            // 短く切って繋ぎ直さず、同じ接続で待つ。
            stream.setReadLimits(sessionStallMs, deadline);
            progress.enter(Progress.Stage.SELECT);
            tag = stream.nextTag();
            stream.send(tag + " SELECT INBOX");
            stream.uidNext = parseUidNext(stream.readUntilTagged(tag));
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
     * 探すのは fromUid 以降だけ (searchLowerBound)。latestGenuineUid と同じ範囲にしないと、範囲の外の
     * 古いメールを新着と見誤る。
     */
    private int resolveFloorUid(ImapStream stream, long startedAt, int fromUid) throws IOException, ImapException {
        // 下限は From を確かめる前の候補 (SEARCH FROM の結果) で決める。今ある候補はどれも今回のコードより
        // 古いので、本物かどうかに関係なく下限にしてよい。確かめた本物だけで決めると、偽メールが多くて
        // 本物まで確かめきれなかったときに下限が 0 になり、期限切れの古い本物を新しいコードとして使ってしまう。
        progress.enter(Progress.Stage.SEARCH);
        String tag = stream.nextTag();
        stream.send(otpSearchCommand(tag, fromUid, null));
        int latestUid = -1;
        for (int uid : allUids(stream.readUntilTagged(tag), fromUid)) latestUid = Math.max(latestUid, uid);
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
     * 無ければ null。探すのは fromUid 以降の UID だけ (受信箱全体を探すと大きな受信箱で止まる)。
     *
     * IMAP の SEARCH FROM は From ヘッダの部分一致 (送信者名 "idp-apply@u-aizu.ac.jp" <x@attacker> にも当たる)
     * なので候補を絞るだけに使い、From のアドレスは 1 通ずつ取って確かめる (CLAUDE-SECURITY F20)。
     * From は送り手が書く値なので、送信元を完全に名乗ったメールまでは区別できない
     * (大学のメールサーバの認証結果ヘッダは、実物のメールで確かめられていないので使っていない)。
     */
    @Nullable
    private Integer latestGenuineUid(ImapStream stream, int aboveUid, int fromUid,
                                     java.util.Map<Integer, Boolean> checked)
            throws IOException, ImapException {
        progress.enter(Progress.Stage.SEARCH);
        String tag = stream.nextTag();
        // 日付 (SINCE) では絞らない。UID の範囲で足りるうえ、日付はサーバの時間帯で区切られるので、
        // 日付をまたいだ直後 (PC 中継の「10 分前」を含む) に届いたメールを落としうる。
        stream.send(otpSearchCommand(tag, fromUid, null));
        List<Integer> uids = allUids(stream.readUntilTagged(tag), fromUid);
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
    private static boolean isFromOtpSender(ImapStream stream, int uid) throws IOException, ImapException {
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
     * コードを返し、deleteAfterRead ならそのメールを削除する。
     */
    @Nullable
    private OtpMail searchAndFetch(ImapStream stream, int floorUid, int fromUid, boolean deleteAfterRead,
                                   java.util.Map<Integer, Boolean> checked)
            throws IOException, ImapException {
        // 下限以下は今回要求したコードではない (期限切れの古いメール)。
        Integer genuine = latestGenuineUid(stream, floorUid, fromUid, checked);
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
     * detail の材料にする (iOS の IMAPOTPProgress と同じ)。取得の動きには影響しない (取りやめの abort を除く)。
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
        /** 今の接続。abort で閉じる。 */
        @Nullable private ImapStream activeConnection;
        private boolean aborted;
        private final java.util.concurrent.CountDownLatch abortLatch = new java.util.concurrent.CountDownLatch(1);

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

        /**
         * 取得を取りやめる。今の接続を LOGOUT して閉じ、この取得ではもう繋がない。どのスレッドからも呼べ、
         * すぐに戻る (送受信は別のスレッドで行う)。中継の時間切れの見張りと、仕事が止められたときに使う。
         * 止められたプロセスは凍結されることがあり、開いたままの接続はサーバ側でセッションとして残り続けて、
         * 次の依頼の LOGIN を同時接続数の上限で断らせる。iOS の IMAPOTPProgress.abort と同じ。
         */
        public void abort() {
            ImapStream connection;
            synchronized (this) {
                aborted = true;
                connection = activeConnection;
                activeConnection = null;
            }
            abortLatch.countDown();
            if (connection != null) connection.abortFromOutside();
        }

        public synchronized boolean isAborted() { return aborted; }

        /** 接続を始めたら登録する。取りやめたあとなら false (呼び出し側が接続を取りやめる)。 */
        synchronized boolean attach(@NonNull ImapStream connection) {
            if (aborted) return false;
            activeConnection = connection;
            return true;
        }

        synchronized void detach(@NonNull ImapStream connection) {
            if (activeConnection == connection) activeConnection = null;
        }

        /** ms だけ待つ。取りやめられたらすぐに戻って true。 */
        boolean pause(long ms) throws InterruptedException {
            return abortLatch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

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
         * 繋ぎ直した回数 (":r2" など)。サーバの応答は、角括弧の応答コード ([INUSE] など、英字だけ) と、
         * LOGIN の NO の大まかな理由 (loginRefusalKind) しか使わない。応答の本文はそのまま入れない。
         * 例外の文面はホスト名や IP を含みうるので、errno の名前 (ECONNREFUSED など) だけを取り出す。
         */
        @NonNull
        static String detail(@NonNull Exception error, @NonNull Stage s, int reconnects) {
            String stage = s.code();
            String code;
            String message = String.valueOf(error.getMessage());
            if (error instanceof InterruptedException
                    || (error instanceof ImapException && message.equals("aborted"))) {
                code = "cancelled";
            } else if (error instanceof ImapException && message.equals("mailNotFound")) {
                code = "mail-timeout";
            } else if (error instanceof ImapException && message.equals("codeNotFound")) {
                code = "code-not-found";
            } else if (error instanceof ImapException && message.startsWith("commandFailed(")) {
                // "commandFailed(a1): a1 NO [UNAVAILABLE] ..." (応答行が無い形もある)
                ImapException imap = (ImapException) error;
                int colon = message.indexOf("): ");
                String line = imap.response != null ? imap.response : colon >= 0 ? message.substring(colon + 3) : "";
                String[] words = line.split(" ");
                boolean bad = words.length > 1 && words[1].equalsIgnoreCase("BAD");
                code = stage + "-" + (bad ? "bad" : "no");
                List<String> reason = new ArrayList<>();
                Matcher m = Pattern.compile("\\[([A-Za-z-]{1,24})[\\] ]").matcher(line);
                if (m.find()) reason.add(m.group(1).toLowerCase(Locale.ROOT));
                if (s == Stage.LOGIN && !bad) reason.add(loginRefusalKind(line));
                if (!reason.isEmpty()) code += ":" + String.join("-", reason);
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

        /**
         * LOGIN の NO の理由を、決まった語にだけ読み替える (応答の本文は送らない)。
         * Dovecot は同じ利用者・同じ IP の同時接続数の上限 (mail_max_userip_connections) を超えると
         * {@code NO [UNAVAILABLE] Maximum number of connections from user+IP exceeded (...)} を返す。
         * 同じ [UNAVAILABLE] でも認証基盤の一時的な不調とは対処が違うので分ける。iOS の loginRefusalKind と同じ。
         */
        @NonNull
        static String loginRefusalKind(@NonNull String response) {
            return response.toLowerCase(Locale.ROOT).contains("maximum number of connections") ? "maxconn" : "other";
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
     * allUids のうち fromUid 以降。{@code UID n:*} は n が受信箱の最大の UID より大きいと「最大の UID のメール」に
     * 当たる (RFC 3501 の * は最大値で、n:* は *:n と同じ) ので、範囲より古いメールを手元で落とす。
     */
    @NonNull
    static List<Integer> allUids(@NonNull List<String> lines, int fromUid) {
        List<Integer> out = new ArrayList<>();
        for (int uid : allUids(lines)) {
            if (uid >= fromUid) out.add(uid);
        }
        return out;
    }

    /**
     * 差出人で探す UID SEARCH。UID の範囲を先に置くと、Dovecot はその範囲のメールのヘッダだけを見る。
     * toUid が null なら最新まで (n:*)。日付 (SINCE) では絞らない。iOS の otpSearchCommand と同じ。
     */
    @NonNull
    static String otpSearchCommand(@NonNull String tag, int fromUid, @Nullable Integer toUid) {
        String upper = toUid != null ? String.valueOf(toUid) : "*";
        return tag + " UID SEARCH UID " + Math.max(1, fromUid) + ":" + upper + " FROM \"" + OTP_SENDER + "\"";
    }

    /** 新着を探す UID の下限。UIDNEXT が分からなければ (RFC 3501 では必須で、Dovecot は必ず返す) 1 から探す。 */
    static int searchLowerBound(@Nullable Integer uidNext) {
        if (uidNext == null) return 1;
        return Math.max(1, uidNext - RECENT_UID_WINDOW);
    }

    /** SELECT の応答 "* OK [UIDNEXT 13045] Predicted next UID" から UIDNEXT を取る。無ければ null。 */
    @Nullable
    static Integer parseUidNext(@NonNull List<String> lines) {
        Pattern p = Pattern.compile("\\[UIDNEXT (\\d{1,10})\\]");
        for (String line : lines) {
            if (!line.startsWith("* OK")) continue;
            Matcher m = p.matcher(line);
            if (!m.find()) continue;
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 読み取りを待つ上限。無応答の上限と、期限 (0 なら無し) の早い方。期限を過ぎていても 0.5 秒は待つ。 */
    static int readWaitMs(long stallLimitMs, long deadline, long now) {
        long wait = deadline > 0 ? Math.min(stallLimitMs, Math.max(500, deadline - now)) : stallLimitMs;
        // setSoTimeout(0) は「無期限」なので、0 にはしない。
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, wait));
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
     *
     * 閉じるときは必ず LOGOUT を送ってから切る (close / abortFromOutside)。サーバ (Dovecot) は、処理中の
     * コマンドがあると切断に気づくのが遅れ、その間セッションが同じ利用者・同じ IP の同時接続数に数えられた
     * ままになる。止まった接続を LOGOUT せずに捨てて繋ぎ直すたびにこれが積み重なり、LOGIN が
     * {@code NO [UNAVAILABLE]} で断られていた (2026-10-22)。送り終えたら、遅くとも LOGOUT_TIMEOUT_MS で切る。
     */
    private static final class ImapStream implements AutoCloseable {
        /** 取得のタグ (a1, a2, ...) と重ならないもの。 */
        static final String LOGOUT_TAG = "z9";
        static final long LOGOUT_TIMEOUT_MS = 500;
        /**
         * 別のスレッドから送る LOGOUT。送信が詰まっても他の接続の LOGOUT を待たせないよう、数を決めない
         * (取りやめは 1 回の取得で 1 回だけ)。
         */
        private static final java.util.concurrent.ExecutorService LOGOUT_SENDER =
                java.util.concurrent.Executors.newCachedThreadPool(r -> daemon(r, "imap-logout"));
        /** 遅くとも LOGOUT_TIMEOUT_MS で切る。LOGOUT の送信とは別にして、送信が詰まっても切れるようにする。 */
        private static final java.util.concurrent.ScheduledExecutorService HARD_CLOSER =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "imap-close"));

        private static Thread daemon(Runnable r, String name) {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }

        private final SSLSocket socket;
        private final InputStream in;
        private final OutputStream out;
        @Nullable private final Progress progress;
        /** 送信は取得のスレッドと、取りやめの LOGOUT の両方から来うる。行が混ざらないようにする。 */
        private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();
        private final java.util.concurrent.atomic.AtomicBoolean logoutSent = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean handshaken;
        private int tagSeq = 0;
        /** 1 回の読み取りで無応答を待つ上限と、これを過ぎたら待たない時刻 (0 なら無し)。 */
        private volatile long stallLimitMs;
        private volatile long deadline;
        /** SELECT INBOX が返した UIDNEXT。返らなければ null。 */
        @Nullable Integer uidNext;

        /**
         * @param stallLimitMs 接続 (TCP + TLS) と読み取りで無応答を待つ上限。
         * @param deadline     これを過ぎたら読み取りを待たない (epoch ミリ秒、0 なら無し)。
         * @param progress     取りやめ (abort) で閉じられるように登録する先。取りやめたあとなら繋がない。
         */
        ImapStream(String host, int port, long stallLimitMs, long deadline, @Nullable Progress progress)
                throws IOException {
            this.stallLimitMs = stallLimitMs;
            this.deadline = deadline;
            this.progress = progress;
            SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            socket = s;
            // 繋いでいる間の取りやめ (Progress.abort) でも閉じられるように、繋ぐ前に登録する。
            if (progress != null && !progress.attach(this)) {
                closeSocket();
                throw new java.io.InterruptedIOException("aborted");
            }
            try {
                s.connect(new InetSocketAddress(host, port), currentReadWait());
                s.setSoTimeout(currentReadWait());
                // SSLSocket は既定ではホスト名検証をしない。iOS の NWConnection(TLS) と同じ強度にするため明示する。
                SSLParameters params = s.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                s.setSSLParameters(params);
                s.startHandshake();
                in = new BufferedInputStream(s.getInputStream());
                out = s.getOutputStream();
                // in / out を入れてから立てる (volatile の書き込み)。取りやめ (別のスレッド) はこれを見てから
                // out に書くので、組み立て途中の接続に書かない。
                handshaken = true;
            } catch (IOException | RuntimeException e) {
                if (progress != null) progress.detach(this);
                closeSocket();
                throw e;
            }
        }

        /** 読み取りの待ちを変える (LOGIN のあと、見つけたあと、削除の前)。 */
        void setReadLimits(long stallLimitMs, long deadline) {
            this.stallLimitMs = stallLimitMs;
            this.deadline = deadline;
        }

        private int currentReadWait() {
            return readWaitMs(stallLimitMs, deadline, System.currentTimeMillis());
        }

        /** 1 接続で複数コマンドを送るので、タグは使い捨てにして応答の取り違えを防ぐ。 */
        String nextTag() {
            return "a" + (++tagSeq);
        }

        void send(String line) throws IOException {
            writeLock.lock();
            try {
                out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                writeLock.unlock();
            }
        }

        /** tag で始まる応答 ("a1 OK ..." 等) が来るまで、途中の未タグ行を集めて返す。"a1 NO"/"a1 BAD" ならエラー。 */
        List<String> readUntilTagged(String tag) throws IOException, ImapException {
            List<String> lines = new ArrayList<>();
            while (true) {
                String line = readLine();
                if (line.startsWith(tag + " OK")) return lines;
                if (line.startsWith(tag + " NO") || line.startsWith(tag + " BAD")) {
                    // 応答行に資格情報が含まれることはないが、念のためメッセージには先頭の状態語だけ残す。
                    // 行の全体は detail の大まかな理由 (maxconn など) を決めるためだけに持つ。
                    throw new ImapException("commandFailed(" + tag + "): " + line.substring(0, Math.min(line.length(), 40)),
                            line);
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
            // 1 行ごとに待ちを決め直す (期限に近づくほど短くなる)。
            socket.setSoTimeout(currentReadWait());
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
            socket.setSoTimeout(currentReadWait());
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

        /** LOGOUT を 1 回だけ送る。送れたら true。止まった送信の後ろで LOGOUT_TIMEOUT_MS より長くは待たない。 */
        private boolean sendLogoutOnce() {
            if (!handshaken || !logoutSent.compareAndSet(false, true)) return false;
            try {
                // 先に待たずに取る (割り込まれたスレッドでも LOGOUT を送れるように)。
                if (!writeLock.tryLock()
                        && !writeLock.tryLock(LOGOUT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return false;
                }
                try {
                    out.write((LOGOUT_TAG + " LOGOUT\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return true;
                } finally {
                    writeLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                // 切れている。閉じるだけ。
                return false;
            }
        }

        private void closeSocket() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 閉じる途中の失敗は無視してよい
            }
        }

        /**
         * 読み書きしているスレッド (取得の本体) から閉じる。LOGOUT を送り、その答え (BYE と z9 OK) を
         * LOGOUT_TIMEOUT_MS まで待ってから切る (答えを読まずに切ると、未読のデータが残っていた場合に
         * RST になって LOGOUT が届かないことがある)。どの終わり方でもここを通す。何度呼んでもよい。
         */
        @Override
        public void close() {
            if (progress != null) progress.detach(this);
            // 送信や読み取りが止まっていても、遅くともこの時間で切る。
            java.util.concurrent.ScheduledFuture<?> hardClose =
                    HARD_CLOSER.schedule(this::closeSocket, LOGOUT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            try {
                if (sendLogoutOnce()) {
                    long until = System.currentTimeMillis() + LOGOUT_TIMEOUT_MS;
                    setReadLimits(LOGOUT_TIMEOUT_MS, until);
                    while (System.currentTimeMillis() < until) {
                        if (readLine().startsWith(LOGOUT_TAG + " ")) break;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 答えが無い・切れた。切るだけ。
            } finally {
                closeSocket();
                hardClose.cancel(false);
            }
        }

        /**
         * 別のスレッド (見張り・取りやめ) から閉じる。すぐに戻る。LOGOUT は別のスレッドで送り、
         * LOGOUT_TIMEOUT_MS 後に切る。読み取りで待っている取得の本体は、切れたことで例外になる
         * (Progress.isAborted を見て繋ぎ直さない)。メインスレッドから呼ばれても通信しない。
         */
        void abortFromOutside() {
            // 送れない (握手の前・既に送った・切れている) なら待たずに切る。
            LOGOUT_SENDER.execute(() -> {
                try {
                    if (!sendLogoutOnce()) closeSocket();
                } catch (RuntimeException e) {
                    // 別のスレッドで投げるとプロセスごと落ちる。切るだけにする。
                    closeSocket();
                }
            });
            HARD_CLOSER.schedule(this::closeSocket, LOGOUT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
