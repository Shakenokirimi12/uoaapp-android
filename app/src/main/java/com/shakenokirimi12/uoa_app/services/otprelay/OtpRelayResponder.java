package com.shakenokirimi12.uoa_app.services.otprelay;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 依頼に応える本体。iOS の OTPRelayShared.fetchAndRespond / respondExpired と同じ手順:
 * 大学のメール (IMAP) から OTP を読み、封緘して中継サーバへ返す。依頼ごとの承認は無い
 * (2026-10-21 に廃止。iOS と同じく、メールを読む同意のある連携は常に自動で返す)。
 *
 * IdP にはログインしない。使うのは保存済みの ID / パスワードでの IMAP だけ
 * (バックグラウンドから新規ログインしない決まり、AuthorizationContext.BACKGROUND と同じ考え)。
 */
public final class OtpRelayResponder {
    private static final String TAG = "OtpRelay";

    /** 同意を記録する前に作った連携。メールは読まずに rejected で断った。 */
    public static final String CONSENT_REQUIRED = "consent-required";
    /**
     * 時間切れの見張り (respondExpired) が先に答えたので、fetchAndRespond は何も返さなかった。
     * 通知も見張りが出すので、呼び出し側は通知を出さない。
     */
    public static final String DEADLINE_PASSED = "deadline-passed";

    public enum Kind { SENT, FAILED }

    public static final class Outcome {
        @NonNull public final Kind kind;
        /**
         * FAILED のときの理由 (no-credentials / login-failed / code-not-found / imap-error / mail-timeout /
         * expired / bad-key / relay-unreachable / consent-required / deadline-passed)。
         */
        @Nullable public final String reason;

        Outcome(@NonNull Kind kind, @Nullable String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        static Outcome sent() { return new Outcome(Kind.SENT, null); }
        static Outcome failed(String reason) { return new Outcome(Kind.FAILED, reason); }
    }

    /**
     * 1 件の依頼への応答の進み具合。時間切れの見張り (別のスレッド) と取得の処理のうち、中継サーバへ
     * 答えるのは先に claimResponse した方だけ (同じ依頼に二重に答えない)。
     */
    public static final class Attempt {
        @NonNull public final ImapOtpFetcher.Progress imap = new ImapOtpFetcher.Progress();
        private final AtomicBoolean responded = new AtomicBoolean();

        /** これから中継サーバに答える。既に誰かが答え始めていたら false。 */
        boolean claimResponse() { return responded.compareAndSet(false, true); }

        /** 中継サーバに答え始めたか (届いたかは問わない)。 */
        public boolean responded() { return responded.get(); }
    }

    /** IMAP の差し替え口 (テスト用)。 */
    public interface MailSource {
        @NonNull ImapOtpFetcher.OtpMail fetch(@NonNull String uid, @NonNull String pass, long timeoutSeconds, long since,
                                              @NonNull ImapOtpFetcher.Progress progress) throws Exception;
        void deleteUsed(@NonNull String uid, @NonNull String pass, int upToUid) throws Exception;
    }

    public interface Credentials {
        @NonNull String username();
        @NonNull String password();
    }

    public interface LastResultSink {
        void save(boolean succeeded, @NonNull String summary, @Nullable String detail);
    }

    public interface Clock { long now(); }

    private final OtpRelayApi api;
    private final MailSource mail;
    private final Credentials credentials;
    private final LastResultSink lastResult;
    private final Clock clock;

    public OtpRelayResponder(@NonNull OtpRelayApi api, @NonNull MailSource mail, @NonNull Credentials credentials,
                             @NonNull LastResultSink lastResult, @NonNull Clock clock) {
        this.api = api;
        this.mail = mail;
        this.credentials = credentials;
        this.lastResult = lastResult;
        this.clock = clock;
    }

