package com.shakenokirimi12.uoa_app.services.otprelay;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shakenokirimi12.uoa_app.services.NetworkClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 中継サーバ (workers/otp-relay、docs/otp-relay.md) の呼び出し。認証は Authorization: Bearer <token>。
 * 応答は HTTP ステータスで返す (通信できなければ IOException)。
 */
public final class OtpRelayApi {
    /** ゲートウェイ (workers/uoa-gateway) 経由。iOS の OTPRelayShared.baseURL と同じ。 */
    public static final String BASE_URL = "https://uoa-app.shakenokiri.me/relay";
    /** 登録・解除・拒否など、急がない呼び出し (iOS の send() と同じ 8 秒)。 */
    static final long DEFAULT_TIMEOUT_MS = 8_000;
    private static final MediaType JSON = MediaType.parse("application/json");

    public static final class Reply {
        public final int status;
        /** 応答 JSON の "error"。無ければ null。 */
        @Nullable public final String error;

        Reply(int status, @Nullable String error) {
            this.status = status;
            this.error = error;
        }
    }

    private final String baseUrl;
    private final OkHttpClient client;

    public OtpRelayApi() { this(BASE_URL, NetworkClient.getNoCookieClient()); }

    OtpRelayApi(@NonNull String baseUrl, @NonNull OkHttpClient client) {
        this.baseUrl = baseUrl;
        this.client = client;
    }

    /**
     * POST /v1/pairings。FCM の data メッセージで依頼を受ける (platform "fcm")。
     * supportId は問い合わせ ID (SupportId)。管理画面で連携を引くためだけのもので、鍵ではない。null なら送らない。
     */
    @NonNull
    public Reply register(@NonNull String pairingId, @NonNull OtpRelayCrypto.Keys keys, @NonNull String fcmToken,
                          @NonNull String deviceName, @NonNull String browserLabel, boolean updateOnly,
                          @androidx.annotation.Nullable String supportId)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("pairingId", pairingId);
        body.addProperty("requestToken", keys.requestToken);
        body.addProperty("respondToken", keys.respondToken);
        body.addProperty("platform", "fcm");
        body.addProperty("fcmToken", fcmToken);
        body.addProperty("deviceName", deviceName);
        body.addProperty("browserLabel", browserLabel);
        body.addProperty("updateOnly", updateOnly);
        if (supportId != null) body.addProperty("supportId", supportId);
        return send("POST", "/v1/pairings", null, body, DEFAULT_TIMEOUT_MS);
    }

    /** DELETE /v1/pairings/{id}。204 / 404 はどちらも「サーバには無い」。 */
    @NonNull
    public Reply unpair(@NonNull String pairingId, @NonNull OtpRelayCrypto.Keys keys) throws IOException {
        return send("DELETE", "/v1/pairings/" + pairingId, keys.respondToken, null, DEFAULT_TIMEOUT_MS);
    }

    /** POST /v1/requests/{id}/response。 */
    @NonNull
    public Reply respond(@NonNull String requestId, @NonNull OtpRelayCrypto.Keys keys, @NonNull JsonObject body,
                         long timeoutMs) throws IOException {
        return send("POST", "/v1/requests/" + requestId + "/response", keys.respondToken, body, timeoutMs);
    }

    @NonNull
    private Reply send(@NonNull String method, @NonNull String path, @Nullable String bearer,
                       @Nullable JsonObject body, long timeoutMs) throws IOException {
        Request.Builder rb = new Request.Builder().url(baseUrl + path);
        if (bearer != null) rb.header("Authorization", "Bearer " + bearer);
        RequestBody rbody = body != null ? RequestBody.create(body.toString(), JSON) : null;
        rb.method(method, rbody);
        OkHttpClient c = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build();
        try (Response resp = c.newCall(rb.build()).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            return new Reply(resp.code(), errorCode(text));
        }
    }

    @Nullable
    static String errorCode(@NonNull String text) {
        if (text.isEmpty()) return null;
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            return o.has("error") && !o.get("error").isJsonNull() ? o.get("error").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
