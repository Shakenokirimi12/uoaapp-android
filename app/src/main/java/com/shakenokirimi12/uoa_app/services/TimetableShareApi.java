package com.shakenokirimi12.uoa_app.services;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shakenokirimi12.uoa_app.BuildConfig;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.timetable.SharedTimetable;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareLink;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 時間割共有のサーバー (uoa-app-push の /api/share/v2) との通信。iOS の TimetableShareAPI と同じ。
 * どれも通信をするので、メインスレッドから呼ばない。
 */
public final class TimetableShareApi {
    private static final MediaType JSON = MediaType.parse("application/json");

    /** 画面に出す失敗。文面は iOS と同じ。 */
    public static final class Failure extends Exception {
        /** HTTP ステータス。通信できなかったときは 0、読めない応答は -1。 */
        public final int status;

        @StringRes private final int messageRes;

        Failure(int status, @StringRes int messageRes) {
            super("timetable share failure: " + status);
            this.status = status;
            this.messageRes = messageRes;
        }

        /** 画面に出す文面。 */
        @NonNull
        public String message(@NonNull Context ctx) { return ctx.getString(messageRes); }

        static Failure of(int status) {
            switch (status) {
                case 404: return new Failure(404, R.string.timetable_failure_share_expired);
                case 429: return new Failure(429, R.string.timetable_failure_too_many_requests);
                case 409: return new Failure(409, R.string.timetable_failure_full);
                case 0: return new Failure(0, R.string.timetable_failure_no_network);
                case -1: return new Failure(-1, R.string.timetable_failure_unreadable);
                default: return new Failure(status, R.string.timetable_failure_server_error);
            }
        }

        public boolean isExpired() { return status == 404; }

        /** 返事を読むトークンを端末に置けなかった (暗号化 prefs が使えないなど)。通信の失敗ではない。 */
        @NonNull
        public static Failure cannotStoreOnDevice() {
            return new Failure(-2, R.string.timetable_failure_cannot_store);
        }
    }

    public static final class Created {
        @NonNull public final String shareId;
        @NonNull public final String ownerToken;
        public final long expiresAt;

        Created(@NonNull String shareId, @NonNull String ownerToken, long expiresAt) {
            this.shareId = shareId;
            this.ownerToken = ownerToken;
            this.expiresAt = expiresAt;
        }
    }

    public static final class Reply {
        @NonNull public final String replyId;
        @NonNull public final SharedTimetable timetable;

        Reply(@NonNull String replyId, @NonNull SharedTimetable timetable) {
            this.replyId = replyId;
            this.timetable = timetable;
        }
    }

    private final String baseUrl;
    private final OkHttpClient client;

    public TimetableShareApi() {
        // debug ビルドだけ、手元の wrangler dev に向けられる (-PtimetableShareBaseUrl=...)。release は常に空。
        this(BuildConfig.TIMETABLE_SHARE_BASE_URL.isEmpty() ? TimetableShareLink.WEB_BASE : BuildConfig.TIMETABLE_SHARE_BASE_URL,
                NetworkClient.getNoCookieClient().newBuilder().callTimeout(20, TimeUnit.SECONDS).build());
    }

    TimetableShareApi(@NonNull String baseUrl, @NonNull OkHttpClient client) {
        this.baseUrl = baseUrl;
        this.client = client;
    }

    @NonNull
    public Created create(@NonNull SharedTimetable timetable) throws Failure {
        JsonObject json = send("POST", "/api/share/v2", timetable, null);
        String id = string(json, "shareId");
        String token = string(json, "ownerToken");
        String expires = string(json, "expiresAt");
        Instant expiresAt = expires != null ? SharedTimetable.parseInstant(expires) : null;
        if (!TimetableShareLink.isShareId(id) || token == null || token.isEmpty() || expiresAt == null) throw Failure.of(-1);
        return new Created(id, token, expiresAt.toEpochMilli());
    }

    @NonNull
    public SharedTimetable fetch(@NonNull String shareId) throws Failure {
        JsonObject json = send("GET", "/api/share/v2/" + shareId, null, null);
        SharedTimetable t = SharedTimetable.normalize(json.get("timetable"));
        if (t == null) throw Failure.of(-1);
        return t;
    }

    public void reply(@NonNull String shareId, @NonNull SharedTimetable timetable) throws Failure {
        send("POST", "/api/share/v2/" + shareId + "/replies", timetable, null);
    }

    @NonNull
    public List<Reply> replies(@NonNull String shareId, @NonNull String ownerToken) throws Failure {
        JsonObject json = send("GET", "/api/share/v2/" + shareId + "/replies", null, ownerToken);
        JsonElement list = json.get("replies");
        if (list == null || !list.isJsonArray()) throw Failure.of(-1);
        List<Reply> result = new ArrayList<>();
        // 読めない返事は飛ばす (1 件のために、ほかの返事まで受け取れなくしない)。
        for (JsonElement item : list.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            String id = string(item.getAsJsonObject(), "replyId");
            SharedTimetable t = SharedTimetable.normalize(item.getAsJsonObject().get("timetable"));
            if (id != null && t != null) result.add(new Reply(id, t));
        }
        return result;
    }

    public void delete(@NonNull String shareId, @NonNull String ownerToken) throws Failure {
        send("DELETE", "/api/share/v2/" + shareId, null, ownerToken);
    }

    @NonNull
    private JsonObject send(@NonNull String method, @NonNull String path, @Nullable SharedTimetable body,
                            @Nullable String bearer) throws Failure {
        Request.Builder rb = new Request.Builder().url(baseUrl + path);
        if (bearer != null) rb.header("Authorization", "Bearer " + bearer);
        RequestBody rbody = null;
        if (body != null) {
            JsonObject wrapper = new JsonObject();
            wrapper.add("timetable", body.toJson());
            rbody = RequestBody.create(wrapper.toString(), JSON);
        }
        rb.method(method, rbody);
        try (Response resp = client.newCall(rb.build()).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (resp.code() != 200) throw Failure.of(resp.code());
            try {
                return JsonParser.parseString(text).getAsJsonObject();
            } catch (RuntimeException e) {
                throw Failure.of(-1);
            }
        } catch (IOException e) {
            throw Failure.of(0);
        }
    }

    @Nullable
    private static String string(@NonNull JsonObject o, @NonNull String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
