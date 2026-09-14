package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.FacilityUsage;
import com.shakenokirimi12.uoa_app.data.models.Grade;
import com.shakenokirimi12.uoa_app.services.idp.ImapOtpFetcher;
import com.shakenokirimi12.uoa_app.services.idp.MarkerList;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;
import com.shakenokirimi12.uoa_app.services.idp.SeciossIdPClient;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CampusSquareService {
    private static final String DEFAULT_BASE_URL = "https://csweb.u-aizu.ac.jp/campusweb";

    /** 大学側が URL を変えたときにフラグだけで追従できるようにする (iOS と同じ)。 */
    private static String baseUrl() {
        return AppConfigService.getInstance().stringFlag("campussquare_base_url", DEFAULT_BASE_URL);
    }

    /**
     * baseUrl() の scheme://host 部分。Origin ヘッダとルート相対リダイレクトの解決に使う。
     * ここを固定文字列にしておくと、フラグでホストを差し替えたときに
     * リダイレクト先だけ旧ホストへ戻ってセッションが分裂する。
     */
    /**
     * ID/PW 誤りの文言。iOS と同じ既定値で、`campussquare_auth_failure_markers` フラグ
     * (カンマ区切り) で審査なしに上書きできる。大学側が文言を変えたときの逃げ道。
     */
    private static final String DEFAULT_AUTH_FAILURE_MARKER = "ユーザ名またはパスワードの入力に誤りがあります";

    private static boolean containsAuthFailureMarker(String html) {
        String raw = AppConfigService.getInstance().flagValue("campussquare_auth_failure_markers");
        java.util.List<String> markers = new java.util.ArrayList<>();
        if (raw != null) {
            for (String m : raw.split(",")) if (!m.trim().isEmpty()) markers.add(m.trim());
        }
        if (markers.isEmpty()) markers.add(DEFAULT_AUTH_FAILURE_MARKER);
        String body = AuthErrors.withoutScripts(html);
        for (String m : markers) if (body.contains(m)) return true;
        return false;
    }

    private static String baseOrigin() {
        String url = baseUrl();
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return url;
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void login(String username, String password, ServiceCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                authenticatedCookieHeader(username, password);
                postSuccess(callback, true);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    // ---- Login Method Switch (iOS の CampusSquareService.LoginMethod と同じ) ----

    /**
     * 成績・カレンダー取得が旧方式 (生 POST) と新方式 (IdP 経由) のどちらでログインするかの明示的な
     * 切り替え。`campussquare_login_method` フラグを `idp` にすると審査を経ずに新方式へ切り替えられる
     * (問題があれば `legacy` に戻すだけで良い)。未設定・不正な値は安全側 (legacy) に倒す。
     */
    public enum LoginMethod { LEGACY, IDP }

    public static LoginMethod resolveLoginMethod() {
        return "idp".equals(AppConfigService.getInstance().flagValue("campussquare_login_method"))
                ? LoginMethod.IDP : LoginMethod.LEGACY;
    }

    /**
     * 旧方式は JSESSIONID 単体、新方式は SeciossIdPClient が集めた複数 cookie (AWS ALB のスティッキー
     * セッション cookie 等を含み得る) を Cookie ヘッダごと必要とする。この違いを fetchGrades /
     * fetchCalendarEvents から隠し、どちらもそのまま Cookie ヘッダに載せられる文字列を返す。
     */
    private String authenticatedCookieHeader(String username, String password) throws Exception {
        if (resolveLoginMethod() == LoginMethod.LEGACY) {
            return "JSESSIONID=" + doLogin(username, password);
        }
        // iOS は毎回ログインし直すが、Android は IdP 経由の OTP 往復 (最大 30 秒) を避けるため
        // キャッシュしたセッションが生きていればそれを使う。死んでいたら捨てて取り直す。
        String cached = PreferenceManager.getInstance().getCsIdpSessionCookieHeader();
        if (!cached.isEmpty()) {
            if (isSessionAlive(cached)) return cached;
            clearIdpSession();
        }
        return loginViaIdPAutomatic(null, username, password);
    }

    /**
     * IdP セッションの生存確認。旧方式の Step 4 と同じ page=main を見る。IdP 化後の CampusSquare で
     * このページ・文言がどうなるかは 2026-09-14 時点で未確認なので、URL は `campussquare_session_check_url`
     * フラグで差し替えられるようにしておく。
     */
    private boolean isSessionAlive(String cookieHeader) {
        String url = AppConfigService.getInstance().stringFlag("campussquare_session_check_url",
                baseUrl() + "/campusportal.do?page=main");
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.getUserAgent())
                .header("Cookie", cookieHeader)
                .build();
        try (Response resp = NetworkClient.getNoCookieClient().newCall(req).execute()) {
            String html = resp.body() != null ? resp.body().string() : "";
            return resp.isSuccessful() && containsAnySuccessMarker(html);
        } catch (IOException e) {
            return false;
        }
    }

    /** セッション切れ等で無効化する。次回同期時に再ログインが必要と判断させる。 */
    public static void clearIdpSession() {
        PreferenceManager.getInstance().setCsIdpSessionCookieHeader(null);
    }

    // ---- IdP (SECIOSS) login ----

    private final SeciossIdPClient seciossClient = new SeciossIdPClient();

    /**
     * SeciossIdPClient 自体は SECIOSS 側の成功マーカーしか見ていない。CampusSquare へ実際に戻って
     * こられたかは別に確認する。CampusSquare 側の成功マーカー文言は実接続で判明するまで未確認なので、
     * `campussquare_success_markers` フラグ (カンマ区切り、既定は "ログアウト,Logout") で追従できる。
     */
    private static final String[] DEFAULT_SUCCESS_MARKERS = {"ログアウト", "Logout"};

    private static boolean containsAnySuccessMarker(String html) {
        return MarkerList.containsAnyIgnoreCase(html, MarkerList.parse(
                AppConfigService.getInstance().flagValue("campussquare_success_markers"), DEFAULT_SUCCESS_MARKERS));
    }

    /**
     * IdP (SECIOSS) 経由でログインし、CampusSquare のセッション Cookie ヘッダを返す (キャッシュにも保存)。
     * entryUrl が null なら resolveCampusSquareEntryUrl() の結果を使う。otpCode は OTP が要求された
     * 場合にのみ呼ばれる (UI 入力待ちや IMAP 自動取得)。
     * ID/PW 誤りは AuthErrors.INVALID_CREDENTIALS_MESSAGE の Exception に変換して投げる。既存の
     * onError 側 (markInvalidIfCredentialsError) がそのまま自動同期を止められるようにするため。
     */
    public String loginViaIdP(String entryUrl, String username, String password,
                              SeciossIdPClient.OtpCodeProvider otpCode) throws Exception {
        String uid = username.trim();
        String pass = password.trim();
        String resolvedEntry = entryUrl != null ? entryUrl : resolveCampusSquareEntryUrl();
        SeciossIdPClient.Session session;
        try {
            session = seciossClient.login(resolvedEntry, uid, pass, otpCode);
        } catch (SeciossError e) {
            if (e.kind == SeciossError.Kind.INVALID_CREDENTIALS) {
                throw new Exception(AuthErrors.INVALID_CREDENTIALS_MESSAGE);
            }
            throw new Exception(e.loginMessage(), e);
        }
        // マーカーだけだと IdP 側のページ (エラー画面にも「ログアウト」がある) を成功と誤認する。
        // 復帰先が CampusSquare 自身のホストであることも要求する。
        okhttp3.HttpUrl finalUrl = okhttp3.HttpUrl.parse(session.finalUrl);
        okhttp3.HttpUrl csUrl = okhttp3.HttpUrl.parse(baseUrl());
        String finalHost = finalUrl != null ? finalUrl.host().toLowerCase() : "";
        String csHost = csUrl != null ? csUrl.host().toLowerCase() : "csweb.u-aizu.ac.jp";
        if (!finalHost.equals(csHost)) {
            throw new Exception(new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                    "CampusSquareへ戻れていない(final=" + session.finalUrl + ")").loginMessage());
        }
        if (!containsAnySuccessMarker(session.finalHtml)) {
            throw new Exception(new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                    "CampusSquareへの復帰後、成功マーカーが見つからない").loginMessage());
        }
        PreferenceManager.getInstance().setCsIdpSessionCookieHeader(session.cookieHeader);
        return session.cookieHeader;
    }

    /**
     * OTP をメール (stdmsv1.u-aizu.ac.jp、AINS ID/PW 共通) から自動取得してログインする。人手を介さず
     * バックグラウンドでも完結できるが、メール到着が遅いと最大 30 秒待つ。
     */
    public String loginViaIdPAutomatic(String entryUrl, String username, String password) throws Exception {
        return loginViaIdP(entryUrl, username, password, () -> {
            // メール自動読み取りはユーザーが IdP チュートリアルで明示的に許可した場合のみ行う。
            if (!PreferenceManager.getInstance().isOtpAutoFetchEnabled()) {
                throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
            }
            return new ImapOtpFetcher().fetchOtpCode(username.trim(), password.trim(), 30);
        });
    }

    /**
     * CampusSquare 側の未認証入口の形は実接続まで不明。他大学の SECIOSS 導入事例で観測できた 2 パターンを
     * 両方実装し、`campussquare_entry_pattern` フラグで切り替える (iOS と同じ)。
     * - direct (既定): entryUrl への初回アクセスで即座にログインフォーム (sessid/back/tenant) が現れる想定。
     * - selection_page: entryUrl は「SSO ログイン」等を選ばせる画面で、該当リンクまたは GET フォームを辿る。
     *   POST フォームはフィールド構成を決め打ちしたくないため対象外。
     * 想定外の画面だった場合は下流の SeciossIdPClient が MISSING_FIELD/UNRECOGNIZED_STATE で明示的に失敗する。
     */
    private static final String[] DEFAULT_ENTRY_LINK_HINTS = {"SSO", "シングルサインオン", "統合認証", "SECIOSS", "証明書"};

    private String resolveCampusSquareEntryUrl() {
        AppConfigService flags = AppConfigService.getInstance();
        String base = flags.flagValue("campussquare_entry_url");
        if (base == null || base.trim().isEmpty()) base = baseUrl() + "/campusportal.do?locale=ja_JP";
        if (!"selection_page".equals(flags.flagValue("campussquare_entry_pattern"))) return base;

        java.util.List<String> hints = MarkerList.parse(flags.flagValue("campussquare_entry_link_hints"), DEFAULT_ENTRY_LINK_HINTS);
        Request req = new Request.Builder().url(base).header("User-Agent", NetworkClient.getUserAgent()).build();
        try (Response resp = NetworkClient.getNoCookieClient().newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return base;
            String html = resp.body().string();
            String target = findMatchingLink(html, hints, base);
            if (target == null) target = findMatchingGetFormSubmission(html, hints, base);
            if (target != null) return target;
        } catch (IOException e) {
            // 取得に失敗したら base のまま返す。下流が sessid 等を見つけられず MISSING_FIELD として明示的に失敗する。
        }
        return base;
    }

    /** ヒント語との一致は大文字小文字を区別しない (実際の文言の表記揺れに強くするため)。 */
    static String findMatchingLink(String html, java.util.List<String> hints, String baseUrl) {
        Matcher m = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]*)</a>").matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String text = m.group(2);
            if (MarkerList.containsAnyIgnoreCase(text, hints) || MarkerList.containsAnyIgnoreCase(href, hints)) {
                return resolveAgainst(baseUrl, href);
            }
        }
        return null;
    }

    /** <form method="get"> のうち、フォーム内にヒント語を含むものを action + hidden input から 1 本の URL にする。 */
    static String findMatchingGetFormSubmission(String html, java.util.List<String> hints, String baseUrl) {
        Matcher form = Pattern.compile("<form\\b([^>]*)>(.*?)</form>", Pattern.DOTALL).matcher(html);
        while (form.find()) {
            String attrs = form.group(1);
            String body = form.group(2);
            if (!MarkerList.containsAnyIgnoreCase(body, hints)) continue;
            String action = extractMatchIgnoreCase(attrs, "action=\"([^\"]+)\"");
            if (action == null) continue;
            String method = extractMatchIgnoreCase(attrs, "method=\"([^\"]+)\"");
            if (method != null && !"get".equalsIgnoreCase(method)) continue;

            StringBuilder query = new StringBuilder();
            Matcher input = Pattern.compile("<input[^>]*>").matcher(body);
            while (input.find()) {
                String tag = input.group();
                String name = extractMatchIgnoreCase(tag, "name=\"([^\"]+)\"");
                if (name == null) continue;
                String value = extractMatchIgnoreCase(tag, "value=\"([^\"]*)\"");
                if (query.length() > 0) query.append('&');
                // iOS と同じく空白は %20 (URLEncoder は "+" にするので使わない)。
                query.append(name).append('=').append(SeciossIdPClient.urlEncode(value != null ? value : ""));
            }
            String actionUrl = resolveAgainst(baseUrl, action);
            if (query.length() == 0) return actionUrl;
            return actionUrl + (actionUrl.contains("?") ? "&" : "?") + query;
        }
        return null;
    }

    private static String extractMatchIgnoreCase(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String resolveAgainst(String baseUrl, String relative) {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(baseUrl);
        okhttp3.HttpUrl resolved = base != null ? base.resolve(relative) : null;
        return resolved != null ? resolved.toString() : relative;
    }

    private String doLogin(String username, String password) throws Exception {
        OkHttpClient noRedirect = NetworkClient.getNoRedirectClient();

        // Step 1: GET portal page for rwfHash + initial JSESSIONID
        Request getPortal = new Request.Builder()
                .url(baseUrl() + "/campusportal.do?locale=ja_JP")
                .header("User-Agent", NetworkClient.getUserAgent())
                .build();

        String portalHtml;
        String initialSid;
        try (Response resp = noRedirect.newCall(getPortal).execute()) {
            // Follow redirects manually for initial page
            Response finalResp = followRedirectsManually(resp);
            portalHtml = finalResp.body().string();
            initialSid = extractSessionId(finalResp);
            finalResp.close();
        }

        String rwfHash = extractMatch(portalHtml, "'rwfHash'\\s*:\\s*'([a-f0-9]+)'");
        if (rwfHash == null || initialSid == null || initialSid.isEmpty()) {
            throw new Exception("ポータルページの読み込みに失敗しました");
        }

        // Step 2: POST login
        String body = "wfId=nwf_PTW0000002_login" +
                "&userName=" + urlEncode(username.trim()) +
                "&password=" + urlEncode(password.trim()) +
                "&locale=ja_JP&undefined=&action=rwf&tabId=home&page=&rwfHash=" + rwfHash;

        Request postLogin = new Request.Builder()
                .url(baseUrl() + "/campusportal.do")
                .header("User-Agent", NetworkClient.getUserAgent())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "JSESSIONID=" + initialSid)
                .header("Referer", baseUrl() + "/campusportal.do?locale=ja_JP")
                .header("Origin", baseOrigin())
                .post(RequestBody.create(body, FORM))
                .build();

        String authSid;
        String locationHeader;
        try (Response resp = noRedirect.newCall(postLogin).execute()) {
            String newSid = extractSessionId(resp);
            authSid = (newSid != null && !newSid.isEmpty()) ? newSid : initialSid;
            locationHeader = resp.header("Location");
            // ID/PW 誤りはログイン POST の応答本文にだけ現れる (page=main には出ない。iOS で実測)。
            // 見逃すと Step 4 の「ログインに失敗」に紛れ、誤ったパスワードで自動同期が回り続ける。
            String loginBody = resp.body() != null ? resp.body().string() : "";
            if (containsAuthFailureMarker(loginBody)) {
                throw new Exception(AuthErrors.INVALID_CREDENTIALS_MESSAGE);
            }
        }

        // Step 3: Follow redirect if present
        if (locationHeader != null && !locationHeader.isEmpty()) {
            String redirectUrl = resolveUrl(locationHeader);
            Request followRedirect = new Request.Builder()
                    .url(redirectUrl)
                    .header("User-Agent", NetworkClient.getUserAgent())
                    .header("Cookie", "JSESSIONID=" + authSid)
                    .header("Referer", baseUrl() + "/campusportal.do")
                    .build();
            try (Response resp = NetworkClient.getNoCookieClient().newCall(followRedirect).execute()) {
                resp.body().string();
            }
        }

        // Step 4: Verify login
        Request verifyReq = new Request.Builder()
                .url(baseUrl() + "/campusportal.do?page=main")
                .header("User-Agent", NetworkClient.getUserAgent())
                .header("Cookie", "JSESSIONID=" + authSid)
                .header("Referer", baseUrl() + "/campusportal.do")
                .build();

        try (Response resp = NetworkClient.getNoCookieClient().newCall(verifyReq).execute()) {
            String verifyHtml = resp.body().string();
            if (!verifyHtml.contains("ログアウト") && !verifyHtml.contains("Logout")) {
                throw new Exception("CampusSquare ログインに失敗しました");
            }
        }

        return authSid;
    }

    public void fetchGrades(String username, String password, ServiceCallback<List<Grade>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("campussquare_grades_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                String cookie = authenticatedCookieHeader(username, password);

                // Navigate to grades tab
                Request tabReq = new Request.Builder()
                        .url(baseUrl() + "/campusportal.do?page=main&tabId=si")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", cookie)
                        .header("Referer", baseUrl() + "/campusportal.do?page=main")
                        .build();
                try (Response r = NetworkClient.getNoCookieClient().newCall(tabReq).execute()) {
                    r.body().string();
                }
                Thread.sleep(300);

                // Start grade flow
                OkHttpClient noRedirect = NetworkClient.getNoRedirectClient();
                Request flowReq = new Request.Builder()
                        .url(baseUrl() + "/campussquare.do?_flowId=SIW0001200-flow")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", cookie)
                        .header("sec-fetch-dest", "iframe")
                        .header("Referer", baseUrl() + "/campusportal.do?page=main&tabId=si")
                        .build();

                String flowHtml;
                String flowKey = null;
                try (Response resp = noRedirect.newCall(flowReq).execute()) {
                    String location = resp.header("Location");
                    flowKey = extractFlowKey(location);
                    if (flowKey != null && location != null) {
                        String redirectUrl = resolveUrl(location);
                        Request follow = new Request.Builder()
                                .url(redirectUrl)
                                .header("User-Agent", NetworkClient.getUserAgent())
                                .header("Cookie", cookie)
                                .build();
                        try (Response r2 = NetworkClient.getNoCookieClient().newCall(follow).execute()) {
                            flowHtml = r2.body().string();
                        }
                    } else {
                        flowHtml = resp.body().string();
                    }
                }

                if (flowKey == null) {
                    flowKey = extractMatch(flowHtml, "_flowExecutionKey\"\\s*value=\"([a-zA-Z0-9_-]+)\"");
                }

                if (flowKey == null) {
                    postError(callback, "成績ページのフローキー取得に失敗しました");
                    return;
                }

                // POST to display grades
                String postBody = "_flowExecutionKey=" + flowKey + "&_eventId=display";
                Request gradePost = new Request.Builder()
                        .url(baseUrl() + "/campussquare.do")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Referer", baseUrl() + "/campussquare.do?_flowId=SIW0001200-flow&_flowExecutionKey=" + flowKey)
                        .post(RequestBody.create(postBody, FORM))
                        .build();

                String gradesHtml;
                try (Response resp = NetworkClient.getNoCookieClient().newCall(gradePost).execute()) {
                    gradesHtml = resp.body().string();
                }

                List<Grade> grades = parseGrades(gradesHtml);
                postSuccess(callback, grades);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    public void fetchCalendarEvents(String username, String password,
                                    ServiceCallback<List<CalendarEvent>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("campussquare_calendar_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                String cookie = authenticatedCookieHeader(username, password);

                // Navigate to calendar tab
                Request tabReq = new Request.Builder()
                        .url(baseUrl() + "/campusportal.do?page=main&tabId=po")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", cookie)
                        .header("Referer", baseUrl() + "/campusportal.do?page=main")
                        .build();
                try (Response r = NetworkClient.getNoCookieClient().newCall(tabReq).execute()) {
                    r.body().string();
                }
                Thread.sleep(300);

                // Get calendar URL
                Request calReq = new Request.Builder()
                        .url(baseUrl() + "/campussquare.do?_flowId=POW2401000-flow")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .header("Cookie", cookie)
                        .header("sec-fetch-dest", "iframe")
                        .header("Referer", baseUrl() + "/campusportal.do?page=main&tabId=po")
                        .build();

                String calHtml;
                try (Response resp = NetworkClient.getNoCookieClient().newCall(calReq).execute()) {
                    calHtml = resp.body().string();
                }

                String icsUrl = extractMatch(calHtml, "id=\"calendarNm\"[^>]*value=\"([^\"]+)\"");
                if (icsUrl == null || icsUrl.isEmpty()) {
                    postError(callback, "カレンダーURLの取得に失敗しました");
                    return;
                }

                // Fetch ICS file
                Request icsReq = new Request.Builder()
                        .url(icsUrl)
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .build();

                String icsContent;
                try (Response resp = NetworkClient.getNoCookieClient().newCall(icsReq).execute()) {
                    icsContent = resp.body().string();
                }

                List<CalendarEvent> events = parseICS(icsContent);
                postSuccess(callback, events);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private List<Grade> parseGrades(String html) {
        List<Grade> grades = new ArrayList<>();
        Matcher rowMatcher = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(html);

        while (rowMatcher.find()) {
            String row = rowMatcher.group(1);
            Matcher cellMatcher = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(row);
            List<String> cells = new ArrayList<>();
            while (cellMatcher.find()) {
                cells.add(stripTags(cellMatcher.group(1)).trim());
            }
            if (cells.size() >= 8) {
                String year = cells.get(1);
                String semester = cells.get(2);
                String subjectCode = cells.get(3);
                String subject = cells.get(4);
                String instructor = cells.get(5);
                String score = cells.get(6);
                String gradeStr = cells.get(7);
                if (!subject.isEmpty() && (!score.isEmpty() || !gradeStr.isEmpty())) {
                    Grade g = new Grade();
                    g.setSubjectCode(subjectCode);
                    g.setCourseName(subject);
                    g.setInstructor(instructor);
                    g.setScore(score);
                    g.setGrade(gradeStr.isEmpty() ? "履修中" : gradeStr);
                    g.setYear(year);
                    g.setSemester(semester);
                    grades.add(g);
                }
            }
        }
        return grades;
    }

    private List<CalendarEvent> parseICS(String ics) {
        List<CalendarEvent> events = new ArrayList<>();
        String[] blocks = ics.split("BEGIN:VEVENT");

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i].split("END:VEVENT")[0];
            String summary = extractIcsField(block, "SUMMARY");
            String location = extractIcsField(block, "LOCATION");
            String dtStartRaw = extractIcsField(block, "DTSTART");
            String dtEndRaw = extractIcsField(block, "DTEND");

            if (summary == null || dtStartRaw == null || dtEndRaw == null) continue;

            summary = summary.replace("\\n", "\n").replace("\\,", ",");
            if (location != null) location = location.replace("\\n", "\n").replace("\\,", ",");

            Date dtStart = parseIcsDate(dtStartRaw);
            Date dtEnd = parseIcsDate(dtEndRaw);

            if (dtStart != null && dtEnd != null) {
                events.add(new CalendarEvent(summary, location, dtStart, dtEnd));
            }
        }
        return events;
    }

    private String extractIcsField(String block, String field) {
        Pattern p = Pattern.compile("^" + field + "[;:](.*)$", Pattern.MULTILINE);
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private Date parseIcsDate(String raw) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);

        if (raw.contains("TZID=Asia/Tokyo:")) {
            String clean = raw.replaceAll("TZID=Asia/Tokyo:", "");
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
            try { return sdf.parse(clean); } catch (Exception e) { return null; }
        } else if (raw.endsWith("Z")) {
            String clean = raw.substring(0, raw.length() - 1);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            try { return sdf.parse(clean); } catch (Exception e) { return null; }
        } else {
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
            try { return sdf.parse(raw); } catch (Exception e) { return null; }
        }
    }

    private Response followRedirectsManually(Response resp) throws IOException {
        while (resp.isRedirect()) {
            String location = resp.header("Location");
            if (location == null) break;
            String newSid = extractSessionId(resp);
            String url = resolveUrl(location);
            resp.close();

            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkClient.getUserAgent());
            if (newSid != null && !newSid.isEmpty()) {
                builder.header("Cookie", "JSESSIONID=" + newSid);
            }
            resp = NetworkClient.getNoRedirectClient().newCall(builder.build()).execute();
        }
        return resp;
    }

    private static String extractSessionId(Response resp) {
        String setCookie = resp.header("Set-Cookie");
        if (setCookie == null) return null;
        Matcher m = Pattern.compile("JSESSIONID=([A-Z0-9]+)").matcher(setCookie);
        return m.find() ? m.group(1) : null;
    }

    private static String extractFlowKey(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("_flowExecutionKey=([a-zA-Z0-9_-]+)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String resolveUrl(String location) {
        if (location.startsWith("http")) return location;
        if (location.startsWith("/")) return baseOrigin() + location;
        return baseUrl() + "/" + location;
    }

    private static String extractMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    public void fetchFacilityUsage(String dateStr, ServiceCallback<List<FacilityUsage>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("campussquare_facility_usage_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                OkHttpClient noRedirect = NetworkClient.getNoRedirectClient();
                OkHttpClient client = NetworkClient.getNoCookieClient();

                // GET initial flow page (no login required)
                Request flowReq = new Request.Builder()
                        .url(baseUrl() + "/campussquare.do?_flowId=KHW0001310-flow")
                        .header("User-Agent", NetworkClient.getUserAgent())
                        .build();

                String html;
                String sid = null;
                try (Response resp = noRedirect.newCall(flowReq).execute()) {
                    String newSid = extractSessionId(resp);
                    if (newSid != null) sid = newSid;
                    String location = resp.header("Location");
                    if (location != null) {
                        String redirectUrl = resolveUrl(location);
                        Request.Builder rb = new Request.Builder()
                                .url(redirectUrl)
                                .header("User-Agent", NetworkClient.getUserAgent());
                        if (sid != null) rb.header("Cookie", "JSESSIONID=" + sid);
                        try (Response r2 = noRedirect.newCall(rb.build()).execute()) {
                            String ns = extractSessionId(r2);
                            if (ns != null) sid = ns;
                            String loc2 = r2.header("Location");
                            if (loc2 != null) {
                                Request.Builder rb2 = new Request.Builder()
                                        .url(resolveUrl(loc2))
                                        .header("User-Agent", NetworkClient.getUserAgent());
                                if (sid != null) rb2.header("Cookie", "JSESSIONID=" + sid);
                                try (Response r3 = client.newCall(rb2.build()).execute()) {
                                    html = r3.body().string();
                                }
                            } else {
                                html = r2.body().string();
                            }
                        }
                    } else {
                        html = resp.body().string();
                    }
                }

                // If date is specified, navigate to that date
                if (dateStr != null && !dateStr.isEmpty()) {
                    String flowKey = extractMatch(html,
                            "_flowExecutionKey\"\\s*value=\"([^\"]+)\"");
                    if (flowKey == null) {
                        flowKey = extractMatch(html, "_flowExecutionKey=([a-zA-Z0-9_-]+)");
                    }
                    if (flowKey != null && sid != null) {
                        String dateUrl = baseUrl() + "/campussquare.do?_flowExecutionKey="
                                + flowKey + "&_eventId=show&displayDate=" + dateStr;
                        Request dateReq = new Request.Builder()
                                .url(dateUrl)
                                .header("User-Agent", NetworkClient.getUserAgent())
                                .header("Cookie", "JSESSIONID=" + sid)
                                .build();
                        try (Response resp = client.newCall(dateReq).execute()) {
                            html = resp.body().string();
                        }
                    }
                }

                List<FacilityUsage> facilities = parseFacilityUsage(html);
                postSuccess(callback, facilities);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private List<FacilityUsage> parseFacilityUsage(String html) {
        List<FacilityUsage> facilities = new ArrayList<>();
        Pattern tdPattern = Pattern.compile("<td([^>]*)>(.*?)</td>", Pattern.DOTALL);
        Matcher rowMatcher = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(html);

        while (rowMatcher.find()) {
            String row = rowMatcher.group(1);
            if (!row.contains("kyuko-shi-shisetsunm")) continue;

            Matcher tdMatcher = tdPattern.matcher(row);
            String facilityName = "";
            int currentSlot = 0;
            int availableStart = 0; // 6:00 = slot 0

            List<FacilityUsage.ScheduleItem> schedule = new ArrayList<>();

            while (tdMatcher.find()) {
                String attrs = tdMatcher.group(1);
                String inner = tdMatcher.group(2).trim()
                        .replaceAll("\n", "")
                        .replaceAll("<br\\s*/?>", "");

                if (attrs.contains("kyuko-shi-shisetsunm")) {
                    if (attrs.contains("nowrap")) {
                        facilityName = stripTags(inner).trim();
                    }
                    // rowspan category cells (講義室, 演習室) - skip
                    continue;
                }

                if (currentSlot >= 108) continue;

                Matcher csMatcher = Pattern.compile("colspan=[\"']?(\\d+)[\"']?").matcher(attrs);
                int colspan = csMatcher.find() ? Integer.parseInt(csMatcher.group(1)) : 1;
                int durationMins = colspan * 10;
                int slotStartMins = 360 + currentSlot * 10;

                String cleanedInner = stripTags(inner).trim();
                boolean isBooked = !cleanedInner.isEmpty();

                if (isBooked) {
                    int availStartMins = 360 + availableStart * 10;
                    if (availStartMins < slotStartMins) {
                        schedule.add(new FacilityUsage.ScheduleItem(
                                availStartMins, slotStartMins, true, null));
                    }
                    schedule.add(new FacilityUsage.ScheduleItem(
                            slotStartMins, slotStartMins + durationMins, false, cleanedInner));
                    availableStart = currentSlot + colspan;
                }

                currentSlot += colspan;
            }

            // Trailing available block
            int endOfDayMins = 360 + 108 * 10; // 24:00
            int availStartMins = 360 + availableStart * 10;
            if (availStartMins < endOfDayMins) {
                schedule.add(new FacilityUsage.ScheduleItem(
                        availStartMins, endOfDayMins, true, null));
            }

            if (!facilityName.isEmpty()) {
                FacilityUsage facility = new FacilityUsage(facilityName);
                for (FacilityUsage.ScheduleItem item : schedule) {
                    facility.addScheduleItem(item);
                }
                facility.computeCurrentStatus();
                facilities.add(facility);
            }
        }
        return facilities;
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ");
    }

    private <T> void postSuccess(ServiceCallback<T> cb, T result) {
        mainHandler.post(() -> cb.onSuccess(result));
    }

    private <T> void postError(ServiceCallback<T> cb, String msg) {
        mainHandler.post(() -> cb.onError(msg != null ? msg : "不明なエラー"));
    }
}
