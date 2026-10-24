package com.shakenokirimi12.uoa_app.services.otprelay;

import androidx.annotation.Nullable;

/**
 * 1 つのブラウザとの連携。iOS の OTPRelayPairing と同じ項目 (Gson で保存する)。
 *
 * 以前の版が保存した autoApprove (連携ごとの自動 / 毎回承認) は、このクラスに項目が無いので読まずに捨てる
 * (次に保存するときに消える)。依頼ごとの承認は 2026-10-21 に廃止した。
 */
public final class OtpRelayPairing {
    public String pairingId;
    /** QR の K (base64url)。トークンと暗号鍵はここから毎回導出する。 */
    public String key;
    public String browserName;
    /** 連携した時刻 (epoch ミリ秒)。 */
    public long pairedAt;
    /**
     * この連携のためにメールを読むことへの同意を得た時刻 (epoch ミリ秒)。連携の確認ダイアログが
     * 「大学のメールを読んで OTP をこの PC に返す」と明示しているので、そこで「同意して連携する」を押したことが
     * 同意になる。同意を記録する前に作った連携は、アプリでの確認 (OtpRelayConsentPrompt) の「続ける」で記録する。
     * 同意のある連携は、依頼が届くとメールを読んで自動で返す。同意の無い連携はメールを読まずに依頼を断る。
     */
    @Nullable public Long mailConsentAt;

    public boolean hasMailConsent() { return mailConsentAt != null && mailConsentAt > 0; }

    /**
     * この PC に、認証アプリ方式のコード (セットアップキーで作ったもの) を送ることへの同意を得た時刻。
     * 2026-10-24 からの確認の文面 (メールも認証アプリのコードも送ると書いたもの) で同意したときか、認証アプリの
     * コードの確認 (OtpRelayConsentPrompt) で「送る」を押したときに記録する。それより前の文面 (メールを読むことだけ)
     * で作った連携には無く、コードを作らずに断る (メールの同意を流用すると、偽の QR で連携させられていた PC に、
     * 確かめないまま有効なコードを渡してしまう)。
     */
    @Nullable public Long totpConsentAt;

    public boolean hasTotpConsent() { return totpConsentAt != null && totpConsentAt > 0; }

    public OtpRelayPairing() {}

    /** 確認ダイアログで同意を得て作る連携。作り方 (リンク・カメラ・貼り付け) にかかわらず同じ。 */
    OtpRelayPairing(String pairingId, String key, String browserName, long pairedAt) {
        this.pairingId = pairingId;
        this.key = key;
        this.browserName = browserName;
        this.pairedAt = pairedAt;
        this.mailConsentAt = pairedAt;
        this.totpConsentAt = pairedAt;
    }
}
