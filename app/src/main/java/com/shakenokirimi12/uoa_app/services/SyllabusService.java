package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.shakenokirimi12.uoa_app.data.models.SyllabusData;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SyllabusService {
    private static final String BASE_URL = "https://sillabus-api.ken20051205.workers.dev";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public String extractCourseId(String shortname) {
        if (shortname == null || shortname.isEmpty()) return null;

        Matcher m1 = Pattern.compile("(?<=\\))\\s*[A-Z0-9]+").matcher(shortname);
        if (m1.find()) return m1.group().trim();

        Matcher m2 = Pattern.compile("^[A-Z0-9]+").matcher(shortname);
        if (m2.find()) return m2.group();

        return null;
    }

    public void fetchSyllabus(String courseId, ServiceCallback<SyllabusData> callback) {
        executor.execute(() -> {
            try {
                OkHttpClient client = NetworkClient.getNoCookieClient();
                Request req = new Request.Builder()
                        .url(BASE_URL + "/syllabus/" + courseId)
                        .build();

                try (Response resp = client.newCall(req).execute()) {
                    if (resp.code() == 404) {
                        mainHandler.post(() -> callback.onError("シラバスが見つかりませんでした"));
                        return;
                    }
                    String json = resp.body().string();
                    SyllabusData data = new Gson().fromJson(json, SyllabusData.class);
                    mainHandler.post(() -> callback.onSuccess(data));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
}
