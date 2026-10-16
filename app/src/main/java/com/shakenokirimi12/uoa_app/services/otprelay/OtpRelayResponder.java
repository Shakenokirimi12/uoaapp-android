package com.shakenokirimi12.uoa_app.services.otprelay;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;

import java.io.IOException;

/**
 * 依頼に応える本体。iOS の OTPRelayShared.fetchAndRespond / OTPRelayService.approve と同じ手順:
 * 大学のメール (IMAP) から OTP を読み、封緘して中継サーバへ返す。
 *
 * IdP にはログインしない。使うのは保存済みの ID / パスワードでの IMAP だけ
 * (バックグラウンドから新規ログインしない決まり、AuthorizationContext.BACKGROUND と同じ考え)。
 */
public final class OtpRelayResponder {
    private static final String TAG = "OtpRelay";

    public enum Kind { SENT, ALREADY_ANSWERED, NEEDS_APPROVAL, FAILED }

    public static final class Outcome {
        @NonNull public final Kind kind;
        /**
         * FAILED のときの理由 (no-credentials / login-failed / code-not-found / imap-error / mail-timeout /
         * expired / bad-key / relay-unreachable)。NEEDS_APPROVAL のときは承認に回した理由
         * (mail-timeout / relay-unreachable)。
         */
        @Nullable public final String reason;

        Outcome(@NonNull Kind kind, @Nullable String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        static Outcome of(Kind kind) { return new Outcome(kind, null); }
        static Outcome failed(String reason) { return new Outcome(Kind.FAILED, reason); }
    }

    /** IMAP の差し替え口 (テスト用)。 */
    public interface MailSource {
        @NonNull ImapOtpFetcher.OtpMail fetch(@NonNull String uid, @NonNull String pass, long timeoutSeconds, long since)
                throws Exception;
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
     * メールを読んで返す。メールが時間内に来なかった (mail-timeout) か、返す POST が通らなかったときは
     * NEEDS_APPROVAL (待てば直りうるので、承認の通知に回す)。他の失敗は理由を中継サーバへ送って FAILED。
     *
     * @param deliveredAt 依頼が届いた時刻 (この端末の時計)。依頼時刻を信じるかの判定に使う。
     * @param mailWaitMs  メールを待つ上限。
     */
    @NonNull
    public Outcome fetchAndRespond(@NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request,
                                   long deliveredAt, long mailWaitMs) {
        long startedAt = clock.now();
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(pairing.key);
        if (keys == null) return Outcome.failed("bad-key");

        String uid = credentials.username().trim();
        String pass = credentials.password().trim();
        if (uid.isEmpty() || pass.isEmpty()) {
            postFailure(pairing, request, keys, "no-credentials");
            return Outcome.failed("no-credentials");
        }

        long since = OtpRelayTiming.effectiveRequestedAt(request.requestedAt, deliveredAt);
        long timeout = OtpRelayTiming.mailTimeoutSeconds(clock.now(), startedAt + mailWaitMs, request.expiresAt);
        ImapOtpFetcher.OtpMail found;
        try {
            found = mail.fetch(uid, pass, timeout, since);
        } catch (Exception e) {
            String reason = failureCode(e);
            if ("mail-timeout".equals(reason)) return new Outcome(Kind.NEEDS_APPROVAL, "mail-timeout");
            postFailure(pairing, request, keys, reason);
            lastResult.save(false, failureText(reason), String.valueOf(e));
            return Outcome.failed(reason);
        }

        int status;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("pairingId", pairing.pairingId);
            body.addProperty("result", "approved");
            body.addProperty("box", OtpRelayCrypto.seal(found.code, request.requestId, keys));
            status = api.respond(request.requestId, keys, body, OtpRelayTiming.POST_TIMEOUT_MS).status;
        } catch (IOException e) {
            status = 0;
        } catch (java.security.GeneralSecurityException e) {
            Log.e(TAG, "sealing failed", e);
            return Outcome.failed("bad-key");
        }

