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

import com.shakenokirimi12.uoa_app.services.diagnostics.OtpDiagnostics;

/**
 * stdmsv1.u-aizu.ac.jp (Dovecot, IMAP4rev1, AUTH=PLAIN、IMAPS/993) から IdP のワンタイムパスワード
 * メールを取り出す最小限の IMAP クライアント。iOS の IMAPOTPFetcher の移植。
 * RFC 3501 全体は実装せず、LOGIN -> SELECT -> UID FETCH (直近のヘッダ、新着は 1 秒おきに) -> UID FETCH (本文)
 * -> LOGOUT だけをサポートする。サーバ側の検索 (SEARCH) は使わない (RECENT_UID_WINDOW の説明を参照)。
 * AINS ID/パスワードは CampusSquare/IdP ログインと共通 (別のメールアカウント設定は無い)。
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
    /** 自動取得以外 (削除だけ・期限なし) で読み取りの無応答を待つ上限。 */
    private static final int DEFAULT_STALL_LIMIT_MS = 15_000;
    /**
     * 新着を確かめる間隔。確かめるのは新しい UID のヘッダの FETCH だけで軽いので、iOS の newMailWait と同じ 1 秒にする
     * (以前は 3 秒おきの SEARCH で、届いてから最大 3 秒を余計に待っていた)。
     * IMAP IDLE は使わない。ここの読み取りは止まった時点の行の途中を捨てるので、IDLE の待ちを読み取りの時間切れで
     * 区切れず、届いたことを早く知る手段 (SSLSocket の available) も、復号前のデータを数えないので当てにならない。
     * 次の {@code UID FETCH n:*} 自体でサーバは受信箱を確かめ直す (NOOP と同じ) ので、NOOP も送らない。
     */
    static final long NEW_MAIL_WAIT_MS = 1_000L;

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
     * 新着の OTP メールを探す範囲 (SELECT の UIDNEXT から何 UID 遡るか)。この範囲のヘッダ (From) と到着時刻を
     * 1 回の {@code UID FETCH} で取り、差出人と時刻は手元で確かめる。iOS の recentUIDWindow と同じ。
     *
     * サーバ側の検索 ({@code UID SEARCH ... FROM}) は使わない。2026-10-24〜25 の中継サーバの detail は
     * search-stall (imap-error) で、{@code UID SEARCH UID n:* FROM} に範囲を絞っても (2026-10-22) SEARCH の答えが
     * 期限までに返らなかった。SEARCH の重さはサーバ次第で、範囲を絞っても軽くなるとは限らない (stdmsv1 の設定は
     * 確かめられない)。UID を指定した FETCH は、指定したメールのヘッダしか読まない。
     * 「既に送信しました」(10 分前の依頼) で前回のメールを使う場合も、その間に 20 通を超えて届くことはまず無い。
     */
    static final int RECENT_UID_WINDOW = 20;
    /**
     * 直近の範囲のメールが全部、依頼の時刻以降に届いていたら、その手前を何 UID 見るか (1 回だけ)。
     * RECENT_UID_WINDOW と合わせて、以前の SEARCH の範囲 (200 UID) と同じにする。iOS の olderUIDWindow と同じ。
     */
    static final int OLDER_UID_WINDOW = 180;
    /**
     * 使ったあとに消す過去の OTP メールを探すとき、1 回のヘッダの FETCH で見る UID の幅。SEARCH は使わない。
     * 範囲に上限は無く (以前は 100 UID まで)、古い方へこの幅ずつ、削除の時間の枠が尽きるまで遡る
     * (SPEC.md「8. OTP メールの削除」)。
     */
    static final int CLEANUP_UID_CHUNK = 200;

    /**
     * below (含まない) より下で、次にヘッダを確かめる UID の範囲 {from, to}。もう無ければ null。
     * 上から下へ CLEANUP_UID_CHUNK ずつ、UID 1 まで隙間なく覆う。
     */
    @Nullable
    static int[] cleanupChunkBelow(int below) {
        int to = below - 1;
        if (to < 1) return null;
        return new int[] {Math.max(1, to - CLEANUP_UID_CHUNK + 1), to};
    }

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

    /** 手順の記録と、どこまで進んだか (診断の記録に使う)。 */
    @NonNull
    public Progress progress() { return progress; }

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
        /** 取得の間に差出人を確かめた OTP メールの UID と、見た範囲の下限。削除の対象を決めるのに使う。 */
        @NonNull private final List<Integer> knownOtpUids;
        private final int scannedFrom;
        @Nullable private ImapStream session;

        OtpMail(@NonNull String code, int uid) {
            this(code, uid, new ArrayList<>(), uid);
        }

        OtpMail(@NonNull String code, int uid, @NonNull List<Integer> knownOtpUids, int scannedFrom) {
            this.code = code;
            this.uid = uid;
            this.knownOtpUids = knownOtpUids;
            this.scannedFrom = scannedFrom;
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
         * UID がこのメールより小さく、From が送信元と完全に一致するものすべて (範囲の上限は無い)。
         * 取得の間に見た範囲 (scannedFrom 以降) は確かめ済みのものを使い、その手前は CLEANUP_UID_CHUNK ずつ古い方へ
         * ヘッダを取り直す (SEARCH は使わない)。遡るのは timeoutMs の 3 分の 2 までで、残りは削除に使う。
         * 遡りきれなかった分は、次に成功したときの削除で消える。失敗しても結果には影響させない。
         * 呼ぶのはログイン (または中継) が成功したときだけ (SPEC.md「8. OTP メールの削除」)。
         */
        public void deleteUsedMails(long timeoutMs) {
            ImapStream stream = take();
            if (stream == null) return;
            try {
                long startedAt = System.currentTimeMillis();
                long scanUntil = startedAt + timeoutMs * 2 / 3;
                stream.setReadLimits(timeoutMs, startedAt + timeoutMs);
                java.util.TreeSet<Integer> toDelete = new java.util.TreeSet<>();
                // このメールの From は見つけたときに確かめてある。
                toDelete.add(uid);
                for (int u : knownOtpUids) {
                    if (u <= uid) toDelete.add(u);
                }
                // 両端を決めるので n:* の落とし穴も無い。
                int[] range = cleanupChunkBelow(Math.min(uid, scannedFrom));
                try {
                    while (range != null && System.currentTimeMillis() < scanUntil) {
                        // 消すのは From が OTP の送信元と完全に一致したものだけ (送信者名で名乗っただけのメールは消さない)。
                        for (FetchedMail m : fetchHeaders(stream, range[0], range[1])) {
                            if (isOtpSender(m.fromHeader)) toDelete.add(m.uid);
                        }
                        range = cleanupChunkBelow(range[0]);
                    }
                } catch (ImapException e) {
                    // サーバは答えている。ここまでに確かめた分だけ消す。
                    // 例外の文面はサーバの応答行を含みうるので、決まった名前だけを残す (Progress.describe)。
                    android.util.Log.w("ImapOtpFetcher", "過去分の確認に失敗 (確かめた分だけ消す): " + Progress.describe(e));
                }
                StringBuilder set = new StringBuilder();
                for (int u : toDelete) {
                    if (set.length() > 0) set.append(',');
                    set.append(u);
                }
                deleteUids(stream, set.toString());
            } catch (Exception e) {
                android.util.Log.w("ImapOtpFetcher", "使用済みの OTP メールを消せなかった: " + Progress.describe(e));
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
     * fetchOtpCode と同じ探し方だが、メールは消さない。PC 中継は中継サーバがコードを受け取った (204) と、
     * アプリのログインは IdP がコードを受け付けたと分かってから {@link OtpMail#deleteUsedMails} で消す
     * (失敗したときに手で使えるように)。返した OtpMail は接続を持っているので、使い終わったら必ず閉じる。
     */
    @NonNull
    public OtpMail fetchOtpMail(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                                long requestedAt) throws Exception {
        return fetch(uid, pass, timeoutSeconds, requestedAt, false);
    }

    @NonNull
    private OtpMail fetch(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                          long requestedAt, boolean deleteAfterRead) throws Exception {
        progress.note("取得を始める: 期限 " + timeoutSeconds + "s、受け付けるメールは依頼の "
                + seconds(System.currentTimeMillis() - requestedAt) + "s 前から");
        try {
            OtpMail mail = fetchOnce(uid, pass, timeoutSeconds, requestedAt, deleteAfterRead);
            progress.finish();
            progress.note("結果: コードを見つけた");
            return mail;
        } catch (Exception e) {
            progress.finish();
            progress.note("結果: 失敗 " + Progress.describe(e) + " (" + progress.detail(e) + ")");
            throw e;
        }
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }

    @NonNull
    private OtpMail fetchOnce(@NonNull String uid, @NonNull String pass, long timeoutSeconds,
                              long requestedAt, boolean deleteAfterRead) throws Exception {
        long timeoutMs = timeoutSeconds * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception lastError = new ImapException("mailNotFound");
        // 見る範囲の下限 (scanLowerBound) と、「受信箱を開いたあとに届いた」とみなす UID (最初の SELECT の UIDNEXT)。
        // 最初の SELECT で決め、繋ぎ直しても変えない (繋ぎ直す間に届いたメールも「開いたあと」に入れる)。
        int scanFrom = -1;
        int arrivedFrom = Integer.MAX_VALUE;
        // 差出人が送信元そのものだったメール (UID ごと)。繋ぎ直しても持ち越す (削除の対象にも使う)。
        // 既読 (\Seen) かどうかでは絞らない。利用者がメールアプリで先に開いても使えるようにするため
        // (2026-09-15 実測: 受信箱に既読の OTP メールが 8 件残っていた)。
        java.util.Map<Integer, FetchedMail> otpMails = new java.util.HashMap<>();
        // 本文からコードが読めなかった・読む前に消されていたメール。次からは使わず、これより古いメールにも戻らない。
        java.util.Set<Integer> unreadable = new java.util.HashSet<>();
        // そのうちコードが読めなかったものがあったか (期限で返す理由を codeNotFound にする)。
        boolean codeUnreadable = false;
        // 今の FETCH を始めた時刻。期限での打ち切りか、サーバが答えなくなったかを分ける (endedByDeadline)。
        long stepStartedAt = System.currentTimeMillis();
        // 直近の範囲より前も 1 回だけ見たか (needsOlderScan)。
        boolean scannedOlder = false;
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
                progress.note("受信箱を開けなかった: " + Progress.describe(e));
                progress.recordFailure(e);
                // ID / パスワードの誤りは繋ぎ直さない (繰り返すとアカウントロックのおそれ)。
                pause = reconnectPause(e, connections, deadline);
                progress.enter(Progress.Stage.CONNECT);
                continue;
            }
            boolean handedOff = false;
            try {
                if (scanFrom < 0) {
                    scanFrom = scanLowerBound(stream.uidNext);
                    // UIDNEXT が無ければ (RFC 3501 では必須で、Dovecot は必ず返す)、届いた時刻だけで決める。
                    arrivedFrom = stream.uidNext != null ? stream.uidNext : Integer.MAX_VALUE;
                }
                // この接続で次に見る UID。繋ぎ直したら範囲の全体を見直す (切れていた間に届いたものを取りこぼさない)。
                int nextUid = scanFrom;
                Progress.Stage stage = Progress.Stage.SCAN;
                while (true) {
                    try {
                        progress.enter(stage);
                        stepStartedAt = System.currentTimeMillis();
                        List<FetchedMail> fetched = fetchHeaders(stream, nextUid, null);
                        for (FetchedMail m : fetched) {
                            if (isOtpSender(m.fromHeader)) otpMails.put(m.uid, m);
                        }
                        nextUid = nextUnseenUid(fetched, nextUid, stream.uidNext);
                        // 範囲のメールが全部依頼のあとに届いていたら (「既に送信しました」の 10 分前など)、手前にも今回の
                        // メールがありうる。1 回だけ OLDER_UID_WINDOW 件遡って見る (以前の SEARCH と同じ 200 UID まで)。
                        if (stage == Progress.Stage.SCAN && !scannedOlder && scanFrom > 1
                                && pickOtpMail(otpMails.values(), requestedAt, arrivedFrom, unreadable) == null
                                && needsOlderScan(fetched, requestedAt)) {
                            int olderFrom = Math.max(1, scanFrom - OLDER_UID_WINDOW);
                            for (FetchedMail m : fetchHeaders(stream, olderFrom, scanFrom - 1)) {
                                if (isOtpSender(m.fromHeader)) otpMails.put(m.uid, m);
                            }
                            // 取り終えてから立てる (途中で切れて繋ぎ直したら、もう一度見る)。
                            scannedOlder = true;
                            scanFrom = olderFrom;
                        }
                        FetchedMail target = pickOtpMail(otpMails.values(), requestedAt, arrivedFrom, unreadable);
                        if (target != null) {
                            progress.enter(Progress.Stage.FETCH);
                            String text = fetchText(stream, target.uid, progress);
                            String code = text != null ? extractCode(text) : null;
                            progress.note(text == null ? "本文が無かった (消された) uid=" + target.uid
                                    : code == null ? "本文からコードを読めなかった uid=" + target.uid
                                    : "本文からコードを読めた uid=" + target.uid);
                            if (code != null) {
                                OtpMail mail = new OtpMail(code, target.uid, new ArrayList<>(otpMails.keySet()), scanFrom);
                                if (deleteAfterRead) {
                                    deleteAfterRead(stream, target.uid);
                                } else {
                                    // 接続ごと渡す (中継サーバが受け取ったら、同じ接続で消す)。
                                    mail.attach(stream);
                                    handedOff = true;
                                }
                                return mail;
                            }
                            // 次からは使わず、より古いメールにも戻らない (新しい方を使えないのに、古い方の使えなくなった
                            // コードを入れない)。読めなかったメールは消さずに残す (デバッグのため)。
                            unreadable.add(target.uid);
                            if (text == null) {
                                // 見ている間に消された (別のメールソフトなど)。
                                otpMails.remove(target.uid);
                                lastError = new ImapException("mailNotFound");
                            } else {
                                codeUnreadable = true;
                                lastError = new ImapException("codeNotFound");
                            }
                        } else {
                            // 読めなかったメールがあれば、期限まで新しいものが来なかったときはそれを理由にする。
                            lastError = new ImapException(codeUnreadable ? "codeNotFound" : "mailNotFound");
                        }
                        progress.recordFailure(lastError);
                    } catch (ImapException e) {
                        // サーバは答えている (FETCH を断られた)。同じ接続で続ける。
                        if (progress.isAborted()) throw aborted();
                        progress.note("サーバが断った: " + Progress.describe(e));
                        progress.recordFailure(e);
                        lastError = e;
                    } catch (IOException e) {
                        // 通信断、または期限まで答えが無かった。ログインしたあとの無応答は繋ぎ直さない。
                        if (progress.isAborted()) throw aborted();
                        progress.note("通信が止まった・切れた: " + Progress.describe(e) + "、残り "
                                + seconds(deadline - System.currentTimeMillis()) + "s");
                        // 新着の確認 (RECHECK) で期限が来て、読み取りが切られただけなら、メールが来なかった。
                        if (endedByDeadline(e, progress.stage(), deadline, stepStartedAt, System.currentTimeMillis())) {
                            throw lastError;
                        }
                        progress.recordFailure(e);
                        pause = reconnectPause(e, connections, deadline);
                        // 繋ぎ直しを待つ間は connect にする。切れた段階 (scan など) のままだと、中継の見張りが
                        // 「メールが来ない」と取り違える。投げるときの detail は記録した段階で作る。
                        progress.enter(Progress.Stage.CONNECT);
                        break;
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) throw lastError;
                    // 受信箱を開いたままメールを待つ間だけ idle にする。
                    progress.enter(Progress.Stage.IDLE);
                    progress.note(seconds(Math.min(NEW_MAIL_WAIT_MS, remaining)) + "s 待つ (IDLE なし)、残り " + seconds(remaining) + "s");
                    if (progress.pause(Math.min(NEW_MAIL_WAIT_MS, remaining))) throw aborted();
                    stage = Progress.Stage.RECHECK;
                }
            } finally {
                if (!handedOff) stream.close();
            }
        }
    }

    /**
     * 読み取り済みの OTP メールを削除する (ユーザー了承済み、2026-09-07)。アプリのログインの経路 (fetchOtpCode)。
     * 削除に失敗してもコードは取れているので、ログインは続ける。ただし黙って捨てると「メールが消えない」ときに
     * 原因が分からなくなるので必ずログへ残す (コードは出さない)。
     */
    private static void deleteAfterRead(ImapStream stream, int uid) {
        try {
            deleteUids(stream, String.valueOf(uid));
        } catch (Exception e) {
            android.util.Log.w("ImapOtpFetcher", "OTPメールの削除に失敗: " + Progress.describe(e));
        }
    }

    /**
     * 新着を確かめる段階 (RECHECK) で、期限が来たために読み取りが時間切れになったか。期限の近くでは読み取りを待つ上限が
     * 0.5 秒まで縮む (readWaitMs) ので、最後の確認の答えが間に合わないと「止まった」ように見える。以前はこれが
     * imap-error (search-stall) として返り、本当は「メールが期限までに見つからなかった」(mail-timeout) のを隠していた。
     * その FETCH を期限の LATE_STEP_WINDOW_MS より前に始めていたら、サーバがそれだけ答えなかったので、止まった
     * (imap-error) のままにする (mail-timeout にすると、拡張が答えないサーバに頼み直す)。
     * iOS の endedByDeadline と同じ (Android の IDLE は通信しない待ちなので、ここでは止まらない)。
     */
    static boolean endedByDeadline(@NonNull Exception error, @NonNull Progress.Stage stage, long deadline,
                                   long stepStartedAt, long now) {
        return error instanceof java.net.SocketTimeoutException && now >= deadline
                && deadline - stepStartedAt <= LATE_STEP_WINDOW_MS
                && (stage == Progress.Stage.RECHECK || stage == Progress.Stage.IDLE);
    }

    /** 期限のこれだけ前より後に始めた確認の FETCH が期限で切られたら、期限での打ち切りとみなす。iOS の lateStepWindow と同じ。 */
    static final long LATE_STEP_WINDOW_MS = 2_000;

    /** 繋ぎ直すなら待つ時間を返し、繋ぎ直さないなら error をそのまま投げる。 */
    private long reconnectPause(@NonNull Exception error, int connections, long deadline) throws Exception {
        ConnectionFailure failure = classify(error, progress.stage());
        Long delay = reconnectDelay(failure, connections, deadline - System.currentTimeMillis());
        if (delay == null) throw error;
        progress.note("繋ぎ直す (" + failure + "、" + seconds(delay) + "s 後)");
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
                case SCAN:
                case RECHECK:
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
        progress.beginConnection();
        progress.note("接続を始める " + host + ":" + port + " (上限 " + seconds(readWaitMs(setupStallMs, deadline, System.currentTimeMillis()))
                + "s)、残り " + seconds(deadline - System.currentTimeMillis()) + "s");
        ImapStream stream = new ImapStream(host, port, setupStallMs, deadline, progress);
        try {
            progress.enter(Progress.Stage.GREETING);
            String greeting = stream.readLine(); // "* OK [CAPABILITY ...] Dovecot ready."
            progress.note("greeting " + greetingSummary(greeting));

            progress.enter(Progress.Stage.LOGIN);
            String tag = stream.nextTag();
            progress.note(tag + " LOGIN *** ***");
            stream.send(tag + " LOGIN " + quote(uid) + " " + quote(pass));
            stream.readUntilTagged(tag);
            progress.note(tag + " OK (LOGIN)");

            // LOGIN に答えたなら経路は生きている。ここから先が遅いのはサーバ側 (受信箱のロック待ちなど) なので、
            // 短く切って繋ぎ直さず、同じ接続で待つ。
            stream.setReadLimits(sessionStallMs, deadline);
            progress.enter(Progress.Stage.SELECT);
            tag = stream.nextTag();
            progress.note(tag + " SELECT INBOX");
            stream.send(tag + " SELECT INBOX");
            List<String> selected = stream.readUntilTagged(tag);
            stream.uidNext = parseUidNext(selected);
            String exists = "?";
            for (String line : selected) {
                Matcher m = Pattern.compile("^\\* ([0-9]+) EXISTS").matcher(line);
                if (m.find()) exists = m.group(1);
            }
            progress.note(tag + " OK (SELECT) uidnext=" + (stream.uidNext != null ? stream.uidNext : "なし") + " exists=" + exists);
            return stream;
        } catch (IOException | ImapException e) {
            stream.close();
            throw e;
        }
    }

    /**
     * fromUid から toUid (null なら最新まで) のメールの到着時刻と From ヘッダを 1 回の FETCH で取る。
     * 範囲の外のもの ({@code n:*} の落とし穴、勝手に届いた FETCH) は落とす。
     */
    @NonNull
    private static List<FetchedMail> fetchHeaders(ImapStream stream, int fromUid, @Nullable Integer toUid)
            throws IOException, ImapException {
        String tag = stream.nextTag();
        String command = headerFetchCommand(tag, fromUid, toUid);
        long before = stream.receivedBytes;
        stream.note(command + stream.remaining());
        stream.send(command);
        List<FetchedMail> mails = mailsInRange(parseFetchedMails(stream.readResponsesUntilTagged(tag)), fromUid, toUid);
        int otp = 0;
        for (FetchedMail m : mails) if (isOtpSender(m.fromHeader)) otp++;
        stream.note(tag + " OK " + mails.size() + " 通 (OTP メール " + otp + " 通) " + (stream.receivedBytes - before) + "B");
        return mails;
    }

    /** greeting の要約 (状態の語と、[CAPABILITY ...] の語)。サーバの名前などの文は書かない。iOS の greetingSummary と同じ。 */
    @NonNull
    static String greetingSummary(@NonNull String line) {
        String[] words = line.split(" ");
        String status = words.length > 1 ? words[1] : "?";
        Matcher m = Pattern.compile("\\[CAPABILITY ([A-Za-z0-9 =+.-]{1,300})\\]").matcher(line);
        return m.find() ? status + " CAPABILITY " + m.group(1) : status;
    }

    /** 1 通の本文。メールが無くなっていた (FETCH の応答が無い) ら null。 */
    @Nullable
    private static String fetchText(ImapStream stream, int uid, Progress progress) throws IOException, ImapException {
        String tag = stream.nextTag();
        String command = tag + " UID FETCH " + uid + " (BODY.PEEK[TEXT])";
        long before = stream.receivedBytes;
        progress.note(command + stream.remaining());
        stream.send(command);
        String text = null;
        for (FetchedMail m : parseFetchedMails(stream.readResponsesUntilTagged(tag))) {
            if (m.uid == uid) text = m.text;
        }
        progress.note(tag + " OK " + (stream.receivedBytes - before) + "B");
        return text;
    }

    /**
     * uidSet (UID のカンマ区切り) のメールだけを消す。
     *
     * 以前は \Deleted を付けたあと素の EXPUNGE を送っていた。EXPUNGE は受信箱で \Deleted の付いた
     * メールを全部消すので、利用者が別のメールソフトで「削除済み」にしただけ (まだ戻せる) のメールまで
     * 消していた。UIDPLUS (RFC 4315) があれば UID EXPUNGE で指定した UID だけを消す。無ければ
     * EXPUNGE はしない (印は付いたまま残り、利用者のメールソフトが消すか戻すかを決める)。
     */
    private static void deleteUids(ImapStream stream, String uidSet) throws IOException, ImapException {
        int count = uidSet.isEmpty() ? 0 : uidSet.split(",").length;
        String tag = stream.nextTag();
        stream.note(tag + " UID STORE (" + count + " 通) +FLAGS (\\Deleted)");
        stream.send(tag + " UID STORE " + uidSet + " +FLAGS (\\Deleted)");
        stream.readUntilTagged(tag);
        tag = stream.nextTag();
        stream.note(tag + " CAPABILITY");
        stream.send(tag + " CAPABILITY");
        boolean uidPlus = hasCapability(stream.readUntilTagged(tag), "UIDPLUS");
        stream.note(tag + " OK UIDPLUS=" + (uidPlus ? "あり" : "なし"));
        if (!uidPlus) {
            stream.note("UIDPLUS が無いので EXPUNGE しない (削除の印だけ付けた)");
            return;
        }
        tag = stream.nextTag();
        stream.note(tag + " UID EXPUNGE (" + count + " 通)");
        stream.send(tag + " UID EXPUNGE " + uidSet);
        stream.readUntilTagged(tag);
        stream.note(tag + " OK (EXPUNGE)");
    }

    /** CAPABILITY の未タグ応答 ("* CAPABILITY IMAP4rev1 UIDPLUS ...") に capability があるか。 */
    static boolean hasCapability(@NonNull List<String> lines, @NonNull String capability) {
        for (String line : lines) {
            String[] words = line.trim().split("\\s+");
            if (words.length < 2 || !"*".equals(words[0]) || !"CAPABILITY".equalsIgnoreCase(words[1])) continue;
            for (int i = 2; i < words.length; i++) {
                if (capability.equalsIgnoreCase(words[i])) return true;
            }
        }
        return false;
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
        /**
         * detail の先頭に付く段階の名前。SCAN は最初に直近のヘッダを取る FETCH、RECHECK は新着を待ったあとに
         * 新しい UID のヘッダを取る FETCH、FETCH は見つけたメールの本文の FETCH、IDLE は新着を待つ間。
         * 2026-10-25 までの SEARCH は使わなくなった (RECENT_UID_WINDOW)。iOS の IMAPOTPProgress.Stage と同じ。
         */
        public enum Stage {
            CONNECT, GREETING, LOGIN, SELECT, SCAN, RECHECK, FETCH, IDLE;

            @NonNull
            public String code() { return name().toLowerCase(Locale.ROOT); }
        }

        private Stage stage = Stage.CONNECT;
        private int reconnects;
        @Nullable private Exception failure;
        private Stage failureStage = Stage.CONNECT;
        private int failureReconnects;
        /** 最初の接続を始めた時刻 (epoch ミリ秒、まだなら -1)。detail の時間 (timing) の起点。 */
        private long origin = -1;
        /** 起点から、今の接続で TCP+TLS・LOGIN・SELECT が終わるまでのミリ秒 (まだなら -1)。 */
        private long connectedAfter = -1;
        private long loggedInAfter = -1;
        private long selectedAfter = -1;
        /** 起点から、今の接続を始めるまでのミリ秒 (phaseTimingsMs の tcp_tls を今の接続の分だけにする)。 */
        private long connectionStartedAfter = 0;
        /** 取得 (fetch) が終わった時刻 (まだなら -1)。これより後 (中継への返送・メールの削除) の時間を取得の時間に数えない。 */
        private long finishedAt = -1;
        /** エラーを受け取った時点の、今の接続の段階の時刻 (timing の引数)。 */
        private long failureConnected = -1;
        private long failureLoggedIn = -1;
        private long failureSelected = -1;
        private long failureElapsed = -1;
        /** 手順の記録 (Logcat の Log.i、タグ IMAPOTPFetcher と、Wana の breadcrumbs)。 */
        @NonNull public final OtpDiagnostics.Trail trail = new OtpDiagnostics.Trail("IMAPOTPFetcher");

        /** 手順を 1 行残す。コマンドの名前・応答の状態・件数・バイト数・時間だけ (ID / パスワード・コード・メールの中身・差出人・件名は書かない)。 */
        public void note(@NonNull String message) { trail.note(message); }

        /**
         * 段階ごとの時間 (ミリ秒)。tcp_tls は今の接続を始めてから TLS まで、login・select はそれぞれの往復、
         * after_select は SELECT から取得の終わりまで (メールを探して待った時間)、total は最初の接続から取得の終わりまで
         * (終わっていなければ今まで)。iOS の phaseTimingsMs と同じ。
         */
        @NonNull
        public synchronized java.util.Map<String, Long> phaseTimingsMs() {
            java.util.Map<String, Long> out = new java.util.TreeMap<>();
            out.put("reconnects", (long) reconnects);
            if (connectedAfter >= 0) out.put("tcp_tls", connectedAfter - connectionStartedAfter);
            if (loggedInAfter >= 0) out.put("login", loggedInAfter - Math.max(0, connectedAfter));
            if (selectedAfter >= 0) out.put("select", selectedAfter - Math.max(0, loggedInAfter));
            if (origin >= 0) {
                long total = endTime() - origin;
                out.put("total", total);
                if (selectedAfter >= 0) out.put("after_select", total - selectedAfter);
            }
            return out;
        }
        /** 今の接続。abort で閉じる。 */
        @Nullable private ImapStream activeConnection;
        private boolean aborted;
        private final java.util.concurrent.CountDownLatch abortLatch = new java.util.concurrent.CountDownLatch(1);

        /** 接続を始める。今の接続の時間を消す (起点は最初の接続のまま)。 */
        synchronized void beginConnection() {
            stage = Stage.CONNECT;
            long now = System.currentTimeMillis();
            if (origin < 0) origin = now;
            connectionStartedAfter = now - origin;
            connectedAfter = -1;
            loggedInAfter = -1;
            selectedAfter = -1;
        }

        /** 段階に入る。GREETING に入ったら TCP+TLS、SELECT に入ったら LOGIN、SCAN に入ったら SELECT が終わった時刻として残す。 */
        synchronized void enter(@NonNull Stage s) {
            stage = s;
            if (origin < 0) return;
            long after = System.currentTimeMillis() - origin;
            if (s == Stage.GREETING && connectedAfter < 0) connectedAfter = after;
            if (s == Stage.SELECT && loggedInAfter < 0) loggedInAfter = after;
            if (s == Stage.SCAN && selectedAfter < 0) selectedAfter = after;
        }

        /**
         * 手順の記録に書くエラーの名前。サーバの応答は、タグ・状態の語・角括弧の応答コードだけ (本文にはアドレスなどが入りうる)。
         * 例外の文面はホスト名や IP を含みうるので、クラス名と errno の名前だけ。
         */
        @NonNull
        public static String describe(@NonNull Exception error) {
            String message = String.valueOf(error.getMessage());
            if (error instanceof ImapException) {
                ImapException imap = (ImapException) error;
                if (message.startsWith("commandFailed(")) {
                    int close = message.indexOf(')');
                    String tag = close > 0 ? message.substring("commandFailed(".length(), close) : "?";
                    String line = imap.response != null ? imap.response : "";
                    String[] words = line.split(" ");
                    String status = words.length > 1 ? words[1] : "?";
                    Matcher m = Pattern.compile("\\[([A-Za-z-]{1,24})[\\] ]").matcher(line);
                    return tag + " " + status + (m.find() ? " [" + m.group(1) + "]" : "");
                }
                return message;
            }
            Matcher m = Pattern.compile("\\b(E[A-Z]{2,15})\\b").matcher(message);
            return error.getClass().getSimpleName() + (m.find() ? " " + m.group(1) : "");
        }

        /** 今の時間 (timing の形)。中継の見張りが失敗を返すときにも使う。 */
        @NonNull
        public synchronized String timing() {
            return timing(connectedAfter, loggedInAfter, selectedAfter, origin < 0 ? -1 : endTime() - origin);
        }

        /** 取得が終わっていれば終わった時刻、まだなら今。lock の中で呼ぶ。 */
        private long endTime() {
            return finishedAt >= 0 ? finishedAt : System.currentTimeMillis();
        }

        /** 取得が終わった (コードを見つけた・失敗した)。最初の 1 回だけ効く。 */
        synchronized void finish() {
            if (finishedAt < 0) finishedAt = System.currentTimeMillis();
        }

        /**
         * 最初の接続を始めてからの秒数。{@code :t<TCP+TLS>-l<LOGIN>-s<SELECT>-f<失敗した時点>} (終わった段階だけ、
         * 引数はミリ秒で、まだなら負)。例: {@code scan-stall:t2.1-l3.4-s9.0-f17.0}。iOS の timingSuffix と同じ。
         * サーバが遅いのが接続・LOGIN・SELECT・FETCH のどこかを、本番の detail で見分けるため。
         */
        @NonNull
        public static String timing(long connected, long loggedIn, long selected, long elapsed) {
            List<String> parts = new ArrayList<>();
            if (connected >= 0) parts.add("t" + seconds(connected));
            if (loggedIn >= 0) parts.add("l" + seconds(loggedIn));
            if (selected >= 0) parts.add("s" + seconds(selected));
            if (elapsed >= 0) parts.add("f" + seconds(elapsed));
            return parts.isEmpty() ? "" : ":" + String.join("-", parts);
        }

        private static String seconds(long ms) {
            return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
        }

        synchronized void reconnected() { reconnects++; }

        /** エラーを受け取った時点の段階を残す。最後に投げるエラーと組にして detail を作る。 */
        synchronized void recordFailure(@NonNull Exception e) {
            failure = e;
            failureStage = stage;
            failureReconnects = reconnects;
            failureConnected = connectedAfter;
            failureLoggedIn = loggedInAfter;
            failureSelected = selectedAfter;
            failureElapsed = origin < 0 ? -1 : System.currentTimeMillis() - origin;
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
            String t;
            synchronized (this) {
                boolean same = failure == error;
                s = same ? failureStage : stage;
                r = same ? failureReconnects : reconnects;
                // 記録したあとも待ち続けてから同じエラーを投げることがある (期限まで新着を待ったなど)。
                // 段階はエラーを受け取った時点のまま、失敗した時点 (f) は取得が終わった時点にする。
                t = !same ? timing() : timing(failureConnected, failureLoggedIn, failureSelected,
                        finishedAt >= 0 && origin >= 0 ? finishedAt - origin : failureElapsed);
            }
            return detail(error, s, r, t);
        }

        /**
         * 中継サーバの detail ({@code ^[a-z0-9][a-z0-9._:-]{0,79}$}) にする。どの段階で何が起きたかと、
         * 繋ぎ直した回数 (":r2" など)。サーバの応答は、角括弧の応答コード ([INUSE] など、英字だけ) と、
         * LOGIN の NO の大まかな理由 (loginRefusalKind) しか使わない。応答の本文はそのまま入れない。
         * 例外の文面はホスト名や IP を含みうるので、errno の名前 (ECONNREFUSED など) だけを取り出す。
         */
        @NonNull
        static String detail(@NonNull Exception error, @NonNull Stage s, int reconnects) {
            return detail(error, s, reconnects, "");
        }

        /** timing は {@link #timing(long, long, long, long)} の形。どこで時間を使ったかを後ろに付ける。 */
        @NonNull
        static String detail(@NonNull Exception error, @NonNull Stage s, int reconnects, @NonNull String timing) {
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
            return sanitize(code + timing);
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

    /** 直近のヘッダを取る FETCH。.PEEK なので既読にしない。iOS の headerFetchCommand と同じ。 */
    @NonNull
    static String headerFetchCommand(@NonNull String tag, int fromUid, @Nullable Integer toUid) {
        String upper = toUid != null ? String.valueOf(toUid) : "*";
        return tag + " UID FETCH " + Math.max(1, fromUid) + ":" + upper
                + " (UID INTERNALDATE BODY.PEEK[HEADER.FIELDS (FROM)])";
    }

    /**
     * 直近のヘッダを見る UID の下限。UIDNEXT が分からなければ (RFC 3501 では必須で、Dovecot は必ず返す) 1 から見る
     * (受信箱全体のヘッダになるが、UIDNEXT を返さないサーバは想定していない)。
     */
    static int scanLowerBound(@Nullable Integer uidNext) {
        if (uidNext == null) return 1;
        return Math.max(1, uidNext - RECENT_UID_WINDOW);
    }

    /** FETCH で受け取った 1 通分。ヘッダの FETCH なら fromHeader、本文の FETCH なら text が入る。 */
    static final class FetchedMail {
        final int uid;
        /** INTERNALDATE (メールサーバに届いた時刻、epoch ミリ秒)。読めなければ null。 */
        @Nullable Long arrivedAt;
        /** {@code BODY[HEADER.FIELDS (FROM)]} の中身。 */
        @Nullable String fromHeader;
        /** {@code BODY[TEXT]} の中身。 */
        @Nullable String text;

        FetchedMail(int uid) {
            this.uid = uid;
        }
    }

    /**
     * FETCH の応答から UID ごとの記録を作る (応答の順)。UID の無い応答 (勝手に届いた FLAGS の変化など) は捨てる。
     * 同じ UID が 2 回来たら 1 つにまとめる。リテラルの中身は lines に入らないので、本文に "UID 5" と書かれていても
     * 取り違えない。
     */
    @NonNull
    static List<FetchedMail> parseFetchedMails(@NonNull List<Response> responses) {
        java.util.LinkedHashMap<Integer, FetchedMail> byUid = new java.util.LinkedHashMap<>();
        Pattern fetch = Pattern.compile("^\\* [0-9]+ FETCH ");
        Pattern uidPattern = Pattern.compile("[( ]UID ([0-9]{1,10})");
        for (Response response : responses) {
            if (response.lines.isEmpty() || !fetch.matcher(response.lines.get(0)).find()) continue;
            String text = String.join(" ", response.lines);
            Matcher m = uidPattern.matcher(text);
            if (!m.find()) continue;
            int uid;
            try {
                uid = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            FetchedMail mail = byUid.get(uid);
            if (mail == null) mail = new FetchedMail(uid);
            Long arrivedAt = parseInternalDate(java.util.Collections.singletonList(text));
            if (arrivedAt != null) mail.arrivedAt = arrivedAt;
            for (int i = 0; i < response.literals.size(); i++) {
                // リテラルの手前の行の、最後の BODY[...] がその中身の名前。
                String line = response.lines.get(i);
                int open = line.lastIndexOf("BODY[");
                if (open < 0) continue;
                String section = line.substring(open + 5).toUpperCase(Locale.ROOT);
                byte[] literal = response.literals.get(i);
                if (section.startsWith("HEADER")) mail.fromHeader = new String(literal, StandardCharsets.UTF_8);
                else if (section.startsWith("TEXT")) mail.text = ImapStream.decode(literal);
            }
            byUid.put(uid, mail);
        }
        return new ArrayList<>(byUid.values());
    }

    /**
     * fromUid 以上 toUid (null なら上限なし) 以下のもの。{@code UID n:*} は n が受信箱の最大の UID より大きいと
     * 「最大の UID のメール」に当たる (RFC 3501 の * は最大値で、n:* は *:n と同じ) ので、範囲より古いメールを手元で落とす。
     */
    @NonNull
    static List<FetchedMail> mailsInRange(@NonNull List<FetchedMail> mails, int fromUid, @Nullable Integer toUid) {
        List<FetchedMail> out = new ArrayList<>();
        for (FetchedMail m : mails) {
            if (m.uid >= fromUid && (toUid == null || m.uid <= toUid)) out.add(m);
        }
        return out;
    }

    /**
     * 直近の範囲の一番古いメールも依頼の時刻以降に届いていて、範囲の手前にも今回のメールがありうるか。
     * 範囲が空 (直近のメールが全部消されている) か、到着時刻が読めないときも、手前を見る。iOS の needsOlderScan と同じ。
     */
    static boolean needsOlderScan(@NonNull List<FetchedMail> window, long requestedAt) {
        FetchedMail oldest = null;
        for (FetchedMail m : window) {
            if (oldest == null || m.uid < oldest.uid) oldest = m;
        }
        if (oldest == null || oldest.arrivedAt == null) return true;
        return oldest.arrivedAt >= requestedAt / 1000L * 1000L;
    }

    /** 次の確認で見る UID の下限。見たものの次か、SELECT の UIDNEXT (これ以降が新着) の大きい方。 */
    static int nextUnseenUid(@NonNull List<FetchedMail> fetched, int fromUid, @Nullable Integer uidNext) {
        int next = fromUid;
        for (FetchedMail m : fetched) {
            if (m.uid >= fromUid) next = Math.max(next, m.uid + 1);
        }
        return uidNext != null ? Math.max(next, uidNext) : next;
    }

    /** From ヘッダのアドレスが OTP の送信元と完全に一致するか。 */
    static boolean isOtpSender(@Nullable String fromHeader) {
        String address = fromHeader != null ? fromAddress(fromHeader) : null;
        return address != null && address.equalsIgnoreCase(OTP_SENDER);
    }

    /**
     * 今回のコードのメール。差出人が送信元そのもので、次のどちらかを満たすもののうち一番新しいもの。無ければ null。
     * iOS の pickOTPMail と同じ。
     * <ul>
     *     <li>受信箱を開いたあとに届いた (UID が最初の SELECT の UIDNEXT 以上)。届いた時刻のずれに関係なく、依頼より後。</li>
     *     <li>開く前に届いていて、届いた時刻 (INTERNALDATE) が依頼の時刻以降。TLS・LOGIN・SELECT を待つ間に届いた
     *     目当てのメールを取りこぼさないため。</li>
     * </ul>
     * INTERNALDATE は秒精度なので、requestedAt も秒に切り下げてから比べる。時計のずれの見込みは呼び出し側が
     * requestedAt に入れてある (PC の拡張は 5 秒 / 再送信は 2 秒前、中継は OtpRelayTiming.effectiveRequestedAt)。
     * ここで更に広げると、送り直す直前に届いた前のメール (使えなくなったコード) を拾う。
     * INTERNALDATE が読めないメールは、開いたあとに届いたものでなければ使わない (古いコードを使うより手入力に落ちる方が安全)。
     * unreadable (本文からコードが読めなかったメール) より古いメールにも戻らない (新しい方が読めないのに古い方のコードを
     * 入れると、使えなくなったコードで IdP の失敗が 1 回増える)。これより新しいものだけを待つ。
     */
    @Nullable
    static FetchedMail pickOtpMail(@NonNull java.util.Collection<FetchedMail> mails, long requestedAt, int arrivedFromUid,
                                   @NonNull java.util.Set<Integer> unreadable) {
        long since = requestedAt / 1000L * 1000L;
        int newerThan = 0;
        for (int u : unreadable) newerThan = Math.max(newerThan, u);
        FetchedMail best = null;
        for (FetchedMail m : mails) {
            if (m.uid <= newerThan || !isOtpSender(m.fromHeader)) continue;
            boolean fresh = m.uid >= arrivedFromUid || (m.arrivedAt != null && m.arrivedAt >= since);
            if (fresh && (best == null || m.uid > best.uid)) best = m;
        }
        return best;
    }

    /** サーバの応答 1 つ (未タグの "* ..." か、タグ付きの完了)。lines は行 (リテラルを除く)、literals[i] は lines[i] の末尾の {n}。 */
    static final class Response {
        @NonNull final List<String> lines = new ArrayList<>();
        @NonNull final List<byte[]> literals = new ArrayList<>();
    }

    /**
     * in から応答を 1 つ (リテラルを含めて) 読む。FETCH の BODY[...] は "{n}\r\n" の直後に n バイトの生データ
     * (内部に \r\n を含みうる) が来て、その後ろに行の続きが来る。iOS の IMAPResponse.take と同じ区切り方。
     */
    @NonNull
    static Response readResponse(@NonNull InputStream in) throws IOException {
        Response response = new Response();
        while (true) {
            String line = readLine(in);
            response.lines.add(line);
            Integer length = literalLength(line);
            if (length == null) return response;
            response.literals.add(readBytes(in, length));
        }
    }

    /** 行の末尾の "{123}" (リテラルの長さ)。無ければ null。 */
    @Nullable
    static Integer literalLength(@NonNull String line) {
        if (!line.endsWith("}")) return null;
        int open = line.lastIndexOf('{');
        if (open < 0) return null;
        try {
            return Integer.parseInt(line.substring(open + 1, line.length() - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @NonNull
    private static String readLine(@NonNull InputStream in) throws IOException {
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

    @NonNull
    private static byte[] readBytes(@NonNull InputStream in, int n) throws IOException {
        byte[] result = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(result, off, n - off);
            if (r < 0) throw new IOException("connectionFailed");
            off += r;
        }
        return result;
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
        /** この接続で受け取ったバイト数 (TLS の中身)。手順の記録で、応答の大きさを出すのに使う。 */
        volatile long receivedBytes;

        /** 手順の記録 (progress が無ければ何もしない)。 */
        void note(@NonNull String message) {
            if (progress != null) progress.note(message);
        }

        /** 手順の記録に添える、期限までの残り。 */
        @NonNull
        String remaining() {
            long d = deadline;
            return d > 0 ? "、残り " + seconds(d - System.currentTimeMillis()) + "s" : "";
        }

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
                // InetSocketAddress(host, port) はここで名前解決し、DNS が答えないと待ち時間の上限 (currentReadWait)
                // と関係なく止まっていた。解決にも同じ上限を掛ける。解決した InetAddress はホスト名を持つので、
                // SNI とホスト名の検証は今までどおり host で行われる。
                java.net.InetAddress address = com.shakenokirimi12.uoa_app.services.TimedDns.resolve(host, currentReadWait())[0];
                note("名前を引いた");
                s.connect(new InetSocketAddress(address, port), currentReadWait());
                note("TCP で繋がった");
                s.setSoTimeout(currentReadWait());
                // SSLSocket は既定ではホスト名検証をしない。iOS の NWConnection(TLS) と同じ強度にするため明示する。
                SSLParameters params = s.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                s.setSSLParameters(params);
                s.startHandshake();
                note("TLS の握手が済んだ");
                in = new CountingInputStream(new BufferedInputStream(s.getInputStream()), this);
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

        /** tag の完了 ("a5 OK ...") までの応答 (リテラルを含む) を集めて返す。"a5 NO" / "a5 BAD" ならエラー。 */
        List<Response> readResponsesUntilTagged(String tag) throws IOException, ImapException {
            List<Response> responses = new ArrayList<>();
            while (true) {
                socket.setSoTimeout(currentReadWait());
                Response response = ImapOtpFetcher.readResponse(in);
                String line = response.lines.get(0);
                if (line.startsWith(tag + " OK")) return responses;
                if (line.startsWith(tag + " NO") || line.startsWith(tag + " BAD")) {
                    throw new ImapException("commandFailed(" + tag + "): " + line.substring(0, Math.min(line.length(), 40)),
                            line);
                }
                responses.add(response);
            }
        }

        private String readLine() throws IOException {
            // 1 行ごとに待ちを決め直す (期限に近づくほど短くなる)。
            socket.setSoTimeout(currentReadWait());
            return ImapOtpFetcher.readLine(in);
        }

        static String decode(byte[] raw) {
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
                    note("LOGOUT を送って切る");
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
            note("取りやめた (LOGOUT を送って切る)");
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

    /** 受け取ったバイト数を ImapStream.receivedBytes に足す。 */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private final ImapStream owner;

        CountingInputStream(InputStream in, ImapStream owner) {
            super(in);
            this.owner = owner;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) owner.receivedBytes++;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) owner.receivedBytes += n;
            return n;
        }
    }
}
