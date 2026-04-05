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
    private static final String BASE_URL = "https://uoa-app-push.ken20051205.workers.dev";
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

    public void registerDevice() {
        executor.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("deviceId", deviceId);
                body.addProperty("os", "Android");
                body.addProperty("appVersion", BuildConfig.VERSION_NAME);

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