    /**
     * メールを読んで返す。失敗したら、理由 (error) と切り分け用の detail を中継サーバに送る
     * (PC はそこで待つのをやめて手入力に戻る)。メールが時間内に来なかったときも同じ。
     *
     * メールを読む同意の無い連携 (同意を記録する前に作ったもの) なら、メールを読まずに依頼を断る
     * (中継サーバの失敗理由に「同意が無い」は無いので rejected で返す)。呼び出し側の判定に頼らず、
     * メールを読む手前のここで止める。
     *
     * @param deliveredAt 依頼が届いた時刻 (この端末の時計)。依頼時刻を信じるかの判定に使う。
     * @param mailWaitMs  メールを待つ上限。
     */
    @NonNull
    public Outcome fetchAndRespond(@NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request,
                                   long deliveredAt, long mailWaitMs, @NonNull Attempt attempt) {
        long startedAt = clock.now();
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(pairing.key);
        // 鍵が読めないと応答のトークンも作れない (中継サーバには答えられない)。
        if (keys == null) return Outcome.failed("bad-key");

        if (!pairing.hasMailConsent()) {
            if (!attempt.claimResponse()) return Outcome.failed(DEADLINE_PASSED);
            JsonObject body = new JsonObject();
            body.addProperty("pairingId", pairing.pairingId);
            body.addProperty("result", "rejected");
            post(request, keys, body, OtpRelayApi.DEFAULT_TIMEOUT_MS);
            lastResult.save(false, failureText(CONSENT_REQUIRED), null);
            return Outcome.failed(CONSENT_REQUIRED);
        }

        String uid = credentials.username().trim();
        String pass = credentials.password().trim();
        if (uid.isEmpty() || pass.isEmpty()) {
            return fail(pairing, request, keys, attempt, "no-credentials", null, null);
        }

        long since = OtpRelayTiming.effectiveRequestedAt(request.requestedAt, deliveredAt);
        long timeout = OtpRelayTiming.mailTimeoutSeconds(clock.now(), startedAt + mailWaitMs, request.expiresAt);
        ImapOtpFetcher.OtpMail found;
        try {
            found = mail.fetch(uid, pass, timeout, since, attempt.imap);
        } catch (Exception e) {
            String reason = failureCode(e);
            String detail = attempt.imap.detail(e);
            // 画面に出す切り分け用の記録には例外の中身も残す (端末の中だけ。LOGIN の引数は例外に入らない)。
            return fail(pairing, request, keys, attempt, reason, detail, detail + "\n" + e);
        }

        String box;
        try {
            box = OtpRelayCrypto.seal(found.code, request.requestId, keys);
        } catch (java.security.GeneralSecurityException e) {
            Log.e(TAG, "sealing failed", e);
            return fail(pairing, request, keys, attempt, "imap-error", "seal-error", null);
        }
        // 見張りが先に失敗を返していたら、PC は手入力に戻っている。送らず、メールも消さない
        // (利用者が手入力に使う)。通知は見張りが出す。
        if (!attempt.claimResponse()) return Outcome.failed(DEADLINE_PASSED);
        JsonObject body = new JsonObject();
        body.addProperty("pairingId", pairing.pairingId);
        body.addProperty("result", "approved");
        body.addProperty("box", box);
        int status = post(request, keys, body, OtpRelayTiming.POST_TIMEOUT_MS);

        lastResult.save(status == 204, status == 204 ? "PC に OTP を送りました" : "中継サーバの応答: " + status, null);
        if (status == 204) {
            // 中継サーバが受け付けたので、使ったメールを消す。409 / 410 / 通信失敗は PC に届いていない
            // (応答するのはこの端末だけなので、409 は「既に送った」ではなく、新しい依頼に置き換わった等)。
            // そのときは消さずに残す (PC で手入力に使う。残ったものは次に成功したときに消える)。
            try {
                mail.deleteUsed(uid, pass, found.uid);
            } catch (Exception e) {
                Log.w(TAG, "could not delete used OTP mails: " + e);
            }
            return Outcome.sent();
        }
        // 期限切れか、PC が新しい依頼を出して古い依頼が終わった。
        if (status == 409 || status == 410) return Outcome.failed("expired");
        // 中継サーバに届かなかった。PC は期限まで待ってから手入力に戻る。
        return Outcome.failed("relay-unreachable");
    }

    /**
     * 時間切れの見張り。まだ答え始めていなければ失敗を返す (best effort)。返さないと、依頼が期限
     * (120 秒) まで pending のまま残り、PC はその間待たされる。メールを待つ段階 (search / fetch / idle)
     * まで進んでいたら mail-timeout、その手前なら imap-error。detail は "expired:<段階>[:r<回数>]"。
     *
     * @return NOT_CLAIMED = 取得の処理が先に答え始めていた (通知はそちらが出す)。ACCEPTED = 中継サーバが
     *         204 で受け付けた。UNDELIVERED = 送ったが受け付けられたか分からない。
     */
    @NonNull
    public ExpiryResult respondExpired(@NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request,
                                       @NonNull Attempt attempt, long timeoutMs) {
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(pairing.key);
        if (!pairing.hasMailConsent() || keys == null || !attempt.claimResponse()) return ExpiryResult.NOT_CLAIMED;
        ImapOtpFetcher.Progress.Stage stage = attempt.imap.stage();
        String reason = expiredReason(stage);
        String detail = expiredDetail(stage, attempt.imap.reconnects());
        JsonObject body = failureBody(pairing, reason, detail);
        int status = post(request, keys, body, timeoutMs);
        lastResult.save(false, failureText(reason), detail);
        return status == 204 ? ExpiryResult.ACCEPTED : ExpiryResult.UNDELIVERED;
    }

