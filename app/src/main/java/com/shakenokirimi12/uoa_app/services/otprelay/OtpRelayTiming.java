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
    /** メールを待つ上限 (承認後・自動とも)。 */
    public static final long MAIL_WAIT_LIMIT_MS = 18_000;
    /** 応答の POST のタイムアウト。 */
    public static final long POST_TIMEOUT_MS = 4_000;

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
     * IMAP を待つ秒数。メールの待ち上限と、依頼の期限の 3 秒前の早い方。ただし 5 秒は待つ。
     */
    public static long mailTimeoutSeconds(long now, long mailDeadline, long expiresAt) {
        long ms = Math.min(mailDeadline - now, expiresAt - now - 3_000);
        return Math.max(5, ms / 1000);
    }
}
