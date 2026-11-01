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
import com.shakenokirimi12.uoa_app.data.timetable.SignedShare;
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
 * 時間割共有のサーバー (uoa-app-push の /api/share/v2 と、v3 の継続共有の /api/share/v3/feeds) との通信。
 * iOS の TimetableShareAPI と同じ。どれも通信をするので、メインスレッドから呼ばない。
 * v2 の口にも、時間割の代わりに署名付きの共有 ({"share": ...}) を送る (docs/timetable-share-v3.md の 10.1)。
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

        public static Failure of(int status) {
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

        /** 端末の日時がずれていて、署名付きの共有が受け付けられなかった (400、code: "clock_skew")。 */
        @NonNull
        public static Failure clockSkew() {
            return new Failure(400, R.string.timetable_share_error_clock_skew);
        }

        /** フィードが無い (相手か自分が消した・掃除で消えた)。 */
        public boolean isGone() { return status == 410; }

        /** 署名付きの共有の署名が合わない。保存しない。 */
        @NonNull
        public static Failure unverified() {
            return new Failure(-3, R.string.timetable_failure_unverified);
        }

        /** 自分が出している QR (自分の鍵の共有) を読んだ。 */
        @NonNull
        public static Failure ownShare() {
            return new Failure(-4, R.string.timetable_incoming_own_qr);
        }

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
        /** 署名なしの時間割 (古いアプリの返事)。share があるときは使わない。 */
        @Nullable public final SharedTimetable timetable;
        /** 署名付きの共有。確かめるまで信じない (TimetableShareStore で SignedShare.verify)。 */
        @Nullable public final JsonElement share;
        /** 「今後も共有し続ける」の申し込み (相手が作った、こちら宛てのフィード)。 */
        @Nullable public final String feedId;

        Reply(@NonNull String replyId, @Nullable SharedTimetable timetable, @Nullable JsonElement share,
              @Nullable String feedId) {
            this.replyId = replyId;
            this.timetable = timetable;
            this.share = share;
            this.feedId = feedId;
        }
    }

    /** 開いた共有。share があればそちらを確かめて使い、timetable (署名なし) には落とさない。 */
    public static final class Fetched {
        @Nullable public final SharedTimetable timetable;
        @Nullable public final JsonElement share;

        Fetched(@Nullable SharedTimetable timetable, @Nullable JsonElement share) {
            this.timetable = timetable;
            this.share = share;
        }
    }

    /** 読んだフィード。share は確かめるまで信じない。 */
    public static final class Feed {
        @NonNull public final JsonElement share;
        public final long version;
        @Nullable public final String peerFeedId;

        Feed(@NonNull JsonElement share, long version, @Nullable String peerFeedId) {
            this.share = share;
            this.version = version;
            this.peerFeedId = peerFeedId;
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
    public Created create(@NonNull SignedShare share) throws Failure {
        JsonObject json = send("POST", "/api/share/v2", shareBody(share), null);
        String id = string(json, "shareId");
        String token = string(json, "ownerToken");
        String expires = string(json, "expiresAt");
        Instant expiresAt = expires != null ? SharedTimetable.parseInstant(expires) : null;
        if (!TimetableShareLink.isShareId(id) || token == null || token.isEmpty() || expiresAt == null) throw Failure.of(-1);
        return new Created(id, token, expiresAt.toEpochMilli());
    }

    @NonNull
    public Fetched fetch(@NonNull String shareId) throws Failure {
        JsonObject json = send("GET", "/api/share/v2/" + shareId, null, null);
        JsonElement share = json.get("share");
        if (share != null && share.isJsonNull()) share = null;
        SharedTimetable t = SharedTimetable.normalize(json.get("timetable"));
        if (t == null && share == null) throw Failure.of(-1);
        return new Fetched(t, share);
    }

    /** 返事を置く。feedId は「今後も共有し続ける」の申し込み (こちらが作った、相手宛てのフィード)。 */
    public void reply(@NonNull String shareId, @NonNull SignedShare share, @Nullable String feedId) throws Failure {
        JsonObject body = shareBody(share);
        if (feedId != null) body.addProperty("feedId", feedId);
        send("POST", "/api/share/v2/" + shareId + "/replies", body, null);
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
            JsonObject o = item.getAsJsonObject();
            String id = string(o, "replyId");
            SharedTimetable t = SharedTimetable.normalize(o.get("timetable"));
            JsonElement share = o.get("share");
            if (share != null && share.isJsonNull()) share = null;
            String feedId = string(o, "feedId");
            if (id != null && (t != null || share != null)) {
                result.add(new Reply(id, t, share, TimetableShareLink.isShareId(feedId) ? feedId : null));
            }
        }
        return result;
    }

    public void delete(@NonNull String shareId, @NonNull String ownerToken) throws Failure {
        send("DELETE", "/api/share/v2/" + shareId, null, ownerToken);
    }

    // ── 継続共有のフィード (/api/share/v3/feeds) ──

    /**
     * フィードを作る。id はこちらが作る (128 ビットの乱数)。signature は "create" の操作の署名 (版番号 0)。
     * 同じ id がもう有れば 409 (呼び出し側で id を作り直す)。
     */
    public void createFeed(@NonNull String feedId, @NonNull SignedShare share, @NonNull String signature) throws Failure {
        JsonObject body = shareBody(share);
        body.addProperty("feedId", feedId);
        body.addProperty("signature", signature);
        send("POST", "/api/share/v3/feeds", body, null);
    }

    @NonNull
    public Feed getFeed(@NonNull String feedId) throws Failure {
        JsonObject json = send("GET", "/api/share/v3/feeds/" + feedId, null, null);
        JsonElement share = json.get("share");
        JsonElement version = json.get("version");
        if (share == null || !share.isJsonObject() || version == null || !version.isJsonPrimitive()
                || !version.getAsJsonPrimitive().isNumber()) throw Failure.of(-1);
        String peer = string(json, "peerFeedId");
        long v;
        try {
            v = version.getAsLong();
        } catch (NumberFormatException e) {
            throw Failure.of(-1);
        }
        return new Feed(share, v, TimetableShareLink.isShareId(peer) ? peer : null);
    }

    public void putFeed(@NonNull String feedId, @NonNull SignedShare share, long version, @NonNull String signature)
            throws Failure {
        JsonObject body = shareBody(share);
        body.addProperty("version", version);
        body.addProperty("signature", signature);
        send("PUT", "/api/share/v3/feeds/" + feedId, body, null);
    }

    public void deleteFeed(@NonNull String feedId, long version, @NonNull String signature) throws Failure {
        JsonObject body = new JsonObject();
        body.addProperty("version", version);
        body.addProperty("signature", signature);
        send("DELETE", "/api/share/v3/feeds/" + feedId, body, null);
    }

    /** 相手のフィードの「相手の欄」に、こちらのフィードの id を書く (最初の 1 回だけ。同じ値の書き直しは成功)。 */
    /** 相手のフィード (feedId) の相手の欄に、自分のフィード (peerFeedId) を書く。signature は自分のフィードの鍵で "peer" に署名したもの。 */
    public void setPeer(@NonNull String feedId, @NonNull String peerFeedId, @NonNull String signature) throws Failure {
        JsonObject body = new JsonObject();
        body.addProperty("peerFeedId", peerFeedId);
        body.addProperty("signature", signature);
        send("POST", "/api/share/v3/feeds/" + feedId + "/peer", body, null);
    }

    @NonNull
    private static JsonObject shareBody(@NonNull SignedShare share) {
        JsonObject body = new JsonObject();
        body.add("share", share.toJson());
        return body;
    }

    @NonNull
    private JsonObject send(@NonNull String method, @NonNull String path, @Nullable JsonObject body,
                            @Nullable String bearer) throws Failure {
        Request.Builder rb = new Request.Builder().url(baseUrl + path);
        if (bearer != null) rb.header("Authorization", "Bearer " + bearer);
        RequestBody rbody = null;
        if (body != null) rbody = RequestBody.create(body.toString(), JSON);
        rb.method(method, rbody);
        try (Response resp = client.newCall(rb.build()).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (resp.code() == 400 && isClockSkew(text)) throw Failure.clockSkew();
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

    /** サーバーが日時のずれで断ったか (共有の作成と返事の 400 の本文 {"code":"clock_skew"})。 */
    static boolean isClockSkew(@NonNull String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() && "clock_skew".equals(string(e.getAsJsonObject(), "code"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nullable
    private static String string(@NonNull JsonObject o, @NonNull String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
