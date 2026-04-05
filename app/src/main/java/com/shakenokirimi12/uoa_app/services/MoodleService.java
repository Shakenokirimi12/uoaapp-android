package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MoodleService {
    private static final String TAG = "MoodleService";
    private static final String BASE_URL = "https://elms.u-aizu.ac.jp";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    private String sesskey = "";
    private String userid = "";

    public void login(String username, String password, ServiceCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                OkHttpClient client = NetworkClient.getCookieClient();

                Log.d(TAG, "Starting login for: " + username);

                // GET login page for logintoken
                Request getLogin = new Request.Builder()
                        .url(BASE_URL + "/login/index.php")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .build();

                String loginHtml;
                try (Response resp = client.newCall(getLogin).execute()) {
                    loginHtml = resp.body().string();
                    Log.d(TAG, "Login page loaded, status: " + resp.code());
                }

                String loginToken = extractMatch(loginHtml,
                        "name=\"logintoken\"\\s+value=\"([^\"]+)\"");
                Log.d(TAG, "logintoken: " + (loginToken != null ? "found" : "not found"));

                // POST login
                StringBuilder body = new StringBuilder();
                body.append("username=").append(urlEncode(username.trim()));
                body.append("&password=").append(urlEncode(password.trim()));
                if (loginToken != null) {
                    body.append("&logintoken=").append(urlEncode(loginToken));
                }

                Request postLogin = new Request.Builder()
                        .url(BASE_URL + "/login/index.php")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .post(RequestBody.create(body.toString(), FORM))
                        .build();

                String responseHtml;
                String finalUrl;
                try (Response resp = client.newCall(postLogin).execute()) {
                    responseHtml = resp.body().string();
                    finalUrl = resp.request().url().toString();
                    Log.d(TAG, "POST login response, final URL: " + finalUrl
                            + ", status: " + resp.code());
                }

                // Check for login failure in the response HTML content
                if (responseHtml.contains("Invalid login")
                        || responseHtml.contains("ログインが無効です")
                        || responseHtml.contains("invalidlogin")
                        || responseHtml.contains("id=\"loginerrormessage\"")) {
                    Log.e(TAG, "Login failed: invalid credentials");
                    postError(callback, "ログインに失敗しました。ユーザー名またはパスワードを確認してください。");
                    return;
                }

                // GET /my/ to extract sesskey and userid
                Request getDashboard = new Request.Builder()
                        .url(BASE_URL + "/my/")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .build();

                String dashHtml;
                String dashUrl;
                try (Response resp = client.newCall(getDashboard).execute()) {
                    dashHtml = resp.body().string();
                    dashUrl = resp.request().url().toString();
                    Log.d(TAG, "Dashboard loaded, URL: " + dashUrl
                            + ", length: " + dashHtml.length());
                }

                // If /my/ redirects back to login, session is invalid
                if (dashUrl.contains("login/index.php")
                        || dashHtml.contains("id=\"login\"")
                        || dashHtml.contains("id=\"loginerrormessage\"")) {
                    Log.e(TAG, "Session invalid after login POST");
                    postError(callback, "セッション確立に失敗しました。再度お試しください。");
                    return;
                }

                sesskey = extractMatch(dashHtml, "\"sesskey\":\"([^\"]+)\"");
                userid = extractMatch(dashHtml, "\"userid\":\\s*\"?(\\d+)\"?");
                if (userid == null) {
                    userid = extractMatch(dashHtml, "user/profile\\.php\\?id=(\\d+)");
                }

                Log.d(TAG, "sesskey: " + (sesskey != null ? "found" : "NOT FOUND")
                        + ", userid: " + userid);

                if (sesskey == null || sesskey.isEmpty()) {
                    postError(callback, "セッション情報の取得に失敗しました");
                    return;
                }

                Log.d(TAG, "Login successful");
                postSuccess(callback, true);
            } catch (Exception e) {
                Log.e(TAG, "Login exception", e);
                postError(callback, "接続エラー: " + e.getMessage());
            }
        });
    }

    public void fetchCourses(ServiceCallback<List<MoodleCourse>> callback) {
        executor.execute(() -> {
            try {
                String jsonBody = "[{\"index\":0,\"methodname\":" +
                        "\"core_course_get_enrolled_courses_by_timeline_classification\"," +
                        "\"args\":{\"classification\":\"all\",\"limit\":0,\"offset\":0,\"sort\":\"fullname\"}}]";

                String response = ajaxCall(
                        "core_course_get_enrolled_courses_by_timeline_classification",
                        jsonBody);

                Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
                List<Map<String, Object>> arr = gson.fromJson(response, listType);

                if (arr == null || arr.isEmpty()) {
                    postSuccess(callback, new ArrayList<>());
                    return;
                }

                Map<String, Object> first = arr.get(0);
                if (Boolean.TRUE.equals(first.get("error"))) {
                    postError(callback, "コース取得に失敗しました");
                    return;
                }

                Map<String, Object> data = (Map<String, Object>) first.get("data");
                List<Map<String, Object>> coursesRaw =
                        (List<Map<String, Object>>) data.get("courses");

                List<MoodleCourse> courses = new ArrayList<>();
                if (coursesRaw != null) {
                    for (Map<String, Object> c : coursesRaw) {
                        MoodleCourse course = new MoodleCourse();
                        course.setId(toInt(c.get("id")));
                        course.setShortname(toStr(c.get("shortname")));
                        course.setFullname(toStr(c.get("fullname")));
                        course.setVisible(toInt(c.get("visible")) == 1);
                        course.setStartdate(toLong(c.get("startdate")));
                        course.setEnddate(toLong(c.get("enddate")));
                        courses.add(course);
                    }
                }

                postSuccess(callback, courses);
            } catch (Exception e) {
                Log.e(TAG, "fetchCourses error", e);
                postError(callback, e.getMessage());
            }
        });
    }

    public void fetchAssignments(ServiceCallback<List<Assignment>> callback) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis() / 1000;
                String jsonBody = "[{\"index\":0,\"methodname\":" +
                        "\"core_calendar_get_action_events_by_timesort\"," +
                        "\"args\":{\"limitnum\":50,\"timesortfrom\":" + now +
                        ",\"limittononsuspendedevents\":true}}]";

                String response = ajaxCall(
                        "core_calendar_get_action_events_by_timesort",
                        jsonBody);

                Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
                List<Map<String, Object>> arr = gson.fromJson(response, listType);

                List<Assignment> assignments = new ArrayList<>();
                if (arr != null && !arr.isEmpty()) {
                    Map<String, Object> first = arr.get(0);
                    Map<String, Object> data = (Map<String, Object>) first.get("data");
                    if (data != null) {
                        List<Map<String, Object>> events =
                                (List<Map<String, Object>>) data.get("events");
                        if (events != null) {
                            for (Map<String, Object> e : events) {
                                Assignment a = new Assignment();
                                a.setId(toInt(e.get("id")));
                                a.setName(toStr(e.get("name")));
                                a.setDueDate(toLong(e.get("timesort")));

                                Map<String, Object> course =
                                        (Map<String, Object>) e.get("course");
                                if (course != null) {
                                    a.setCourseName(toStr(course.get("fullname")));
                                    a.setCourseId(toInt(course.get("id")));
                                } else {
                                    a.setCourseName("Moodle Event");
                                }
                                assignments.add(a);
                            }
                        }
                    }
                }

                postSuccess(callback, assignments);
            } catch (Exception e) {
                Log.e(TAG, "fetchAssignments error", e);
                postError(callback, e.getMessage());
            }
        });
    }

    private String ajaxCall(String info, String jsonBody) throws IOException {
        OkHttpClient client = NetworkClient.getCookieClient();
        String url = BASE_URL + "/lib/ajax/service.php?sesskey=" + sesskey + "&info=" + info;

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.getUserAgent())
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            return resp.body().string();
        }
    }

    private static String extractMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    public void fetchCourseContents(int courseId, ServiceCallback<List<com.shakenokirimi12.uoa_app.data.models.CourseSection>> callback) {
        executor.execute(() -> {
            try {
                OkHttpClient client = NetworkClient.getCookieClient();
                String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + sesskey
                        + "&wsfunction=core_course_get_contents&courseid=" + courseId
                        + "&moodlewsrestformat=json";

                // Try AJAX approach first
                String ajaxUrl = BASE_URL + "/lib/ajax/service.php?sesskey=" + sesskey
                        + "&info=core_course_get_contents";
                com.google.gson.JsonArray args = new com.google.gson.JsonArray();
                com.google.gson.JsonObject call = new com.google.gson.JsonObject();
                call.addProperty("index", 0);
                call.addProperty("methodname", "core_course_get_contents");
                com.google.gson.JsonObject callArgs = new com.google.gson.JsonObject();
                callArgs.addProperty("courseid", courseId);
                call.add("args", callArgs);
                args.add(call);

                Request req = new Request.Builder()
                        .url(ajaxUrl)
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .post(RequestBody.create(args.toString(), JSON))
                        .build();

                try (Response resp = client.newCall(req).execute()) {
                    String body = resp.body().string();
                    com.google.gson.JsonArray respArr = new com.google.gson.Gson().fromJson(body, com.google.gson.JsonArray.class);
                    if (respArr != null && respArr.size() > 0) {
                        com.google.gson.JsonObject first = respArr.get(0).getAsJsonObject();
                        if (!first.has("error") || first.get("error").isJsonNull()) {
                            com.google.gson.JsonElement data = first.get("data");
                            java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<List<com.shakenokirimi12.uoa_app.data.models.CourseSection>>() {}.getType();
                            List<com.shakenokirimi12.uoa_app.data.models.CourseSection> sections =
                                    new com.google.gson.Gson().fromJson(data, listType);
                            if (sections == null) sections = new java.util.ArrayList<>();
                            postSuccess(callback, sections);
                            return;
                        }
                    }
                }
                postSuccess(callback, new java.util.ArrayList<>());
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0; }
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
