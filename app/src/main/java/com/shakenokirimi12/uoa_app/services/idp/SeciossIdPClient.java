package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.services.NetworkClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * SECIOSS(SLINK) 製 IdP (https://slink.secioss.com) の認証チェーンを生 HTTP で再現するクライアント。
 * iOS の SeciossIdPClient.swift の移植。
 *
 * tenantlogin.cgi -> allotplogin.cgi -> motp.cgi の流れ、中継フォーム (自動 submit で別画面へ飛ばす
 * ページ)、既存 OTP 設定の有無、qrsecret/motpmail 双方の新規登録手順は 2026-09-07 に iOS 側の
 * CLI スクリプトで実機検証済み。CampusSquare (csweb) が SP として繋がった後の SAML POST バックは
 * 未検証。このクライアント自体は CampusSquare を一切知らず、entryUrl として渡された先の IdP 認証
 * だけを担う (csweb 固有の扱いは CampusSquareService 側の責務)。
 */
public final class SeciossIdPClient {
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");
    /** IdP のホスト。保持している SSO cookie を「サーバが実際に発行するのと同じ表現」で仕立て直すのに使う。 */
    private static final String IDP_HOST = "slink.secioss.com";
    private static final String TENANT_LOGIN_URL = "https://slink.secioss.com/pub/tenantlogin.cgi";
    private static final String MOTP_URL = "https://slink.secioss.com/pub/motp.cgi";
    private static final String ALL_OTP_LOGIN_URL = "https://slink.secioss.com/pub/allotplogin.cgi";
    private static final String OTP_NOT_CONFIGURED_MARKER = "ワンタイムパスワード設定が行われていません";
    private static final String SESSION_TIMED_OUT_MARKER = "タイムアウトしました";
    private static final int MAX_REDIRECTS = 10;
    private static final int MAX_RELAY_HOPS = 5;

    // OTP の調達は OtpProvider (方式ごとの実体は OtpProviders が決める) に委ねる。

    public static final class Session {
        @NonNull public final String finalHtml;
        /**
         * チェーンが終わった URL。成功マーカー (「ログアウト」) は IdP 側のページにも含まれるため、
         * SP (CampusSquare) へ本当に戻ったかは呼び出し側がこの URL のホストで判定する。
         */
        @NonNull public final String finalUrl;
        /** 最後のホップの HTTP ステータス。SP の 503 はメンテナンスページ (Moodle、2026-09-15 実測)。 */
        public final int finalStatus;
        @NonNull public final String cookieHeader;
        /**
         * cookieHeader の元になった SP の cookie を属性 (Domain / host-only / Path / 期限 / Secure) 込みで。
         * 永続化はこちらを使う。"name=value" に潰すと、復元時にどのホスト・パスへ送るものか、いつ切れるかが
         * 分からなくなる。最終ホップ (SP) のホストに送られうる未失効のものだけ。
         */
        @NonNull public final List<Cookie> spCookies;

        Session(@NonNull String finalHtml, @NonNull String finalUrl, int finalStatus, @NonNull String cookieHeader,
                @NonNull List<Cookie> spCookies) {
            this.finalHtml = finalHtml;
            this.finalUrl = finalUrl;
            this.finalStatus = finalStatus;
            this.cookieHeader = cookieHeader;
            this.spCookies = Collections.unmodifiableList(new ArrayList<>(spCookies));
        }
    }

    /**
     * SP 側のメンテナンスページか (Moodle: HTTP 503 + 「メンテナンスモード」、2026-09-15 実測)。
     * このページにも「ログアウト」リンクが含まれるため、呼び出し側は成功マーカーより先にこれを見ること。
     */
    public static boolean isMaintenancePage(int status, @NonNull String html) {
        // 本文一致だと通常画面のお知らせで誤検出しうるので、503 か <title> だけを見る。
        if (status == 503) return true;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<title>([^<]*)</title>").matcher(html);
        if (!m.find()) return false;
        String title = m.group(1).toLowerCase(java.util.Locale.ROOT);
        return title.contains("メンテナンスモード") || title.contains("maintenance");
    }

    /**
     * SECIOSS が ID/PW 誤り時に <div id="comment" class="message error"> へ入れて返す文言。
     * 英語版は実アカウント + 誤ったパスワードで確認済み (2026-09-09)。日本語・中国語版はログインフォームの
     * JS (会津大がこの 3 文言を書き換える処理) から網羅的に取得した。UI の言語は Accept-Language で
     * 切り替わるため 3 言語すべてを見る。
     */
    static final String[] AUTH_FAILURE_MARKERS = {
            "ユーザー名またはパスワードが間違っています",
            "User Name or password is invalid",
            "用户名或密码错误",
    };

    /**
     * ログインフォームのページは正常時でもこの 3 文言を JS 内の文字列として持っている。素の contains だと
     * ログイン成功時まで認証失敗と誤判定して同期を止めてしまうため、必ず <script> を除外する。
     */
    static boolean containsAuthFailureMessage(@NonNull String html) {
        String withoutScripts = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        for (String marker : AUTH_FAILURE_MARKERS) {
            if (withoutScripts.contains(marker)) return true;
        }
        return false;
    }

    private final String userAgent;
    /** 全リクエストをこのプロキシ経由にする (OTP 登録を学内 SSH トンネル経由で行うため)。null なら直接。 */
    @Nullable private final java.net.Proxy proxy;

    public SeciossIdPClient() {
        this(NetworkClient.getUserAgent(), null);
    }

    public SeciossIdPClient(@NonNull String userAgent) {
        this(userAgent, null);
    }

    public SeciossIdPClient(@Nullable java.net.Proxy proxy) {
        this(NetworkClient.getUserAgent(), proxy);
    }

    public SeciossIdPClient(@NonNull String userAgent, @Nullable java.net.Proxy proxy) {
        this.userAgent = userAgent;
        this.proxy = proxy;
    }

    private okhttp3.OkHttpClient httpClient() {
        okhttp3.OkHttpClient base = NetworkClient.getNoRedirectClient();
        return proxy == null ? base : base.newBuilder().proxy(proxy).build();
    }

    // ---- HTTP ----

    static final class HttpResult {
        final int status;
        @NonNull final String url;
        @NonNull final String html;
        /** ホスト情報を持ったまま持ち回る。ホスト別に分けないと、secioss と SP が同名 cookie
         * (AWS ALB の AWSALB 等) を出したとき互いに上書きし合って SAML が無限リダイレクトになる。 */
        @NonNull final List<Cookie> cookies;

        HttpResult(int status, @NonNull String url, @NonNull String html, @NonNull List<Cookie> cookies) {
            this.status = status;
            this.url = url;
            this.html = html;
            this.cookies = cookies;
        }
    }

    HttpResult get(@NonNull String url, @NonNull List<Cookie> cookies,
                   @NonNull Map<String, String> extraHeaders) throws IOException {
        return request(url, "GET", null, cookies, extraHeaders);
    }

    HttpResult postForm(@NonNull String url, @NonNull String body, @NonNull List<Cookie> cookies,
                        @NonNull String referer) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Referer", referer);
        return request(url, "POST", body, cookies, headers);
    }

    /**
     * リダイレクトは自前で追う。共有 cookie jar を使わず、途中の各応答の Set-Cookie を集めて次のホップへ
     * 送る。AWS ALB のスティッキーセッション cookie を引き継がないと、多段リクエストが別バックエンドに
     * 割り振られて sessid ベースのサーバ側セッションが見つからなくなることがある。
     *
     * cookie は必ずホスト (ドメイン) 単位で持ち、そのホストにだけ送る。名前だけの辞書で全ホストへ
     * 無差別に送っていた頃は、IdP(secioss) と SP(csweb/elms) が同名 cookie (AWSALB 等) を出すと
     * 互いに上書きし合い、両方の ALB スティッキーが壊れて SAML が延々リダイレクトし続け、端末側で
     * -1007 (リダイレクト過多) になっていた (2026-09-15、実機のセルラー回線で発生)。
     */
    private HttpResult request(@NonNull String url, @NonNull String method, @Nullable String body,
                               @NonNull List<Cookie> cookies,
                               @NonNull Map<String, String> extraHeaders) throws IOException {
        List<Cookie> jar = new ArrayList<>(cookies);
        String currentUrl = url;
        String currentMethod = method;
        String currentBody = body;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpUrl parsed = HttpUrl.parse(currentUrl);
            if (parsed == null) throw new IOException("bad URL: " + currentUrl);
            Request.Builder rb = new Request.Builder().url(parsed).header("User-Agent", userAgent);
            for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                if ("Content-Type".equalsIgnoreCase(h.getKey()) && currentBody == null) continue;
                rb.header(h.getKey(), h.getValue());
            }
            // このホストに一致する cookie だけを送る (Cookie.matches がドメイン/パス/secure を見る)。
            String header = cookieHeaderFor(jar, parsed);
            if (!header.isEmpty()) rb.header("Cookie", header);
            if ("POST".equals(currentMethod)) {
                rb.post(RequestBody.create(currentBody != null ? currentBody : "", FORM));
            } else {
                rb.get();
            }
            try (Response resp = httpClient().newCall(rb.build()).execute()) {
                List<Cookie> received = Cookie.parseAll(parsed, resp.headers());
                mergeCookies(jar, received);
                // この 302 も含めた各ホップで IdP が出した cookie だけを SSO ストアへ渡す。
                // 最終ホップだけを見ると、SP へ戻る途中の 302 で発行された SSO cookie を取り逃がす。
                // 素の endsWith だと "evil-secioss.com" のような別ドメインの cookie まで SSO
                // ストアに取り込んでしまう。ドメイン境界まで見る。
                String hopHost = parsed.host().toLowerCase(java.util.Locale.ROOT);
                if (hopHost.equals("secioss.com") || hopHost.endsWith(".secioss.com")) {
                    // 属性 (path / expires) ごと渡す: ストアはセッション cookie と期限付き cookie を区別して保持する。
                    SeciossSsoStore.getInstance().merge(hopHost, received);
                }
                String location = resp.header("Location");
                if (resp.isRedirect() && location != null) {
                    HttpUrl next = parsed.resolve(location);
                    if (next == null) throw new IOException("bad redirect: " + location);
                    currentUrl = next.toString();
                    // 303、および 301/302 の POST はブラウザ同様 GET に落とす。307/308 はメソッドと本文を維持する。
                    int code = resp.code();
                    if (code == 303 || ((code == 301 || code == 302) && "POST".equals(currentMethod))) {
                        currentMethod = "GET";
                        currentBody = null;
                    }
                    continue;
                }
                byte[] bytes = resp.body() != null ? resp.body().bytes() : new byte[0];
                return new HttpResult(resp.code(), currentUrl, decode(bytes), jar);
            }
        }
        throw new TooManyRedirectsException(url);
    }

    /** 自前のリダイレクト追従が上限に達した。SSO を載せた入口でこれが出たら SSO を捨てて入り直す。 */
    static final class TooManyRedirectsException extends IOException {
        TooManyRedirectsException(String url) { super("too many redirects: " + url); }
    }

    /**
     * 受信 cookie を jar に取り込む。同じ (name, domain, path) の cookie だけを最新で置き換え、
     * ホスト (domain) が違えば同名でも別 cookie として残す。
     *
     * ここが名前だけをキーにしていた頃、IdP(secioss) と SP(csweb/elms) が同名 cookie (AWSALB 等) を
     * 出すと互いに上書きし合い、両方の ALB スティッキーが壊れて SAML が延々リダイレクトし、端末側で
     * -1007 (リダイレクト過多)・データ「何も出ない」になっていた。このメソッドの不変条件
     * (同名・別ホストは共存する / 同名・同ホストは置き換わる) は SeciossCookieJarTest で固定している。
     */
    static void mergeCookies(@NonNull List<Cookie> jar, @NonNull List<Cookie> received) {
        mergeCookies(jar, received, System.currentTimeMillis());
    }

    /**
     * 共有の cookie jar (NetworkClient の HostScopedCookieJar) も同じ規則で動かすため public にしている。
     *
     * 期限切れの Set-Cookie は「削除指示」なので、古いものを消したうえで jar に積まない。ここを積んで
     * いたため、SAML が使い捨てするリクエスト状態 cookie (_opensaml_req_ss:mem:<毎回別のID>) が削除されず
     * 毎ホップ増殖し、削除済み cookie を送り返してサーバが状態不整合と判断 → SP と IdP の間で 302/307 が
     * 延々ピンポンしてリダイレクト過多になっていた (2026-09-16 の iOS 実機ログで、1 ホップごとに
     * request_bytes が 552→2810 と単調増加するのを確認)。動作する Python 参照 (http.cookiejar) は期限切れを
     * 正しく捨てるため、同じサーバでも成功していた。
     */
    public static void mergeCookies(@NonNull List<Cookie> jar, @NonNull List<Cookie> received, long now) {
        for (Cookie c : received) {
            jar.removeIf(existing -> existing.name().equals(c.name())
                    && existing.domain().equals(c.domain()) && existing.path().equals(c.path()));
            if (isExpired(c, now)) continue;
            jar.add(c);
        }
    }

    /** サーバが「削除」を指示した cookie か。セッション cookie (persistent でない) は期限なし。 */
    public static boolean isExpired(@NonNull Cookie cookie, long now) {
        return cookie.persistent() && cookie.expiresAt() <= now;
    }

    /** jar のうち url に一致し、かつ期限切れでない cookie を "a=b; c=d" で返す。無ければ空文字。 */
    static String cookieHeaderFor(@NonNull List<Cookie> jar, @NonNull HttpUrl url) {
        return cookieHeaderFor(jar, url, System.currentTimeMillis());
    }

    static String cookieHeaderFor(@NonNull List<Cookie> jar, @NonNull HttpUrl url, long now) {
        StringBuilder sb = new StringBuilder();
        for (Cookie c : jar) {
            if (!c.matches(url) || isExpired(c, now)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name()).append('=').append(c.value());
        }
        return sb.toString();
    }

    /**
     * Session に載せる cookie ヘッダ。呼び出し側 (CampusSquare/Moodle) はこれを SP のホストの
     * cookie jar へ入れて以降の取得に使うので、最終ホップ (SP) に一致する cookie だけを返す。
     */
    private static String sessionCookieHeader(@NonNull HttpResult result) {
        HttpUrl url = HttpUrl.parse(result.url);
        return url != null ? cookieHeaderFor(result.cookies, url) : "";
    }

    /** Session.spCookies。最終ホップのホストに送られうる (ドメイン一致の) 未失効の cookie。パスは問わない。 */
    @NonNull
    private static List<Cookie> sessionCookies(@NonNull HttpResult result) {
        HttpUrl url = HttpUrl.parse(result.url);
        return url != null ? cookiesForHost(result.cookies, url.host(), System.currentTimeMillis())
                : Collections.<Cookie>emptyList();
    }

    @NonNull
    static List<Cookie> cookiesForHost(@NonNull List<Cookie> jar, @NonNull String host, long now) {
        List<Cookie> out = new ArrayList<>();
        for (Cookie c : jar) {
            if (isExpired(c, now)) continue;
            if (com.shakenokirimi12.uoa_app.services.HostScopedCookieJar.domainMatches(c, host.toLowerCase(java.util.Locale.ROOT))) {
                out.add(c);
            }
        }
        return out;
    }

    /** UTF-8 として読めなければ Shift_JIS (iOS と同じ順序)。 */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("Shift_JIS"));
        }
    }

    static String cookieHeader(@NonNull Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** iOS の urlEncode と同じ (英数字と "-_.*" 以外をパーセントエンコード。空白は "+" ではなく "%20")。 */
    public static String urlEncode(@NonNull String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    // ---- Parsing helpers (pure, unit-tested) ----

    /** 最初のマッチの group(1)。iOS の String.matching(regex:) と同じく大文字小文字を区別しない。 */
    @Nullable
    static String firstGroup(@NonNull String pattern, @NonNull String text) {
        Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() && m.groupCount() >= 1 ? m.group(1) : null;
    }

    @NonNull
    static List<String> allGroups(@NonNull String pattern, @NonNull String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(pattern).matcher(text);
        while (m.find()) {
            if (m.groupCount() >= 1) out.add(m.group(1));
        }
        return out;
    }

    /**
     * 属性の並び順 (id/name/value の順序がページによって揺れる) に依存しないよう、まず対象 input タグ
     * 全体を切り出してから、その中で value を探す 2 段階にしている。
     */
    @Nullable
    static String extractField(@NonNull String name, @NonNull String html) {
        String tag = firstGroup("(<input[^>]*name=\"" + Pattern.quote(name) + "\"[^>]*>)", html);
        if (tag == null) return null;
        String value = firstGroup("value=\"([^\"]*)\"", tag);
        // 属性値は HTML エスケープされている。back はクエリ文字列を含むので "&amp;" が入る (2026-09-14 実測)。
        return value != null ? htmlUnescape(value) : null;
    }

    static String htmlUnescape(@NonNull String s) {
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    /**
     * エラーメッセージ用に、ページの可視テキストだけを切り出す (<script>/<style> とタグを除き、空白を潰す)。
     * FunctionID だけでは SP 未登録・テナント違い・アカウント停止のどれかが分からないため、本文を添える。
     */
    @NonNull
    public static String visibleExcerpt(@NonNull String html, int limit) {
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        t = t.replaceAll("<[^>]+>", " ");
        t = t.replace("&nbsp;", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t.length() > limit ? t.substring(0, limit) : t;
    }

    /**
     * pub/error.cgi に着地した場合の分類。error.cgi はどのメッセージでも「ログアウト」リンクを含むため、
     * 成功マーカーより先に見ないと成功と誤判定する (2026-09-14、CampusSquare 経由の実測で発覚)。
     * error.cgi でなければ null。
     */
    @Nullable
    static SeciossError classifyErrorPage(@NonNull String url, @NonNull String html) {
        if (!url.contains("/pub/error.cgi")) return null;
        String msg = firstGroup("msg=([A-Za-z0-9_]+)", url);
        if (msg == null) msg = "unknown";
        if (msg.startsWith("auth_err") || html.contains("アクセスが許可されていません")) {
            return new SeciossError(SeciossError.Kind.ACCESS_DENIED, msg);
        }
        return new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                "error.cgi(" + msg + ") " + visibleExcerpt(html, 400));
    }

    /** ブラウザ操作なしにページが次へ進む手段の検出結果。 */
    static final class DetectedRelay {
        /** null なら redirectGetUrl の GET。非 null なら action へ fields を POST。 */
        @Nullable final String formAction;
        @NonNull final Map<String, String> fields;
        @Nullable final String redirectGetUrl;

        private DetectedRelay(@Nullable String formAction, @NonNull Map<String, String> fields,
                              @Nullable String redirectGetUrl) {
            this.formAction = formAction;
            this.fields = fields;
            this.redirectGetUrl = redirectGetUrl;
        }

        static DetectedRelay formSubmit(@NonNull String action, @NonNull Map<String, String> fields) {
            return new DetectedRelay(action, fields, null);
        }

        static DetectedRelay redirectGet(@NonNull String url) {
            return new DetectedRelay(null, Collections.emptyMap(), url);
        }

        boolean isFormSubmit() { return formAction != null; }
    }

    /**
     * HTTP の 3xx を除くと、ブラウザ操作なしにページが次へ進む標準的な手段は実質 3 種類しかない。
     * 個別大学の HTML 構造を当て推量するのではなく、この標準手段そのものを網羅的に検出する。
     * 1. JS の document.<form>.submit() によるフォーム自動送信 (SAMLResponse の自動送信も、内部リレー
     *    (file=allotplogin 等) も同じ形。2026-09-07 に協力者アカウントで実際に踏んで発覚・修正済み)。
     * 2. <meta http-equiv="refresh" content="...;url=..."> によるメタリフレッシュ。
     * 3. JS の location.href / location / location.replace(...) への直接代入。
     * FunctionID のような個別フィールド名や SAMLResponse の有無には依存しない。
     */
    @Nullable
    static DetectedRelay detectRelay(@NonNull String html) {
        if (html.contains(".submit()")) {
            String formTag = firstGroup("(<form\\b[^>]*>)", html);
            String action = formTag != null ? firstGroup("action=\"([^\"]*)\"", formTag) : null;
            if (action != null) {
                Map<String, String> fields = new LinkedHashMap<>();
                for (String inputTag : allGroups("(<input[^>]*>)", html)) {
                    String name = firstGroup("name=\"([^\"]*)\"", inputTag);
                    if (name == null) continue;
                    String value = firstGroup("value=\"([^\"]*)\"", inputTag);
                    fields.put(name, htmlUnescape(value != null ? value : ""));
                }
                return DetectedRelay.formSubmit(action, fields);
            }
        }
        String metaTag = firstGroup("(<meta[^>]*http-equiv=\"refresh\"[^>]*>)", html);
        if (metaTag != null) {
            String content = firstGroup("content=\"([^\"]*)\"", metaTag);
            String redirectUrl = content != null ? firstGroup("url=(.+)$", content) : null;
            if (redirectUrl != null) return DetectedRelay.redirectGet(redirectUrl.trim());
        }
        String redirectUrl = firstGroup("location(?:\\.href)?\\s*=\\s*\"([^\"]+)\"", html);
        if (redirectUrl == null) redirectUrl = firstGroup("location\\.replace\\(\\s*\"([^\"]+)\"", html);
        if (redirectUrl != null) return DetectedRelay.redirectGet(redirectUrl);
        return null;
    }

    @NonNull
    static String resolve(@NonNull String base, @NonNull String relative) {
        HttpUrl baseUrl = HttpUrl.parse(base);
        HttpUrl resolved = baseUrl != null ? baseUrl.resolve(relative) : null;
        return resolved != null ? resolved.toString() : relative;
    }

    private HttpResult followAutoSubmitRelays(@NonNull HttpResult start) throws IOException {
        HttpResult current = start;
        for (int i = 0; i < MAX_RELAY_HOPS; i++) {
            DetectedRelay relay = detectRelay(current.html);
            if (relay == null) break;
            HttpResult hop;
            if (relay.isFormSubmit()) {
                String target = resolve(current.url, relay.formAction);
                StringBuilder body = new StringBuilder();
                for (Map.Entry<String, String> f : relay.fields.entrySet()) {
                    if (body.length() > 0) body.append('&');
                    body.append(urlEncode(f.getKey())).append('=').append(urlEncode(f.getValue()));
                }
                hop = postForm(target, body.toString(), current.cookies, current.url);
            } else {
                String target = resolve(current.url, relay.redirectGetUrl);
                hop = get(target, current.cookies, Collections.singletonMap("Referer", current.url));
            }
            // 200 以外でもそのホップの内容を返す。SP のメンテナンスページ (503) を捨てて手前の
            // 中継フォームを返すと、呼び出し側が原因を判別できない。
            current = hop;
            if (hop.status != 200) break;
        }
        return current;
    }

    // ---- Login chain ----

    private static final class PostPasswordState {
        @NonNull final HttpResult result;
        @Nullable final String functionId;

        PostPasswordState(@NonNull HttpResult result, @Nullable String functionId) {
            this.result = result;
            this.functionId = functionId;
        }
    }

    /**
     * entry -> tenantlogin.cgi (ID/PW POST) -> back の追跡 (+ 中継フォームの追従) までを行う。
     * login() と beginOtpRegistrationOnCampus() の共通部分。
     */
    private PostPasswordState authenticateUpToOtpDecision(@NonNull String entryUrl, @NonNull String uid,
                                                          @NonNull String pass, boolean useStoredSso)
            throws SeciossError, IOException {
        // 保持している SSO セッションを載せて入口を叩く。IdP 側で生きていれば、ログインフォームでは
        // なく SAMLResponse の自動 submit が返ってきて、パスワードも OTP も要求されずに SP へ戻れる。
        // SSO は持ち主ごと。別アカウントのものなら捨て、ここから受け取る分は uid のものとする
        // (SSO を載せない経路でも受け取った cookie はストアに入るので、同じく記録する)。
        SeciossSsoStore sso = SeciossSsoStore.getInstance();
        sso.claimFor(uid);
        List<Cookie> initialCookies = useStoredSso ? sso.current() : Collections.<Cookie>emptyList();
        HttpResult entry;
        try {
            entry = get(entryUrl, initialCookies, Collections.emptyMap());
        } catch (TooManyRedirectsException e) {
            if (initialCookies.isEmpty()) throw e;
            // 載せた SSO を IdP が受け付けず、IdP 内でリダイレクトが回った (IdP 側で失効したチケットを
            // 再生すると 307 → 302 → 302 が続く、2026-09-16)。SSO を捨ててパスワードから 1 回だけ入り直す。
            // 捨てないと、セッション cookie が消えるまでの最大 1 時間、同じ失敗を繰り返す。
            android.util.Log.w("SeciossIdPClient", "entry looped with stored SSO; clearing it and retrying from the password");
            sso.clear();
            sso.claimFor(uid);
            entry = get(entryUrl, Collections.emptyList(), Collections.emptyMap());
        }
        if (entry.status != 200) {
            throw new SeciossError(SeciossError.Kind.UNEXPECTED_STATUS, entry.status, "entry page (" + entryUrl + ")");
        }
        if (extractField("sessid", entry.html) == null) {
            // ログインフォームが無い = 既存の SSO セッションで通った可能性が高い。中継を追って SP まで
            // 戻す。想定と違う画面なら、呼び出し側 (成功マーカー / ホスト検証) が弾く。
            HttpResult passthrough = followAutoSubmitRelays(entry);
            return new PostPasswordState(passthrough, extractField("FunctionID", passthrough.html));
        }
        String sessid = extractField("sessid", entry.html);
        String back = extractField("back", entry.html);
        String tenant = extractField("tenant", entry.html);
        if (sessid == null || back == null || tenant == null) {
            throw new SeciossError(SeciossError.Kind.MISSING_FIELD, "sessid/back/tenant on login form");
        }

        String loginBody = "dummy=&username=" + urlEncode(uid) + "&tenant=" + urlEncode(tenant)
                + "&password=" + urlEncode(pass) + "&op=login&back=" + urlEncode(back) + "&sessid=" + sessid;
        HttpResult login = postForm(TENANT_LOGIN_URL, loginBody, entry.cookies, entryUrl);
        if (login.status != 200) {
            throw new SeciossError(SeciossError.Kind.UNEXPECTED_STATUS, login.status, "tenantlogin.cgi");
        }
        // ID/PW 誤りは HTTP 200 のままログインフォームが再表示される形で返ってくるため、ステータスコードでは
        // 判別できない。ここで明示的にエラー文言を見て他の失敗と区別する (アカウントロック回避のため)。
        if (containsAuthFailureMessage(login.html)) {
            throw new SeciossError(SeciossError.Kind.INVALID_CREDENTIALS);
        }

        HttpResult after = get(back, login.cookies, Collections.singletonMap("Referer", TENANT_LOGIN_URL));
        if (after.status != 200) {
            throw new SeciossError(SeciossError.Kind.UNEXPECTED_STATUS, after.status, "post-login back");
        }
        HttpResult relayed = followAutoSubmitRelays(after);
        if (relayed.html.contains(SESSION_TIMED_OUT_MARKER)) {
            throw new SeciossError(SeciossError.Kind.SESSION_TIMED_OUT);
        }
        return new PostPasswordState(relayed, extractField("FunctionID", relayed.html));
    }

    /**
     * otpCode を呼んでコードを取得し、送信して完了させる。学内アクセス相当で OTP 自体が要求されなかった
     * 場合は otpCode を呼ばずに完了する。OTP 未設定の場合は OTP_NOT_CONFIGURED を投げる (このクライアントは
     * 登録操作をしない。登録が必要なら beginOtpRegistrationOnCampus を使うこと)。
     */
    @NonNull
    public Session login(@NonNull String entryUrl, @NonNull String uid, @NonNull String pass,
                         @NonNull OtpProvider otp) throws Exception {
        PostPasswordState state = authenticateUpToOtpDecision(entryUrl, uid, pass, true);
        HttpResult current = state.result;

        if (current.html.contains(OTP_NOT_CONFIGURED_MARKER)) {
            throw new SeciossError(SeciossError.Kind.OTP_NOT_CONFIGURED);
        }

        SeciossError errorPage = classifyErrorPage(current.url, current.html);
        if (errorPage != null) throw errorPage;

        if (!"allotplogin".equals(state.functionId)) {
            boolean ok = current.html.contains("ログアウト") || current.html.contains("Logout")
                    || current.url.contains("index.php");
            if (!ok) {
                throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                        "post-login screen(FunctionID=" + state.functionId + ", url=" + current.url + ") "
                                + visibleExcerpt(current.html, 400));
            }
            return new Session(current.html, current.url, current.status, sessionCookieHeader(current),
                    sessionCookies(current));
        }

        String otpSessid = extractField("sessid", current.html);
        String otpBack = extractField("back", current.html);
        String otpUsername = extractField("username", current.html);
        if (otpSessid == null || otpBack == null || otpUsername == null) {
            throw new SeciossError(SeciossError.Kind.MISSING_FIELD, "sessid/back/username on OTP form");
        }

        // メール方式のときだけ送信を要求する (motprefresh=0)。認証アプリ方式はこのステップを使わない。
        HttpResult motp = current;
        if (otp.requiresMailDispatch()) {
            String motpBody = "username=" + urlEncode(otpUsername) + "&sessid=" + urlEncode(otpSessid)
                    + "&motprefresh=0";
            motp = postForm(MOTP_URL, motpBody, current.cookies, current.url);
            if (motp.status != 200) {
                throw new SeciossError(SeciossError.Kind.OTP_REQUEST_FAILED);
            }
        }

        String code = otp.code();

        String otpBody = "dummy=&username=" + urlEncode(otpUsername) + "&password=" + urlEncode(code)
                + "&op=login&back=" + urlEncode(otpBack) + "&sessid=" + otpSessid;
        HttpResult verify = postForm(ALL_OTP_LOGIN_URL, otpBody, motp.cookies, current.url);
        if (verify.status != 200) {
            throw new SeciossError(SeciossError.Kind.UNEXPECTED_STATUS, verify.status,
                    "allotplogin.cgi(コードが間違っていた可能性も含む)");
        }

        HttpResult fin = get(otpBack, verify.cookies, Collections.singletonMap("Referer", ALL_OTP_LOGIN_URL));
        if (fin.status != 200) {
            throw new SeciossError(SeciossError.Kind.UNEXPECTED_STATUS, fin.status, "post-OTP back");
        }
        HttpResult relayed = followAutoSubmitRelays(fin);
        // otpCode の待ち時間 (IMAP 自動取得は最大 30 秒) を挟むため、ここでのタイムアウトが一番起こりやすい。
        if (relayed.html.contains(SESSION_TIMED_OUT_MARKER)) {
            throw new SeciossError(SeciossError.Kind.SESSION_TIMED_OUT);
        }
        return new Session(relayed.html, relayed.url, relayed.status, sessionCookieHeader(relayed),
                sessionCookies(relayed));
    }

    public enum OtpStatus {
        /** パスワード後に「ワンタイムパスワード設定が行われていません」(auth_err_020)。学外からの判定。 */
        NOT_CONFIGURED,
        /** OTP 入力フォーム (allotplogin) が出た = 設定済み。 */
        CONFIGURED,
        /** OTP を挟まずポータルまで通った (学内アクセス)。設定済みかどうかはこれだけでは分からない。 */
        REACHED_PORTAL
    }

    private static boolean reachedPortal(@NonNull PostPasswordState state) {
        return "menu".equals(state.functionId)
                || state.result.url.contains("index.php")
                || state.result.html.contains("ログアウト")
                || state.result.html.contains("Logout");
    }

    /**
     * 通常経路 (学外) からパスワードだけで入り、OTP の設定状況を見る。SSO セッションを載せると IdP が
     * パスワード画面ごと飛ばしてしまい判定できないので、ここだけは必ずパスワードから入る
     * (2026-09-15 エミュレータで実測)。
     */
    @NonNull
    public OtpStatus checkOtpStatus(@NonNull String entryUrl, @NonNull String uid, @NonNull String pass) throws Exception {
        PostPasswordState state = authenticateUpToOtpDecision(entryUrl, uid, pass, false);
        if (state.result.html.contains(OTP_NOT_CONFIGURED_MARKER)) return OtpStatus.NOT_CONFIGURED;
        if ("allotplogin".equals(state.functionId)) return OtpStatus.CONFIGURED;
        if (reachedPortal(state)) return OtpStatus.REACHED_PORTAL;
        throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                "registration entry screen(FunctionID=" + state.functionId + ", url=" + state.result.url + ")");
    }

    /**
     * 学内アクセス (このクライアントが CampusSshSocksTunnel の proxy 付きで作られている前提) でパスワード
     * ログインし、ユーザーポータルに着いた jar で登録セッションを始める。
     *
     * 登録画面 (/user/index.php?app=motpmail 等) は sso/authorization.php を経由してログインセッション
     * (auth_tkt / samsessid) を要求する。空の cookie で叩くと tenantlogin に戻され「csrf が無い」で落ちる
     * (2026-09-19 に iOS 側の idp_verification/test_idp_full_auto.swift のトレースで確認)。学外の通常経路だと
     * 同じ画面が auth_err_020 に落ちるので、登録は必ず学内経路で行う。
     */
    @NonNull
    public SeciossRegistrationSession beginOtpRegistrationOnCampus(@NonNull String entryUrl, @NonNull String uid,
                                                                   @NonNull String pass) throws Exception {
        PostPasswordState state = authenticateUpToOtpDecision(entryUrl, uid, pass, false);
        if (state.result.html.contains(OTP_NOT_CONFIGURED_MARKER)) {
            // 学内経路のはずなのに学外扱い = トンネルが効いていない。
            throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                    "on-campus login still asked for OTP setup (tunnel not effective?)");
        }
        if ("allotplogin".equals(state.functionId)) {
            throw new SeciossError(SeciossError.Kind.OTP_ALREADY_CONFIGURED);
        }
        if (!reachedPortal(state)) {
            throw new SeciossError(SeciossError.Kind.UNRECOGNIZED_STATE,
                    "on-campus login did not reach the portal(FunctionID=" + state.functionId + ", url=" + state.result.url + ")");
        }
        return new SeciossRegistrationSession(this, new ArrayList<>(state.result.cookies));
    }
}