    public enum ExpiryResult { NOT_CLAIMED, ACCEPTED, UNDELIVERED }

    @NonNull
    static String expiredReason(@NonNull ImapOtpFetcher.Progress.Stage stage) {
        switch (stage) {
            case SEARCH:
            case FETCH:
            case IDLE:
                return "mail-timeout";
            default:
                return "imap-error";
        }
    }

    @NonNull
    static String expiredDetail(@NonNull ImapOtpFetcher.Progress.Stage stage, int reconnects) {
        return "expired:" + stage.code() + (reconnects > 0 ? ":r" + reconnects : "");
    }

    /** 失敗を中継サーバに返す。見張りが先に答えていたら何もしない (DEADLINE_PASSED)。 */
    @NonNull
    private Outcome fail(OtpRelayPairing pairing, OtpRelayRequest request, OtpRelayCrypto.Keys keys, Attempt attempt,
                         String reason, @Nullable String detail, @Nullable String localDetail) {
        if (!attempt.claimResponse()) return Outcome.failed(DEADLINE_PASSED);
        post(request, keys, failureBody(pairing, reason, detail), OtpRelayApi.DEFAULT_TIMEOUT_MS);
        lastResult.save(false, failureText(reason), localDetail != null ? localDetail : detail);
        return Outcome.failed(reason);
    }

    @NonNull
    private static JsonObject failureBody(OtpRelayPairing pairing, String reason, @Nullable String detail) {
        JsonObject body = new JsonObject();
        body.addProperty("pairingId", pairing.pairingId);
        body.addProperty("result", "failed");
        body.addProperty("error", reason);
        if (detail != null) body.addProperty("detail", detail);
        return body;
    }

    /** 中継サーバへ送る。通信できなければ 0。 */
    private int post(OtpRelayRequest request, OtpRelayCrypto.Keys keys, JsonObject body, long timeoutMs) {
        try {
            return api.respond(request.requestId, keys, body, timeoutMs).status;
        } catch (IOException e) {
            Log.w(TAG, "response not delivered: " + e);
            return 0;
        }
    }

    /**
     * ImapOtpFetcher の例外を、中継サーバが受け付ける 5 種類の理由に寄せる (iOS の failureCode と同じ)。
     * LOGIN の NO のうち ID / パスワードの誤りだけを login-failed にする。同時接続数の超過などの
     * 一時的な NO ([UNAVAILABLE] など) は imap-error (以前は a1 の NO をすべて login-failed にしていて、
     * パスワードが正しいのに「ID かパスワードが違う」と通知していた)。
     */
    @NonNull
    static String failureCode(@NonNull Exception e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof ImapOtpFetcher.ImapException) {
            if (m.equals("mailNotFound")) return "mail-timeout";
            if (m.equals("codeNotFound")) return "code-not-found";
            // a1 は LOGIN (接続して最初に送るコマンド)。
            if (m.startsWith("commandFailed(a1)") && ImapOtpFetcher.isAuthenticationFailure(m)) return "login-failed";
        }
        return "imap-error";
    }

    /** 通知と設定画面に出す失敗の説明。 */
    @NonNull
    public static String failureText(@Nullable String reason) {
        if (reason == null) return "大学のメールサーバに接続できませんでした。通信状況を確かめて、PC でもう一度ログインしてください。";
        switch (reason) {
            case CONSENT_REQUIRED: return "この PC との連携の確認が済んでいません。アプリを開いて確認してください。";
            case "no-credentials": return "アプリに AINS の ID / パスワードが入っていません。設定で入力してください。";
            case "login-failed": return "AINS の ID かパスワードが違うため、大学のメールを読めませんでした。設定で入力し直してください。";
            case "code-not-found": return "OTP メールからコードを読み取れませんでした。";
            case "mail-timeout": return "OTP メールが時間内に届きませんでした。PC でもう一度ログインしてください。";
            case "expired": return "期限が切れました。PC でもう一度ログインしてください。";
            case "relay-unreachable": return "中継サーバに OTP を送れませんでした。時間をおいて、PC でもう一度ログインしてください。";
            case "bad-key": return "連携の情報が壊れています。設定で連携を解除して、連携し直してください。";
            default: return "大学のメールサーバに接続できませんでした。通信状況を確かめて、PC でもう一度ログインしてください。";
        }
    }
}
