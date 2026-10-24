package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shakenokirimi12.uoa_app.BuildConfig;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PushNotificationService {
    private static final String BASE_URL = "https://uoa-app.shakenokiri.me/push";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Gson gson = new Gson();
    /**
     * 端末 ID は呼ぶたびに PreferenceManager から読む。以前は最初に読んだ値をフィールドに持っていたので、
     * 「ログアウト」(prefs.clearAll) で ID が作り直されても、このプロセスの間は古い ID で登録・同期し、
     * app-config (PreferenceManager から直接読む) とは別の ID を使っていた。
     */
    private final Supplier<String> deviceIds;
    /** 問い合わせ ID (SupportId)。端末 ID と同じくログアウトで作り直されるので、送るたびに読む。null なら送らない。 */
    private final Supplier<String> supportIds;

    private static PushNotificationService shared;

    /**
     * プロセスで 1 つのインスタンス。内部の単一スレッド executor に shutdown の口が無く、
     * 呼び出しのたびに new するとスレッドが増え続ける。期限通知の同期はこの executor の
     * 順序に依存するので、同期の呼び出し元は必ずこれを使うこと。
     */
    public static synchronized PushNotificationService shared(android.content.Context context) {
        if (shared == null) {
            com.shakenokirimi12.uoa_app.data.PreferenceManager prefs =
                    com.shakenokirimi12.uoa_app.data.PreferenceManager.getInstance(context.getApplicationContext());
            shared = new PushNotificationService(prefs::getDeviceId, prefs::getSupportId);
        }
        return shared;
    }

    PushNotificationService(Supplier<String> deviceIds) {
        this(deviceIds, () -> null);
    }

    PushNotificationService(Supplier<String> deviceIds, Supplier<String> supportIds) {
        this.deviceIds = deviceIds;
        this.supportIds = supportIds;
    }

    public String getDeviceId() { return deviceIds.get(); }

    public void registerDevice() { registerDevice(null); }

    /**
     * @param fcmToken 端末の FCM 登録トークン。サーバーはこれが無いと OS 通知 (課題の期限通知等)
     *                 を一切送れない。iOS の APNs deviceToken に相当する。
     */
    public void registerDevice(String fcmToken) {
        // 端末 ID も問い合わせ ID も積む前に読む (新しい問い合わせ ID が前の端末の行に付かないように)。
        final String deviceId = getDeviceId();
        final String supportId = supportIds.get();
        executor.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("deviceId", deviceId);
                body.addProperty("os", "Android");
                body.addProperty("appVersion", BuildConfig.VERSION_NAME);
                // 管理画面で問い合わせ ID から端末を引くため (SupportId)。
                if (supportId != null) body.addProperty("supportId", supportId);
                if (fcmToken != null && !fcmToken.isEmpty()) {
                    body.addProperty("fcmToken", fcmToken);
                }

                OkHttpClient client = NetworkClient.getNoCookieClient();
                Request req = new Request.Builder()
                        .url(BASE_URL + "/api/devices/register")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    // ignore result
                }
            } catch (Exception ignored) {}
        });
    }

    // MARK: 課題の期限通知 (サーバー配信)

    public static class AssignmentReminder {
        public final int assignmentId;
        public final String offsetId;
        public final String triggerAt;   // ISO8601 (UTC)
        public final String title;
        public final String body;
        public final String url;

        public AssignmentReminder(int assignmentId, String offsetId, String triggerAt,
                                  String title, String body, String url) {
            this.assignmentId = assignmentId;
            this.offsetId = offsetId;
            this.triggerAt = triggerAt;
            this.title = title;
            this.body = body;
            this.url = url;
        }
    }

    public interface SyncCallback { void onDone(boolean succeeded); }

    /**
     * 端末が持っている期限通知の一覧をサーバーへ渡し、サーバー側をそれに合わせる。
     * iOS の syncAssignmentReminders と同じエンドポイント・同じ意味論。
     *
     * @param clearAll 「通知を止める」意思表示。通常の再同期では、期限が来ていてまだ配信されて
     *                 いない予約をサーバーに残す (クライアントは期限の来た分を一覧に含めないため、
     *                 消すと cron が拾う前の再同期で黙って消える)。止めるときだけは全部消す。
     */
    public void syncAssignmentReminders(List<AssignmentReminder> reminders, boolean clearAll, SyncCallback callback) {
        // 呼んだ時点の ID で送る。ログアウトは予約を消す同期をここへ積んでから prefs.clearAll() で ID を
        // 作り直すので、executor の中で読むと新しい ID の (空の) 予約を消すことになる。
        final String deviceId = getDeviceId();
        executor.execute(() -> {
            boolean ok = false;
            try {
                JsonObject body = new JsonObject();
                body.addProperty("deviceId", deviceId);
                body.addProperty("clearAll", clearAll);
                JsonArray arr = new JsonArray();
                for (AssignmentReminder r : reminders) {
                    JsonObject o = new JsonObject();
                    o.addProperty("assignmentId", r.assignmentId);
                    o.addProperty("offsetId", r.offsetId);
                    o.addProperty("triggerAt", r.triggerAt);
                    o.addProperty("title", r.title);
                    o.addProperty("body", r.body);
                    o.addProperty("url", r.url);
                    arr.add(o);
                }
                body.add("reminders", arr);

                // 「全データを削除」はこの応答を待つ。既定の接続待ちのまま電波が悪いだけで
                // 長く固まって見えるので短くする。
                OkHttpClient client = NetworkClient.getNoCookieClient().newBuilder()
                        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                Request req = new Request.Builder()
                        .url(BASE_URL + "/api/reminders/sync")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    ok = resp.isSuccessful();
                }
            } catch (Exception e) {
                android.util.Log.w("PushNotificationService", "reminders/sync failed: " + e.getMessage());
            }
            if (callback != null) {
                final boolean result = ok;
                // 使う所で作る (フィールドで作ると、Looper の無い JVM のテストで生成できない)。
                new Handler(Looper.getMainLooper()).post(() -> callback.onDone(result));
            }
        });
    }

    /**
     * サーバーからこの端末の行と、それに紐付く予約・エラーレポートを消す (DELETE /api/devices/:deviceId)。
     * ログアウトは prefs.clearAll() で ID を作り直すので、その前に呼ぶ (呼んだ時点の ID を消す)。
     * 消さないと古い ID の行が FCM トークンを持ったまま残り、再ログインするまで一斉配信がそちらにも届く。
     * 失敗しても止めない。残った行のトークンは、新しい ID で登録したときにサーバーが外す。
     */
    public void deleteDevice() {
        final String deviceId = getDeviceId();
        executor.execute(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE_URL + "/api/devices/" + java.net.URLEncoder.encode(deviceId, "UTF-8"))
                        .delete()
                        .build();
                OkHttpClient client = NetworkClient.getNoCookieClient().newBuilder()
                        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) android.util.Log.w("PushNotificationService", "devices delete: HTTP " + resp.code());
                }
            } catch (Exception e) {
                android.util.Log.w("PushNotificationService", "devices delete failed: " + e.getMessage());
            }
        });
    }

    public void reportError(String title, String message) {
        final String deviceId = getDeviceId();
        final String supportId = supportIds.get();
        executor.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("deviceId", deviceId);
                body.addProperty("title", title);
                body.addProperty("message", message);
                body.addProperty("os", "Android");
                body.addProperty("appVersion", BuildConfig.VERSION_NAME);
                if (supportId != null) body.addProperty("supportId", supportId);

                OkHttpClient client = NetworkClient.getNoCookieClient();
                Request req = new Request.Builder()
                        .url(BASE_URL + "/api/errors/report")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();
                try (Response resp = client.newCall(req).execute()) {}
            } catch (Exception ignored) {}
        });
    }
}
