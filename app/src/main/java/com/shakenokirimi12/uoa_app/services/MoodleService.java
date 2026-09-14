package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;
import com.shakenokirimi12.uoa_app.services.idp.MarkerList;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;
import com.shakenokirimi12.uoa_app.services.idp.SeciossIdPClient;

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
    private static volatile String sesskey = "";
    private static volatile String userid = "";

    /**
     * ログインの要否をプロセスで 1 つ判断する (iOS の ensureSession / lastSessionVerifiedAt と同じ)。
     * 10 分以内に検証済みなら通信せず、それ以外は /my/ を 1 回見て、未認証のときだけ単一飛行で
     * ログインする。セッションの表現は sesskey。
     */
    private static final SessionCoordinator<String> coordinator = new SessionCoordinator<>();

    /** True once any instance has logged in during this process. */
    public boolean hasSession() {
        return sesskey != null && !sesskey.isEmpty();
    }

    /** ログアウト時に呼ぶ。cookie jar は NetworkClient.clearCookies() が別に消す。 */
    public static void clearSession() {
        sesskey = "";
        userid = "";
        coordinator.reset();
    }

    /**
     * ログイン手順そのものの失敗。呼び出し元へは文言をそのまま返す ("接続エラー: " を付けない)。
     * ID/PW 誤りもこれで投げる。接頭辞が付くと AuthErrors の判定に一致しなくなる。
     */
    private static final class LoginFailure extends Exception {
        LoginFailure(String message) { super(message); }
    }

    /** 取得の途中で未認証と分かったことを withSession に伝える。 */
    private static final class SessionExpiredException extends Exception {
        SessionExpiredException() { super("Moodle のセッションが切れました"); }
    }

    private interface SessionBody<T> { T run(String sesskey) throws Exception; }

    // ---- Login Method Switch (iOS の MoodleService.LoginMethod と同じ) ----

    /** 既定は legacy。`moodle_login_method` フラグを `idp` にすると新方式へ切り替えられる。 */
    public enum LoginMethod { LEGACY, IDP }

    public static LoginMethod resolveLoginMethod() {
        return "idp".equals(AppConfigService.getInstance().flagValue("moodle_login_method"))
                ? LoginMethod.IDP : LoginMethod.LEGACY;
    }

    // ---- IdP (SAML) Login ----

    private final SeciossIdPClient seciossClient = new SeciossIdPClient();

    /**
     * Moodle は SAML2 認証プラグイン (auth_saml2) で CampusSquare と同じ SECIOSS テナントに接続済み。
     * このURLで tenantlogin.cgi の sessid/back/tenant フォームまで到達することを iOS 側で確認済み
     * (2026-09-08)。テナントが同一なので CampusSquare 向けと同じ SeciossIdPClient をそのまま使う。
     */
    private static String samlEntryUrl() { return baseUrl() + "/auth/saml2/login.php"; }

    /**
     * Moodle コア標準のログアウト URL (login/logout.php) の有無で認証済み判定する。この URL パスは言語
     * 非依存。実接続時のマーカーが違った場合は `moodle_success_markers` フラグで上書きできる。
     */
    private static final String[] DEFAULT_SUCCESS_MARKERS = {"login/logout.php"};

    private static boolean containsAnySuccessMarker(String html) {
        return MarkerList.containsAnyIgnoreCase(html, MarkerList.parse(
                AppConfigService.getInstance().flagValue("moodle_success_markers"), DEFAULT_SUCCESS_MARKERS));
    }

    /**
     * OTP はメール (IMAP) 自動取得。login() の呼び出し元 (バックグラウンド同期) には OTP 入力を待ち受ける
     * UI が無いため、CampusSquare の loginViaIdPAutomatic と同じ方式にする。
     * 成功した cookie だけを共有 cookie jar へ注入する。順序が逆だと、ログイン失敗時にも IdP 側の cookie が
     * 残り、以降のリクエストに無関係なセッションが乗る。
     */
    private void loginViaIdP(String username, String password) throws Exception {
        String uid = username.trim();
        String pass = password.trim();
        SeciossIdPClient.Session session;
        try {
            session = seciossClient.login(samlEntryUrl(), uid, pass, () -> {
                if (!PreferenceManager.getInstance().isOtpAutoFetchEnabled()) {
                    throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
                }
                return new ImapOtpFetcher().fetchOtpCode(uid, pass, 30);
            });
        } catch (SeciossError e) {
            if (e.kind == SeciossError.Kind.INVALID_CREDENTIALS) {
                throw new Exception(INVALID_CREDENTIALS_MESSAGE);
            }
            throw new Exception(e.loginMessage(), e);
        }
        if (!containsAnySuccessMarker(session.finalHtml)) {
            throw new Exception("SAML経由でのログイン後、Moodleへの復帰が確認できませんでした");
        }
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(baseUrl());
        String host = base != null ? base.host() : "elms.u-aizu.ac.jp";
        boolean secure = base == null || base.isHttps();
        int dropped = NetworkClient.injectCookieHeader(host, secure, session.cookieHeader);
        if (dropped > 0) Log.w(TAG, "IdP session: " + dropped + " cookie(s) could not be imported");
    }

    /**
     * 手持ちのセッションを捨てて必ずログインし直す (資格情報の確認用: オンボーディング・設定変更)。
     * 画面の取得前には ensureLoggedIn を使う。
     */
    public void login(String username, String password, ServiceCallback<Boolean> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                coordinator.forceLogin(resolveLoginMethod(), () -> doLogin(username, password));
                postSuccess(callback, true);
            } catch (Exception e) {
                postLoginError(callback, e);
            }
        });
    }

    /**
     * 必要ならログインして、認証済みであることを確認する。10 分以内に検証済みなら通信しない。
     * userInitiated=false (SyncWorker) は直前の障害系失敗から 60 秒間は再ログインを試みない。
     */
    public void ensureLoggedIn(String username, String password, boolean userInitiated, ServiceCallback<Boolean> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                ensureSession(username, password, userInitiated);
                postSuccess(callback, true);
            } catch (Exception e) {
                postLoginError(callback, e);
            }
        });
    }

    private String ensureSession(String username, String password, boolean userInitiated) throws Exception {
        return coordinator.session(resolveLoginMethod(), userInitiated, null,
                key -> probeDashboardQuietly(), () -> doLogin(username, password));
    }

    /**
     * 取得処理を認証済みセッションで実行し、途中で未認証と分かった (SessionExpiredException) 場合だけ
     * 1 回、ログインし直して再実行する。資格情報は保存済みのものを使う (画面側は直前に
     * ensureLoggedIn を通しているので、通常ここでは通信しない)。
     */
    private <T> T withSession(SessionBody<T> body) throws Exception {
        PreferenceManager prefs = PreferenceManager.getInstance();
        String user = prefs.getUsername();
        String pass = prefs.getPassword();
        String key = ensureSession(user, pass, false);
        try {
            return body.run(key);
        } catch (SessionExpiredException e) {
            coordinator.invalidate(key);
            String fresh = ensureSession(user, pass, false);
            return body.run(fresh);
        }
    }

    private void postLoginError(ServiceCallback<Boolean> callback, Exception e) {
        Log.e(TAG, "Login exception", e);
        // IdP 経由の ID/PW 誤りは例外で上がってくる。接頭辞を付けると AuthErrors の判定に
        // 一致しなくなり、誤ったパスワードで自動同期が回り続ける。
        if (e instanceof LoginFailure || AuthErrors.isInvalidCredentials(e.getMessage())) {
            postError(callback, e.getMessage());
        } else {
            postError(callback, "接続エラー: " + e.getMessage());
        }
    }

    /** ログイン手順 (旧方式 / IdP) を実行し、/my/ から sesskey を取り出して返す。 */
    private String doLogin(String username, String password) throws Exception {
        OkHttpClient client = NetworkClient.getCookieClient();

        Log.d(TAG, "Starting login for: " + username);

        if (resolveLoginMethod() == LoginMethod.IDP) {
            // IdP 経由でセッション cookie を得たら、以降 (/my/ で sesskey 取得) は旧方式と共通。
            try {
                loginViaIdP(username, password);
            } catch (Exception e) {
                if (AuthErrors.isInvalidCredentials(e.getMessage())) throw new LoginFailure(e.getMessage());
                throw e;
            }
        } else {
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
                throw new LoginFailure(INVALID_CREDENTIALS_MESSAGE);
            }
        }

        String key = probeDashboard();
        Log.d(TAG, "Login successful");
        return key;
    }

    /**
     * GET /my/ で認証済みか確かめ、sesskey / userid を取り出して共有状態を更新する。
     * 未認証 (ログインページに戻される) なら LoginFailure。ログイン直後とセッション再確認の両方で使う。
     */
    private String probeDashboard() throws Exception {
        OkHttpClient client = NetworkClient.getCookieClient();
        Request getDashboard = new Request.Builder()
                .url(baseUrl() + "/my/")
                .header("User-Agent", NetworkClient.getUserAgent())
                .build();

        String dashHtml;
        String dashUrl;
        int status;
        try (Response resp = client.newCall(getDashboard).execute()) {
            dashHtml = resp.body().string();
            dashUrl = resp.request().url().toString();
            status = resp.code();
            Log.d(TAG, "Dashboard loaded, URL: " + dashUrl
                    + ", length: " + dashHtml.length());
        }

        // If /my/ redirects back to login, session is invalid
        if (status != 200
                || dashUrl.contains("login/index.php")
                || dashHtml.contains("id=\"login\"")
                || dashHtml.contains("id=\"loginerrormessage\"")) {
            Log.e(TAG, "Session invalid after login POST");
            throw new LoginFailure("セッション確立に失敗しました。再度お試しください。");
        }

        String key = extractMatch(dashHtml, "\"sesskey\":\"([^\"]+)\"");
        String uid = extractMatch(dashHtml, "\"userid\":\\s*\"?(\\d+)\"?");
        if (uid == null) {
            uid = extractMatch(dashHtml, "user/profile\\.php\\?id=(\\d+)");
        }

        Log.d(TAG, "sesskey: " + (key != null ? "found" : "NOT FOUND")
                + ", userid: " + uid);

        if (key == null || key.isEmpty()) {
            throw new LoginFailure("セッション情報の取得に失敗しました");
        }
        sesskey = key;
        userid = uid;
        return key;
    }

    /** 生きていれば /my/ から取り直した sesskey (以前と違えばそちらが正)、死んでいれば null。 */
    private String probeDashboardQuietly() {
        try {
            return probeDashboard();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Moodle の AJAX 応答が「未ログイン / sesskey 不一致」を示しているか。セッションが切れると
     * `[{"error":true,"exception":{"errorcode":"servicerequireslogin"|"invalidsesskey",...}}]` が返る。
     */
    private static boolean ajaxLooksUnauthenticated(String response) {
        return response.contains("\"errorcode\":\"invalidsesskey\"")
                || response.contains("requireslogin");
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

                String response = withSession(key -> ajaxCall(key,
                        "core_course_get_enrolled_courses_by_timeline_classification",
                        jsonBody));

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

                String response = withSession(key -> ajaxCall(key,
                        "core_calendar_get_action_events_by_timesort",
                        jsonBody));

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

    /** セッション切れの応答は SessionExpiredException にして withSession に 1 回だけやり直させる。 */
    private String ajaxCall(String key, String info, String jsonBody) throws Exception {
        OkHttpClient client = NetworkClient.getCookieClient();
        String url = baseUrl() + "/lib/ajax/service.php?sesskey=" + key + "&info=" + info;

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.getUserAgent())
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        String response;
        try (Response resp = client.newCall(request).execute()) {
            response = resp.body().string();
        }
        if (ajaxLooksUnauthenticated(response)) throw new SessionExpiredException();
        return response;
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
                postSuccess(callback, withSession(key -> fetchCourseContentsWithSession(key, courseId)));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private List<com.shakenokirimi12.uoa_app.data.models.CourseSection> fetchCourseContentsWithSession(String key, int courseId) throws Exception {
        OkHttpClient client = NetworkClient.getCookieClient();

        // Try AJAX approach first
        String ajaxUrl = baseUrl() + "/lib/ajax/service.php?sesskey=" + key
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
            if (ajaxLooksUnauthenticated(body)) throw new SessionExpiredException();
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
                    return sections;
                }
                // core_course_get_contents is not an AJAX-enabled function on this
                // Moodle ("ウェブサービスを利用できません"), so this branch is the normal
                // path. Same as iOS: fall back to scraping the course page.
                Log.d(TAG, "core_course_get_contents unavailable, scraping course page");
            }
        }
        return scrapeCourseContents(client, courseId);
    }

    /**
     * Port of the iOS fetchCourseContentsScraping: parse /course/view.php?id=N into sections
     * and activity links. Throws when the page cannot be loaded so the caller shows an error
     * instead of an empty (and therefore misleading) list.
     */
    private List<com.shakenokirimi12.uoa_app.data.models.CourseSection> scrapeCourseContents(OkHttpClient client, int courseId) throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/course/view.php?id=" + courseId)
                .header("User-Agent", NetworkClient.getUserAgent())
                .build();
        String html;
        try (Response resp = client.newCall(req).execute()) {
            // 未ログインだと login/index.php へ飛ばされる。1 回だけログインし直して取り直す。
            if (resp.request().url().toString().contains("login/index.php")) {
                throw new SessionExpiredException();
            }
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
