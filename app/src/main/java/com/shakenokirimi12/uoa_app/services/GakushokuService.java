package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenu;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GakushokuService {
    private static final String API_URL =
            "https://uoa-app.shakenokiri.me/gakushoku/api/menus/v2";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
            "AppleWebKit/605.1.15";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    public void fetchMenu(ServiceCallback<GakushokuMenu> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("gakushoku_menu_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                postSuccess(callback, fetchMenuBlocking());
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * 呼び出したスレッドで取得する (WorkManager の Worker から使う)。失敗は例外。
     * killswitch の確認は呼び出し側の責任 (fetchMenu は確認してから呼ぶ)。
     */
    public GakushokuMenu fetchMenuBlocking() throws Exception {
        OkHttpClient client = NetworkClient.getNoCookieClient();

        Request request = new Request.Builder()
                .url(API_URL)
                .header("User-Agent", MOBILE_UA)
                .build();

        String json;
        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("学食メニューの取得に失敗しました (HTTP " + resp.code() + ")");
            }
            json = resp.body().string();
        }

        GakushokuMenu menu = gson.fromJson(json, GakushokuMenu.class);
        if (menu == null || menu.weeks == null) {
            throw new java.io.IOException("学食メニューの応答を解析できませんでした");
        }
        return menu;
    }

    private <T> void postSuccess(ServiceCallback<T> cb, T result) {
        mainHandler.post(() -> cb.onSuccess(result));
    }

    private <T> void postError(ServiceCallback<T> cb, String msg) {
        mainHandler.post(() -> cb.onError(msg != null ? msg : "不明なエラー"));
    }
}
