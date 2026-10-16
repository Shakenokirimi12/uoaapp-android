package com.shakenokirimi12.uoa_app.services.otprelay;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * PC からの依頼 1 件。FCM の data メッセージの "otpRelay" (JSON 文字列) から作る。
 * 時刻はどちらも epoch ミリ秒 (PC の時計)。
 */
public final class OtpRelayRequest {
    @NonNull public final String pairingId;
    @NonNull public final String requestId;
    public final long requestedAt;
    public final long expiresAt;

    public OtpRelayRequest(@NonNull String pairingId, @NonNull String requestId, long requestedAt, long expiresAt) {
        this.pairingId = pairingId;
        this.requestId = requestId;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
    }

    /** {"v":1,"pairingId":...,"requestId":...,"requestedAt":ms,"expiresAt":ms}。読めなければ null。 */
    @Nullable
    public static OtpRelayRequest parse(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            // v は iOS も見ていない。将来の版で意味が変わったときに古いアプリが誤動作しないよう、1 以外は断る。
            if (o.has("v") && o.get("v").getAsInt() != 1) return null;
            String pairingId = o.get("pairingId").getAsString();
            String requestId = o.get("requestId").getAsString();
            long requestedAt = o.get("requestedAt").getAsLong();
            long expiresAt = o.get("expiresAt").getAsLong();
            if (pairingId.isEmpty() || requestId.isEmpty()) return null;
            // URL のパスに入るので、base64url の文字だけを許す。
            if (!requestId.matches("[A-Za-z0-9_-]+") || !pairingId.matches("[A-Za-z0-9_-]+")) return null;
            return new OtpRelayRequest(pairingId, requestId, requestedAt, expiresAt);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
