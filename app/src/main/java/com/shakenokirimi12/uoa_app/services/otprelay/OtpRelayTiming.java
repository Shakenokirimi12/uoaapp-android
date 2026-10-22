package com.shakenokirimi12.uoa_app.services.otprelay;

/**
 * 依頼時刻の扱い。iOS の OTPRelayShared (maxClockSkew 以下の定数と effectiveRequestedAt) と同じ。
 */
public final class OtpRelayTiming {
    private OtpRelayTiming() {}

    /** PC と端末の時計のずれとして許す幅。 */
    static final long MAX_CLOCK_SKEW_MS = 60_000;
    /** 拡張は「既に送ってあった OTP メール」を拾わせるため、10 分前を依頼時刻にしてくることがある。 */
    static final long ALREADY_SENT_LOOKBACK_MS = 10 * 60_000;
    /** 信用できない時刻のときは、通知が届いた時刻のこれだけ前からのメールを探す。 */
    static final long DELIVERY_LEAD_MS = 20_000;
    /** メールを待つ上限。 */
    public static final long MAIL_WAIT_LIMIT_MS = 18_000;
    /** 応答の POST のタイムアウト。 */
    public static final long POST_TIMEOUT_MS = 4_000;
    /**
     * 処理を始めてからこの時間までに中継サーバへ答え始めていなければ、見張りが自分で失敗を返す
     * (iOS の NotificationService.respondDeadline と同じ 24 秒)。ふつうの流れはメールの待ち (18 秒) から
     * 返送 (4 秒) までで答え始めるので、それより後に置く。IMAP の読み取りは期限を過ぎても 1 行ごとに 0.5 秒は
     * 待ち、LOGOUT にも最大 0.5 秒かかるので、メールの待ちの上限だけでは答え始める時刻が決まらない。
     */
    static final long RESPOND_DEADLINE_MS = 24_000;
    /** 見張りが失敗を送るときの上限。 */
    public static final long EXPIRY_POST_TIMEOUT_MS = 4_000;
    /** 依頼の期限のこれだけ前には見張りが答える (期限を過ぎると 410 で受け付けられない)。 */
    static final long EXPIRY_MARGIN_MS = 5_000;

    /**
     * IMAP で「この時刻以降に届いた OTP メール」を探すときの起点。
     * 依頼時刻 (PC の時計) が届いた時刻の ±60 秒、または「10 分前 ±60 秒」に入っていればそのまま使う。
     * それ以外 (1〜9 分前など) は PC の時計を信じず、届いた時刻の 20 秒前にする。
     */
    public static long effectiveRequestedAt(long requestedAt, long deliveredAt) {
        long offset = requestedAt - deliveredAt;
        boolean usual = Math.abs(offset) <= MAX_CLOCK_SKEW_MS;
        boolean alreadySent = Math.abs(offset + ALREADY_SENT_LOOKBACK_MS) <= MAX_CLOCK_SKEW_MS;
        return usual || alreadySent ? requestedAt : deliveredAt - DELIVERY_LEAD_MS;
    }

    /**
     * 見張りが失敗を返す時刻。処理を始めてから RESPOND_DEADLINE_MS 後か、依頼の期限の EXPIRY_MARGIN_MS 前の
     * 早い方。配信が遅れて期限間際に始まったときは、すぐに (メールを読み終わるのを待たずに) 返す。
     */
    public static long respondDeadline(long startedAt, long expiresAt) {
        return Math.min(startedAt + RESPOND_DEADLINE_MS, expiresAt - EXPIRY_MARGIN_MS);
    }

    /**
     * IMAP を待つ秒数。メールの待ち上限と、依頼の期限の 3 秒前の早い方。ただし 5 秒は待つ。
     */
    public static long mailTimeoutSeconds(long now, long mailDeadline, long expiresAt) {
        long ms = Math.min(mailDeadline - now, expiresAt - now - 3_000);
        return Math.max(5, ms / 1000);
    }
}
