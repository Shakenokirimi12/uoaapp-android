package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.BuildConfig;
import com.shakenokirimi12.uoa_app.data.models.PushNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PushNotificationService {
    private static final String BASE_URL = "https://uoa-app-push.shakenokirimi12.workers.dev";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private String deviceId;

    public interface NotificationCallback {
        void onResult(List<PushNotification> notifications, int unreadCount);
        void onError(String message);
    }

    public void init(String storedDeviceId) {
        if (storedDeviceId != null && !storedDeviceId.isEmpty()) {
            this.deviceId = storedDeviceId;
        } else {
            this.deviceId = UUID.randomUUID().toString();
        }
    }

    public String getDeviceId() { return deviceId; }

    public void registerDevice() { registerDevice(null); }

    /**
     * @param fcmToken 端末の FCM 登録トークン。サーバーはこれが無いと OS 通知 (課題の期限通知等)
     *                 を一切送れない。iOS の APNs deviceToken に相当する。
     */
    public void registerDevice(String fcmToken) {
        executor.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("deviceId", deviceId);
                body.addProperty("os", "Android");
                body.addProperty("appVersion", BuildConfig.VERSION_NAME);
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

    public void fetchNotifications(NotificationCallback callback) {
        executor.execute(() -> {
            try {
                OkHttpClient client = NetworkClient.getNoCookieClient();
                Request req = new Request.Builder()
                        .url(BASE_URL + "/api/notifications?deviceId=" + deviceId)
                        .build();

                try (Response resp = client.newCall(req).execute()) {
                    String json = resp.body().string();
                    JsonObject obj = gson.fromJson(json, JsonObject.class);
                    JsonArray arr = obj.getAsJsonArray("notifications");
                    List<PushNotification> list = gson.fromJson(arr,
                            new TypeToken<List<PushNotification>>() {}.getType());
                    if (list == null) list = new ArrayList<>();

                    int unread = 0;
                    for (PushNotification n : list) {
                        if (!n.isRead()) unread++;
                    }
                    int finalUnread = unread;
                    List<PushNotification> finalList = list;
                    mainHandler.post(() -> callback.onResult(finalList, finalUnread));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void markAsRead(String notificationId) {
        executor.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("deviceId", deviceId);

                OkHttpClient client = NetworkClient.getNoCookieClient();
                Request req = new Request.Builder()
                        .url(BASE_URL + "/api/notifications/" + notificationId + "/read")
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
                mainHandler.post(() -> callback.onDone(result));
            }
        });
    }

    public void reportError(String title, String message) {
        executor.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("deviceId", deviceId);
                body.addProperty("title", title);
                body.addProperty("message", message);
                body.addProperty("os", "Android");
                body.addProperty("appVersion", BuildConfig.VERSION_NAME);

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
