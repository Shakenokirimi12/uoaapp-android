package com.shakenokirimi12.uoa_app.services;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.OtpPromptCoordinator;
import com.shakenokirimi12.uoa_app.services.idp.MarkerList;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;
import com.shakenokirimi12.uoa_app.services.idp.SeciossIdPClient;

import java.io.IOException;
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
    public static String invalidCredentialsMessage() { return AuthErrors.invalidCredentialsMessage(); }

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
     * 共有 cookie jar にある Moodle のセッションの持ち主 (ユーザー名)。null は不明。ログインの開始と保存分の復元で
     * 決まる。アカウント A の生存確認が走っている間に B のログインが終わると、A の確認は jar にある B の cookie を
     * 読む。それを A のものとして保存しないために、保存の前に突き合わせる。
     */
    private static volatile String jarOwner = null;

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

    /**
     * 利用者がやり直したとき (引っ張って更新・再試行ボタン) に、その取得を同じインスタンスで呼ぶ直前に呼ぶ。
     * 直前のログインの失敗による待ち (SessionCoordinator.LOGIN_BACKOFF_MS) を解き、その取得で新しくログインさせる。
     * 画面を開いただけの取得では呼ばない (失敗の直後に OTP を自動で求め直さないため)。iOS の allowLoginRetry と同じ。
     *
     * 解くのは executor の上で、利用者の取得の直前の順番にする。すぐに解くと、同じ executor で先に走っている
     * 自動の取得が後から失敗して待ちを付け直し、利用者の取得がその失敗を受け取る (自動の同期の最中に引っ張ったとき)。
     */
    public void allowLoginRetry() {
        executor.execute(coordinator::clearBackoff);
    }

    /** ログアウト時に呼ぶ。cookie jar は NetworkClient.clearCookies() が別に消す。 */
    public static void clearSession() {
        sesskey = "";
        userid = "";
        jarOwner = null;
        coordinator.reset();
        PreferenceManager.getInstance().setMoodleSessionCookies(null);
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
        SessionExpiredException() { super(AppStrings.get(R.string.moodle_session_expired)); }
    }

    private interface SessionBody<T> { T run(String sesskey) throws Exception; }

    // ---- Login Method Switch (iOS の MoodleService.LoginMethod と同じ) ----

    /**
     * 既定は idp。大学が 2026-09-15 に学務系の認証を SAML へ切り替えたので、旧方式は素の
     * login/index.php では通らない。フラグ (`moodle_login_method`) を `legacy` にすれば戻せる。
     * コード側の既定を legacy のままにすると、フラグを取得する前の起動直後の同期が必ず旧方式で走る。
     */
    public enum LoginMethod { LEGACY, IDP }

    public static LoginMethod resolveLoginMethod() {
        return "legacy".equals(AppConfigService.getInstance().flagValue("moodle_login_method"))
                ? LoginMethod.LEGACY : LoginMethod.IDP;
    }

    // ---- IdP (SAML) Login ----

    private final SeciossIdPClient seciossClient = new SeciossIdPClient();

    /**
     * Moodle は SAML2 認証プラグイン (auth_saml2) で CampusSquare と同じ SECIOSS テナントに接続済み。
     * このURLで tenantlogin.cgi の sessid/back/tenant フォームまで到達することを iOS 側で確認済み
     * (2026-09-08)。テナントが同一なので CampusSquare 向けと同じ SeciossIdPClient をそのまま使う。
     */
    private static String samlEntryUrl() {
        // 2026-09-15 実測: 303 で SECIOSS へ飛び、RelayState は elms に戻る。変わったときのために
        // `moodle_entry_url` フラグで差し替えられる (CampusSquare と同じ考え方)。
        return AppConfigService.getInstance().stringFlag("moodle_entry_url", baseUrl() + "/auth/saml2/login.php");
    }

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
     * OTP の取得は CampusSquare と同じく OtpPromptCoordinator に任せる (メール方式ならメールから自動取得、
     * アプリが前面にいれば入力ダイアログ、バックグラウンド同期なら INTERACTIVE_LOGIN_REQUIRED)。
     * 成功した cookie だけを共有 cookie jar へ注入する。順序が逆だと、ログイン失敗時にも IdP 側の cookie が
     * 残り、以降のリクエストに無関係なセッションが乗る。
     *
     * @param verifyPassword 資格情報の確認 (login)。保存している SSO を使わず、必ずパスワードを送る。
     */
    private void loginViaIdP(String username, String password, AuthorizationContext context, boolean verifyPassword) throws Exception {
        String uid = username.trim();
        String pass = password.trim();
        // ここに来るのは SessionCoordinator が新規ログインを許した場合だけ。BACKGROUND なら、BackgroundLoginPolicy が
        // 方式ごとの条件 (認証アプリは止めていない鍵、メールは 6 時間の枠) を確かめてある。
        com.shakenokirimi12.uoa_app.services.idp.OtpProvider otp =
                com.shakenokirimi12.uoa_app.services.idp.OtpProviders.forLogin(uid, pass, context);
        // バックグラウンドでは、判定 (BackgroundLoginPolicy) のあとで条件が崩れていたら IdP に触れずに止める
        // (鍵を止めた・控えが消えた等)。メールの送信の枠は送る直前にもう一度取る (OtpProvider.beforeMailDispatch)。
        // 下の catch で文言に包むと SyncWorker が「ログインが必要」と見分けられないので、try の外で投げる。
        if (!context.allowsNewLogin() && !otp.canRunUnattended()) {
            throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED);
        }
        SeciossIdPClient.Session session;
        try {
            session = verifyPassword
                    ? seciossClient.loginVerifyingPassword(samlEntryUrl(), uid, pass, otp)
                    : seciossClient.login(samlEntryUrl(), uid, pass, otp);
        } catch (SeciossError e) {
            // OTP 未設定はここでしか分からない。設定画面を探させず、その場で登録画面を開く。
            com.shakenokirimi12.uoa_app.services.idp.OtpRegistrationLauncher.launchIfNeeded(e);
            if (e.kind == SeciossError.Kind.INVALID_CREDENTIALS) {
                throw new Exception(invalidCredentialsMessage());
            }
            throw new Exception(e.loginMessage(), e);
        }
        // 2026-09-15 実測の反省点: (1) メンテナンスページにも "logout" が含まれるのでマーカーより先に見る、
        // (2) IdP 側のページで止まっても復帰先ホストで弾く、(3) login/index.php へ戻された = SP 側で
        // このユーザーが未開放。
        if (SeciossIdPClient.isMaintenancePage(session.finalStatus, session.finalHtml)) {
            throw new SeciossError(SeciossError.Kind.MAINTENANCE,
                    SeciossIdPClient.visibleExcerpt(session.finalHtml, 200)).asLoginException();
        }
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(baseUrl());
        String host = base != null ? base.host() : "elms.u-aizu.ac.jp";
        okhttp3.HttpUrl finalUrl = okhttp3.HttpUrl.parse(session.finalUrl);
        String finalHost = finalUrl != null ? finalUrl.host().toLowerCase() : "";
        if (!finalHost.equals(host.toLowerCase())) {
            throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                    AppStrings.get(R.string.moodle_error_not_returned, session.finalUrl)).asLoginException();
        }
        if (session.finalUrl.contains("login/index.php")) {
            throw new SeciossError(SeciossError.Kind.ACCESS_DENIED, "moodle_login_page").asLoginException();
        }
        if (!containsAnySuccessMarker(session.finalHtml)) {
            throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                    AppStrings.get(R.string.moodle_error_no_success_marker, session.finalUrl) + " "
                            + SeciossIdPClient.visibleExcerpt(session.finalHtml, 200)).asLoginException();
        }
        NetworkClient.addCookies(session.spCookies);
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
                coordinator.forceLogin(sessionKey(username), () -> doLogin(username, password, AuthorizationContext.INTERACTIVE, true),
                        unattended(username, password));
                postSuccess(callback, true);
            } catch (Exception e) {
                postLoginError(callback, e);
            }
        });
    }

    /**
     * 必要ならログインして、認証済みであることを確認する。10 分以内に検証済みなら通信しない。
     * BACKGROUND (SyncWorker) は、BackgroundLoginPolicy が許すとき (認証アプリ方式でこのアプリが鍵を持つ等)
     * だけ新規ログインする。許さなければ、保存済みセッションが死んでいたら諦める。
     */
    public void ensureLoggedIn(String username, String password, AuthorizationContext context, ServiceCallback<Boolean> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        executor.execute(() -> {
            try {
                ensureSession(username, password, context);
                postSuccess(callback, true);
            } catch (Exception e) {
                postLoginError(callback, e);
            }
        });
    }

    private String ensureSession(String username, String password, AuthorizationContext context) throws Exception {
        if (AccountSwitch.isStale(username)) throw new AccountChangedException();
        // プロセス起動直後は cookie jar が空。前回の Moodle セッションを持ち越していれば、まずそれを
        // 入れて生存確認 (probe) にかける。生きていれば IdP ログイン (約 5 秒 + 大学側にログイン記録) を
        // 丸ごと省ける。死んでいれば probe が null を返して普通にログインする。
        String candidate = null;
        String host = moodleHost();
        if (!NetworkClient.hasCookies(host)) {
            PreferenceManager prefs = PreferenceManager.getInstance();
            String stored = prefs.getMoodleSessionCookies();
            // 持ち主の無いもの (旧形式を含む) は誰のものか分からないので消す。別のアカウントのものは使わないだけで
            // 残す (消すと、そのアカウントに戻ったときにログインし直しになる。復元は restore が断る)。
            if (stored != null && !stored.isEmpty() && SpSessionCookies.ownerOf(stored) == null) {
                prefs.setMoodleSessionCookies(null);
            }
            List<okhttp3.Cookie> restored = SpSessionCookies.restore(
                    prefs.getMoodleSessionCookies(), username, host, System.currentTimeMillis());
            if (!restored.isEmpty()) {
                NetworkClient.addCookies(restored);
                jarOwner = username.trim();
                candidate = "restored";
            }
        }
        return coordinator.session(sessionKey(username), context, candidate,
                key -> probeDashboardQuietly(username), () -> doLogin(username, password, context, false), unattended(username, password));
    }

    /**
     * SessionCoordinator に渡す鍵。方式と持ち主 (ユーザー名) の組。アカウントを切り替えている間に前のアカウントの
     * 取得がセッションを作り直しても、新しいアカウントはそれを使わず、飛行中のログインにも合流しない。
     */
    private static Object sessionKey(String username) {
        return java.util.Arrays.asList(resolveLoginMethod(), username.trim());
    }

    /** バックグラウンドの再ログインの判定。ログインに使うのと同じ ID / パスワードで作る。 */
    private static SessionCoordinator.Unattended unattended(String username, String password) {
        return BackgroundLoginPolicy.getInstance().forAccount("moodle", username, password);
    }

    private static String moodleHost() {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(baseUrl());
        return base != null ? base.host() : "elms.u-aizu.ac.jp";
    }

    /**
     * 取得処理を認証済みセッションで実行し、途中で未認証と分かった (SessionExpiredException) 場合だけ
     * 1 回、ログインし直して再実行する。資格情報は保存済みのものを使う (画面側は直前に
     * ensureLoggedIn を通しているので、通常ここでは通信しない)。
     */
    private <T> T withSession(AuthorizationContext context, SessionBody<T> body) throws Exception {
        PreferenceManager prefs = PreferenceManager.getInstance();
        String user = prefs.getUsername();
        String pass = prefs.getPassword();
        String key = ensureSession(user, pass, context);
        try {
            return body.run(key);
        } catch (SessionExpiredException e) {
            coordinator.invalidate(key);
            String fresh = ensureSession(user, pass, context);
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
            postError(callback, AppStrings.get(R.string.moodle_error_connection_format, e.getMessage()));
        }
    }

    /**
     * ログイン手順 (旧方式 / IdP) を実行し、/my/ から sesskey を取り出して返す。
     *
     * @param verifyPassword 資格情報の確認 (login)。IdP 方式では保存している SSO を使わず、必ずパスワードを送る。
     */
    private String doLogin(String username, String password, AuthorizationContext context, boolean verifyPassword) throws Exception {
        OkHttpClient client = NetworkClient.getCookieClient();

        Log.d(TAG, "Starting login for: " + username);
        // ここに来るのは手持ちのセッションが無いか死んでいたとき。復元した cookie と新しく発行される cookie の
        // Path / Domain が違うと置き換わらずに同名を二重に送るので、先に捨てる。
        NetworkClient.removeCookiesIssuedTo(moodleHost());
        jarOwner = username.trim();

        if (resolveLoginMethod() == LoginMethod.IDP) {
            // IdP 経由でセッション cookie を得たら、以降 (/my/ で sesskey 取得) は旧方式と共通。
            try {
                loginViaIdP(username, password, context, verifyPassword);
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
                throw new LoginFailure(invalidCredentialsMessage());
            }
        }

        String key = probeDashboard(username);
        Log.d(TAG, "Login successful");
        return key;
    }

    /**
     * GET /my/ で認証済みか確かめ、sesskey / userid を取り出して共有状態を更新する。
     * 未認証 (ログインページに戻される) なら LoginFailure。ログイン直後とセッション再確認の両方で使う。
     *
     * @param owner 保存するセッションの持ち主 (このセッションでログインしたユーザー名)。
     */
    private String probeDashboard(String owner) throws Exception {
        // リダイレクトは Moodle のホストの中だけ追う (iOS の sameHostRedirectsOnly と同じ)。未ログインだと
        // /my/ は login → IdP (SAML) へ飛び、IdP まで追うと cookie jar に溜まった古い IdP cookie で IdP 内を
        // ループして too many redirects で落ちる (2026-09-16 iOS 実機ログ)。別ホストへの 3xx で未ログインは
        // 確定する。同じホスト内 (ダッシュボード無効時の /my/courses.php など) はログイン済みでも起きるので追う。
        // newBuilder() は cookie jar を共有するので MoodleSession はそのまま送られる。
        OkHttpClient client = NetworkClient.getCookieClient().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        Request getDashboard = new Request.Builder()
                .url(baseUrl() + "/my/")
                .header("User-Agent", NetworkClient.getUserAgent())
                .build();

        String dashHtml;
        String dashUrl;
        int status;
        try (Response resp = NetworkClient.executeFollowingSameHostRedirects(client, getDashboard)) {
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
            throw new LoginFailure(AppStrings.get(R.string.moodle_error_session_establishment_failed));
        }

        String key = extractMatch(dashHtml, "\"sesskey\":\"([^\"]+)\"");
        String uid = extractMatch(dashHtml, "\"userid\":\\s*\"?(\\d+)\"?");
        if (uid == null) {
            uid = extractMatch(dashHtml, "user/profile\\.php\\?id=(\\d+)");
        }

        Log.d(TAG, "sesskey: " + (key != null ? "found" : "NOT FOUND")
                + ", userid: " + uid);

        if (key == null || key.isEmpty()) {
            throw new LoginFailure(AppStrings.get(R.string.moodle_error_session_info_failed));
        }
        // 確認している間に別のアカウントのログインが jar を差し替えていたら、いま読んだのはそのアカウントの
        // ダッシュボード。sesskey を返すと owner の取得が別人のコースや課題を読むので、ここで止める。
        String current = jarOwner;
        if (current != null && !current.equals(owner.trim())) {
            throw new AccountChangedException();
        }
        sesskey = key;
        userid = uid;
        // 次回起動で使い回すために保存する (ensureSession の candidate)。ログイン直後だけでなく生存確認の
        // たびに書き直す: ALB の cookie は応答ごとに更新される。持ち主と一緒に保存する (SpSessionCookies)。
        PreferenceManager.getInstance().setMoodleSessionCookies(SpSessionCookies.serialize(
                NetworkClient.cookiesForHost(moodleHost()), System.currentTimeMillis(), owner));
        return key;
    }

    /** 生きていれば /my/ から取り直した sesskey (以前と違えばそちらが正)、死んでいれば null。 */
    private String probeDashboardQuietly(String owner) {
        try {
            return probeDashboard(owner);
        } catch (AccountChangedException e) {
            // 死んでいる (null) と答えると、SessionCoordinator が owner でログインし直し、切り替えた先の
            // アカウントのセッションを消す。取得ごと失敗させる。
            throw e;
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

    public void fetchCourses(AuthorizationContext context, ServiceCallback<List<MoodleCourse>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        // 結果を渡す直前にアカウントが変わっていないか確かめる (前のアカウントの課題や科目を画面・キャッシュに出さない)。
        AccountSwitch.Ticket ticket = AccountSwitch.ticket();
        executor.execute(() -> {
            try {
                String jsonBody = "[{\"index\":0,\"methodname\":" +
                        "\"core_course_get_enrolled_courses_by_timeline_classification\"," +
                        "\"args\":{\"classification\":\"all\",\"limit\":0,\"offset\":0,\"sort\":\"fullname\"}}]";

                String response = withSession(context, key -> ajaxCall(key,
                        "core_course_get_enrolled_courses_by_timeline_classification",
                        jsonBody));

                Map<String, Object> data;
                try {
                    data = MoodleAjax.firstData(gson, response);
                } catch (MoodleAjax.AjaxException e) {
                    Log.w(TAG, "core_course_get_enrolled_courses_by_timeline_classification failed: " + e.getMessage());
                    postErrorForAccount(callback, AppStrings.get(R.string.courses_unavailable), ticket);
                    return;
                }
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

                postSuccessForAccount(callback, courses, ticket);
            } catch (Exception e) {
                Log.e(TAG, "fetchCourses error", e);
                postErrorForAccount(callback, e.getMessage(), ticket);
            }
        });
    }

    public void fetchAssignments(AuthorizationContext context, ServiceCallback<List<Assignment>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        // 結果を渡す直前にアカウントが変わっていないか確かめる (前のアカウントの課題や科目を画面・キャッシュに出さない)。
        AccountSwitch.Ticket ticket = AccountSwitch.ticket();
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis() / 1000;
                String jsonBody = "[{\"index\":0,\"methodname\":" +
                        "\"core_calendar_get_action_events_by_timesort\"," +
                        "\"args\":{\"limitnum\":50,\"timesortfrom\":" + now +
                        ",\"limittononsuspendedevents\":true}}]";

                String response = withSession(context, key -> ajaxCall(key,
                        "core_calendar_get_action_events_by_timesort",
                        jsonBody));

                // 失敗を「課題 0 件」の成功にしない。空で保存すると、保存済みの課題とリマインダーが消える。
                Map<String, Object> data;
                try {
                    data = MoodleAjax.firstData(gson, response);
                } catch (MoodleAjax.AjaxException e) {
                    Log.w(TAG, "core_calendar_get_action_events_by_timesort failed: " + e.getMessage());
                    postErrorForAccount(callback, AppStrings.get(R.string.assignments_unavailable), ticket);
                    return;
                }
                List<Map<String, Object>> events = data.get("events") instanceof List
                        ? (List<Map<String, Object>>) data.get("events") : null;
                if (events == null) {
                    Log.w(TAG, "core_calendar_get_action_events_by_timesort failed: no events");
                    postErrorForAccount(callback, AppStrings.get(R.string.assignments_unavailable), ticket);
                    return;
                }

                List<Assignment> assignments = new ArrayList<>();
                for (Map<String, Object> e : events) {
                    Assignment a = new Assignment();
                    a.setId(toInt(e.get("id")));
                    a.setName(toStr(e.get("name")));
                    a.setDueDate(toLong(e.get("timesort")));
                    a.setUrl(MoodleEventUrl.pick(
                            e.get("url") instanceof String ? (String) e.get("url") : null,
                            e.get("viewurl") instanceof String ? (String) e.get("viewurl") : null));

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

                postSuccessForAccount(callback, assignments, ticket);
            } catch (Exception e) {
                Log.e(TAG, "fetchAssignments error", e);
                postErrorForAccount(callback, e.getMessage(), ticket);
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

    public void fetchCourseContents(int courseId, AuthorizationContext context, ServiceCallback<List<com.shakenokirimi12.uoa_app.data.models.CourseSection>> callback) {
        if (!AppConfigService.getInstance().isFeatureEnabled("moodle_enabled")) {
            callback.onError(AppConfigService.FEATURE_DISABLED_MESSAGE);
            return;
        }
        // 結果を渡す直前にアカウントが変わっていないか確かめる (前のアカウントの課題や科目を画面・キャッシュに出さない)。
        AccountSwitch.Ticket ticket = AccountSwitch.ticket();
        executor.execute(() -> {
            try {
                postSuccessForAccount(callback, withSession(context, key -> fetchCourseContentsWithSession(key, courseId)), ticket);
            } catch (Exception e) {
                postErrorForAccount(callback, e.getMessage(), ticket);
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
                throw new java.io.IOException(AppStrings.get(R.string.moodle_error_course_page_format, resp.code()));
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
        mainHandler.post(() -> cb.onError(msg != null ? msg : AppStrings.get(R.string.common_unknown_problem)));
    }

    /**
     * ticket を控えてからアカウントが切り替わった (またはログアウトした) 取得の結果は渡さない。入口の確認
     * (ensureSession の isStale) を通ったあとに切り替えが始まると、前のアカウントのセッションで取った課題や科目が
     * 新しいアカウントの画面とキャッシュに入る。確かめるのは渡すメインスレッドの上 (切り替えもメインスレッドで決まる)。
     */
    private <T> void postSuccessForAccount(ServiceCallback<T> cb, T result, AccountSwitch.Ticket ticket) {
        mainHandler.post(() -> {
            if (ticket.isCurrent()) {
                cb.onSuccess(result);
            } else {
                Log.w(TAG, "Dropping Moodle data fetched for the previous account");
                cb.onError(new AccountChangedException().getMessage());
            }
        });
    }

    /**
     * postSuccessForAccount の失敗版。前のアカウントの失敗 (ID / パスワードの誤りなど) をそのまま渡すと、画面が
     * 今のアカウントの資格情報に誤りの印を付けてログインを止めるので、差し替える。
     */
    private <T> void postErrorForAccount(ServiceCallback<T> cb, String msg, AccountSwitch.Ticket ticket) {
        mainHandler.post(() -> {
            if (ticket.isCurrent()) {
                cb.onError(msg != null ? msg : AppStrings.get(R.string.common_unknown_problem));
            } else {
                cb.onError(new AccountChangedException().getMessage());
            }
        });
    }
}