        boolean accepted = status == 204 || status == 409 || status == 410;
        lastResult.save(accepted, status == 204 ? "PC にコードを送りました" : "中継サーバの応答: " + status, null);
        if (accepted) {
            // 中継サーバが受け取った (か、もう答えが決まっている) ので、使ったメールを消す。
            // 送れなかったときは消さない (手で使えるように残す)。
            try {
                mail.deleteUsed(uid, pass, found.uid);
            } catch (Exception e) {
                Log.w(TAG, "could not delete used OTP mails: " + e);
            }
        }
        if (status == 204) return Outcome.of(Kind.SENT);
        // 410 = 期限切れ。PC にコードは渡っていないので「送りました」とは言わない。
        if (status == 410) return Outcome.failed("expired");
        if (accepted) return Outcome.of(Kind.ALREADY_ANSWERED);
        return new Outcome(Kind.NEEDS_APPROVAL, "relay-unreachable");
    }

    /**
     * 通知の「承認して送る」から。requestedAt は受信時に補正済みの値を渡す (二重に補正しない)。
     * 承認したのにメールが来なければ、mail-timeout として中継サーバに知らせて終える。
     * メールは取れたのに中継サーバへ返せなかったときは、mail-timeout とは言わない (iOS はここを
     * 区別していないが、届いたメールを「届かなかった」と伝えることになるため分ける)。
     */
    @NonNull
    public Outcome approve(@NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request) {
        if (request.expiresAt <= clock.now()) return Outcome.failed("expired");
        Outcome outcome = fetchAndRespond(pairing, request, request.requestedAt, OtpRelayTiming.MAIL_WAIT_LIMIT_MS);
        if (outcome.kind != Kind.NEEDS_APPROVAL) return outcome;
        if ("relay-unreachable".equals(outcome.reason)) return Outcome.failed("relay-unreachable");
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(pairing.key);
        if (keys != null) postFailure(pairing, request, keys, "mail-timeout");
        return Outcome.failed("mail-timeout");
    }

    /** 通知の「拒否」から。 */
    public void reject(@NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request) {
        OtpRelayCrypto.Keys keys = OtpRelayCrypto.keys(pairing.key);
        if (keys == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("pairingId", pairing.pairingId);
        body.addProperty("result", "rejected");
        try {
            api.respond(request.requestId, keys, body, OtpRelayApi.DEFAULT_TIMEOUT_MS);
        } catch (IOException e) {
            Log.w(TAG, "reject not delivered: " + e);
        }
    }

    private void postFailure(OtpRelayPairing pairing, OtpRelayRequest request, OtpRelayCrypto.Keys keys, String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("pairingId", pairing.pairingId);
        body.addProperty("result", "failed");
        body.addProperty("error", reason);
        try {
            api.respond(request.requestId, keys, body, OtpRelayApi.DEFAULT_TIMEOUT_MS);
        } catch (IOException e) {
            Log.w(TAG, "failure not delivered: " + e);
        }
    }

    /** ImapOtpFetcher の例外を、中継サーバが受け付ける 5 種類の理由に寄せる (iOS の failureCode と同じ)。 */
    @NonNull
    static String failureCode(@NonNull Exception e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof ImapOtpFetcher.ImapException) {
            if (m.equals("mailNotFound")) return "mail-timeout";
            if (m.equals("codeNotFound")) return "code-not-found";
            // a1 は LOGIN (接続して最初に送るコマンド)。
            if (m.startsWith("commandFailed(a1)")) return "login-failed";
        }
        return "imap-error";
    }

    /** 通知に出す失敗の説明 (iOS の failureText と同じ文面)。 */
    @NonNull
    public static String failureText(@Nullable String reason) {
        if (reason == null) return "大学のメールサーバとの通信に失敗しました。";
        switch (reason) {
            case "no-credentials": return "アプリに AINS の ID / パスワードが入っていません。";
            case "login-failed": return "AINS の ID かパスワードが違うため、大学のメールを読めませんでした。";
            case "code-not-found": return "OTP メールからコードを読み取れませんでした。";
            case "mail-timeout": return "OTP メールが時間内に届きませんでした。";
            case "expired": return "期限が切れました。PC でもう一度ログインしてください。";
            case "relay-unreachable": return "コードは読めましたが、中継サーバに返せませんでした。PC でもう一度ログインしてください。";
            default: return "大学のメールサーバとの通信に失敗しました。";
        }
    }
}
