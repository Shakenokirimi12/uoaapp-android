package com.shakenokirimi12.uoa_app.services.otprelay;

import androidx.annotation.Nullable;

/** 1 つのブラウザとの連携。iOS の OTPRelayPairing と同じ項目 (Gson で保存する)。 */
public final class OtpRelayPairing {
    public String pairingId;
    /** QR の K (base64url)。トークンと暗号鍵はここから毎回導出する。 */
    public String key;
    public String browserName;
    /** 連携した時刻 (epoch ミリ秒)。 */
    public long pairedAt;
    /** 承認なしで自動で返すか。古い保存データに無いときは true (iOS と同じ既定)。 */
    @Nullable public Boolean autoApprove;
    /**
     * この連携のためにメールを読むことへの同意を得た時刻 (epoch ミリ秒)。連携の確認ダイアログが
     * 「大学のメールを読んで OTP をこの PC に返す」と明示しているので、そこで「連携する」を押したことが同意になる。
     * 同意の無い連携 (この項目ができる前の連携) は、承認されたときにだけメールを読む。
     */
    @Nullable public Long mailConsentAt;

    public boolean autoApprove() { return autoApprove == null || autoApprove; }

    public boolean hasMailConsent() { return mailConsentAt != null && mailConsentAt > 0; }

    /**
     * 依頼が来たとき、承認を待たずにメールを読んで返してよいか。この連携への同意と自動の設定の両方が要る。
     * アプリ自身のログインの同意 (otp_auto_fetch_enabled) はここでは見ない (中継の同意は連携ごと)。
     */
    public boolean readsMailWithoutApproval() { return autoApprove() && hasMailConsent(); }

    public OtpRelayPairing() {}

    /** 確認ダイアログで同意を得て作る連携。 */
    OtpRelayPairing(String pairingId, String key, String browserName, long pairedAt) {
        this.pairingId = pairingId;
        this.key = key;
        this.browserName = browserName;
        this.pairedAt = pairedAt;
        this.autoApprove = true;
        this.mailConsentAt = pairedAt;
    }
}
