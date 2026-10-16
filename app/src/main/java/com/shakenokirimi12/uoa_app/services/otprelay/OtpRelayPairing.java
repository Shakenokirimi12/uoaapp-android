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

    public boolean autoApprove() { return autoApprove == null || autoApprove; }

    public OtpRelayPairing() {}

    OtpRelayPairing(String pairingId, String key, String browserName, long pairedAt) {
        this.pairingId = pairingId;
        this.key = key;
        this.browserName = browserName;
        this.pairedAt = pairedAt;
        this.autoApprove = true;
    }
}
