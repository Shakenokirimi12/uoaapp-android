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
    private static final String DEFAULT_BASE_URL = "https://elms.u-aizu.ac.jp";

    /**
     * 大学側が URL を変えたときに、審査なしにフラグだけで追従できるようにする (iOS と同じ)。
     * 未設定なら既定値。
     */
    private static String baseUrl() {
        return AppConfigService.getInstance().stringFlag("moodle_base_url", DEFAULT_BASE_URL);
    }

    /** 画面側が「この URL は Moodle か」を判定するために使う。 */
    public static String currentBaseUrl() { return baseUrl(); }
    public static final String INVALID_CREDENTIALS_MESSAGE = AuthErrors.INVALID_CREDENTIALS_MESSAGE;

    public static boolean isInvalidCredentialsError(String message) {
        return AuthErrors.isInvalidCredentials(message);
    }

    public static void markInvalidIfCredentialsError(android.content.Context context, String message) {
        AuthErrors.markInvalidIfCredentialsError(context, message);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    // Shared across instances: the cookie jar is process-wide already, and every screen
    // creates its own MoodleService. Per-instance keys meant a fresh instance (course
    // detail) called the AJAX API with an empty sesskey and quietly got nothing back.
    private static String sesskey = "";
    private static String userid = "";

    /** True once any instance has logged in during this process. */
    public boolean hasSession() {
        return sesskey != null && !sesskey.isEmpty();
    }

    public void login(String username, String password, ServiceCallback<Boolean> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                OkHttpClient client = NetworkClient.getCookieClient();

                Log.d(TAG, "Starting login for: " + username);

                // GET login page for logintoken
                Request getLogin = new Request.Builder()
                        .url(baseUrl() + "/login/index.php")
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
                        .url(baseUrl() + "/login/index.php")
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

                // Check for login failure in the response HTML content.
                // <script> を除いてから見る。この判定は「自動同期の恒久停止」に使われるため、
                // ページの JS 内に同じ文言が文字列として埋まっているだけで、正しいパスワードの
                // ユーザーまで止めてしまう誤検知を避ける (iOS 側で実例あり)。
                String bodyWithoutScripts = AuthErrors.withoutScripts(responseHtml);
                if (bodyWithoutScripts.contains("Invalid login")
                        || bodyWithoutScripts.contains("ログインが無効です")
                        || bodyWithoutScripts.contains("invalidlogin")
                        || bodyWithoutScripts.contains("id=\"loginerrormessage\"")) {
                    Log.e(TAG, "Login failed: invalid credentials");
                    postError(callback, INVALID_CREDENTIALS_MESSAGE);
                    return;
                }

                // GET /my/ to extract sesskey and userid
                Request getDashboard = new Request.Builder()
                        .url(baseUrl() + "/my/")
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
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
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
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
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
        String url = baseUrl() + "/lib/ajax/service.php?sesskey=" + sesskey + "&info=" + info;

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
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                OkHttpClient client = NetworkClient.getCookieClient();
                String url = baseUrl() + "/webservice/rest/server.php?wstoken=" + sesskey
                        + "&wsfunction=core_course_get_contents&courseid=" + courseId
                        + "&moodlewsrestformat=json";

                // Try AJAX approach first
                String ajaxUrl = baseUrl() + "/lib/ajax/service.php?sesskey=" + sesskey
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
                        com.google.gson.JsonElement err = first.get("error");
                        if (err == null || err.isJsonNull() || (err.isJsonPrimitive() && !err.getAsBoolean())) {
                            com.google.gson.JsonElement data = first.get("data");
                            java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<List<com.shakenokirimi12.uoa_app.data.models.CourseSection>>() {}.getType();
                            List<com.shakenokirimi12.uoa_app.data.models.CourseSection> sections =
                                    new com.google.gson.Gson().fromJson(data, listType);
                            if (sections == null) sections = new java.util.ArrayList<>();
                            postSuccess(callback, sections);
                            return;
                        }
                        // core_course_get_contents is not an AJAX-enabled function on this
                        // Moodle ("ウェブサービスを利用できません"), so this branch is the normal
                        // path. Same as iOS: fall back to scraping the course page.
                        Log.d(TAG, "core_course_get_contents unavailable, scraping course page");
                    }
                }
                postSuccess(callback, scrapeCourseContents(client, courseId));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * Port of the iOS fetchCourseContentsScraping: parse /course/view.php?id=N into sections
     * and activity links. Throws when the page cannot be loaded so the caller shows an error
     * instead of an empty (and therefore misleading) list.
     */
    private List<com.shakenokirimi12.uoa_app.data.models.CourseSection> scrapeCourseContents(OkHttpClient client, int courseId) throws java.io.IOException {
        Request req = new Request.Builder()
                .url(baseUrl() + "/course/view.php?id=" + courseId)
                .header("User-Agent", NetworkClient.getUserAgent())
                .build();
        String html;
        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() != 200 || resp.body() == null) {
                throw new java.io.IOException("コースページを読み込めませんでした (HTTP " + resp.code() + ")");
            }
            html = resp.body().string();
        }
        List<com.shakenokirimi12.uoa_app.data.models.CourseSection> sections = new ArrayList<>();
        Matcher sec = Pattern.compile("<li[^>]+id=\"section-(\\d+)\"([^>]*)>([\\s\\S]*?)(?=<li[^>]+id=\"section-\\d+\"|$)", Pattern.CASE_INSENSITIVE).matcher(html);
        Pattern nameP = Pattern.compile("<h3[^>]+class=\"[^\"]*sectionname[^\"]*\"[^>]*>([\\s\\S]*?)</h3>");
        Pattern ariaP = Pattern.compile("aria-label=\"([^\"]+)\"");
        Pattern dataNameP = Pattern.compile("data-sectionname=\"([^\"]+)\"");
        Pattern summaryP = Pattern.compile("<div[^>]+class=\"[^\"]*summary[^\"]*\"[^>]*>([\\s\\S]*?)</div>");
        Pattern actP = Pattern.compile("<li[^>]+class=\"activity\\s+([^\"]*)\"\\s+id=\"module-(\\d+)\"[^>]*>([\\s\\S]*?)</li>", Pattern.CASE_INSENSITIVE);
        Pattern hrefP = Pattern.compile("<a[^>]+href=\"([^\"]+)\"");
        Pattern spanNameP = Pattern.compile("<span[^>]+class=\"[^\"]*(?:instance|activity)name[^\"]*\"[^>]*>([\\s\\S]*?)</span>");
        Pattern linkTextP = Pattern.compile("<a[^>]+href=\"[^\"]+\"[^>]*>([\\s\\S]*?)</a>");
        Pattern modtypeP = Pattern.compile("modtype_(\\w+)");
        while (sec.find()) {
            String num = sec.group(1), attrs = sec.group(2), content = sec.group(3);
            String name;
            Matcher m;
            if ((m = nameP.matcher(content)).find()) name = stripTags(m.group(1));
            else if ((m = ariaP.matcher(attrs)).find()) name = m.group(1).trim();
            else if ((m = dataNameP.matcher(attrs)).find()) name = m.group(1).trim();
            else name = "0".equals(num) ? "General" : "Section " + num;
            String summary = (m = summaryP.matcher(content)).find() ? m.group(1).trim() : "";
            com.shakenokirimi12.uoa_app.data.models.CourseSection section =
                    new com.shakenokirimi12.uoa_app.data.models.CourseSection(toInt(num), name, summary);
            Matcher act = actP.matcher(content);
            while (act.find()) {
                String classes = act.group(1), inner = act.group(3);
                Matcher href = hrefP.matcher(inner);
                if (!href.find()) continue;
                String url = href.group(1).replace("&amp;", "&");
                String modName;
                if ((m = spanNameP.matcher(inner)).find()) modName = stripTags(m.group(1));
                else if ((m = linkTextP.matcher(inner)).find()) modName = stripTags(m.group(1));
                else modName = "";
                String modname = (m = modtypeP.matcher(classes)).find() ? m.group(1) : "unknown";
                section.getModules().add(new com.shakenokirimi12.uoa_app.data.models.CourseSection.CourseModule(
                        toInt(act.group(2)), modName, modname, url));
            }
            sections.add(section);
        }
        return sections;
    }

    private static String stripTags(String s) {
        return android.text.Html.fromHtml(s.replaceAll("<[^>]*>", ""), android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
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
