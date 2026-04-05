package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenuItem;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GakushokuService {
    private static final String API_URL =
            "https://gakushoku-proxy.ken20051205.workers.dev/api/menus";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
            "AppleWebKit/605.1.15";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    public void fetchMenu(ServiceCallback<List<GakushokuMenuItem>> callback) {
        executor.execute(() -> {
            try {
                OkHttpClient client = NetworkClient.getNoCookieClient();

                Request request = new Request.Builder()
                        .url(API_URL)
                        .header("User-Agent", MOBILE_UA)
                        .build();

                String json;
                try (Response resp = client.newCall(request).execute()) {
                    if (!resp.isSuccessful()) {
                        postError(callback, "学食メニューの取得に失敗しました (HTTP " + resp.code() + ")");
                        return;
                    }
                    json = resp.body().string();
                }

                Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
                List<Map<String, Object>> weeks = gson.fromJson(json, listType);

                List<GakushokuMenuItem> allItems = new ArrayList<>();
                if (weeks != null) {
                    for (Map<String, Object> week : weeks) {
                        List<Map<String, Object>> items =
                                (List<Map<String, Object>>) week.get("items");
                        if (items == null) continue;

                        for (Map<String, Object> item : items) {
                            GakushokuMenuItem menuItem = new GakushokuMenuItem();
                            menuItem.setDate(toStr(item.get("dateString")));
                            menuItem.setLunch(toStr(item.get("lunch")));
                            menuItem.setFish(toStr(item.get("fish")));
                            menuItem.setSalad(toStr(item.get("salad")));
                            menuItem.setDinner(toStr(item.get("dinner")));
                            allItems.add(menuItem);
                        }
                    }
                }

                postSuccess(callback, allItems);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private static String toStr(Object o) {
        return o != null ? String.valueOf(o) : "";
    }

    private <T> void postSuccess(ServiceCallback<T> cb, T result) {
        mainHandler.post(() -> cb.onSuccess(result));
    }

    private <T> void postError(ServiceCallback<T> cb, String msg) {
        mainHandler.post(() -> cb.onError(msg != null ? msg : "不明なエラー"));
    }
}
