package com.shakenokirimi12.uoa_app.ui.browser;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.NetworkClient;
import com.shakenokirimi12.uoa_app.ui.util.EdgeToEdge;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.Cookie;

/**
 * アプリのログイン済みセッションを引き継いで開くアプリ内ブラウザ。
 *
 * これまでは Chrome Custom Tabs で開いていたが、あれは別プロセスの Chrome なので
 * アプリの OkHttp が持つ cookie は届かず、課題を開くたびに Moodle のログインを
 * 求められていた。WebView の CookieManager へ cookie を写してから読み込む。
 *
 * 判断は iOS の InAppBrowser と揃えている (docs/webview-notes.md 参照):
 * 遷移先を学内と IdP に限定、ホスト名の常時表示、表示できないものはダウンロードへ。
 * Basic 認証は、知られている 1 か所 (BASIC_AUTH_HOST / BASIC_AUTH_REALM) にだけ、https で、
 * 利用者に毎回確かめてから保存済み資格情報を送る (その他のチャレンジは断る)。
 */
public class InAppBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";

    /**
     * この起動中に Basic 認証で資格情報を送った/断られた組み合わせ。
     * キーは host + realm + 資格情報のハッシュ。realm だけで覚えると、パスワードを直しても
     * アプリを終了するまで再試行できなくなる (iOS 側で同じ落とし穴を踏んだ)。
     */
    private static final Set<String> attemptedRealms = new HashSet<>();
    private static final Set<String> rejectedRealms = new HashSet<>();

    /**
     * 保存済みの資格情報を送ってよい唯一の Basic 認証。web-int.u-aizu.ac.jp は Apache の
     * `WWW-Authenticate: Basic realm="web-int-proxy"` が掛かっている (iOS の docs/webview-notes.md)。
     * 学内の他のホストは教員の個人ページや研究室のサーバも含み、そこが 401 を返しただけで大学の
     * パスワードを渡すことになるので、ホストと realm の完全一致で絞る。
     */
    static final String BASIC_AUTH_HOST = "web-int.u-aizu.ac.jp";
    static final String BASIC_AUTH_REALM = "web-int-proxy";

    /** 保存済みの資格情報で答えてよいチャレンジか。ページが https で、ホストと realm が完全一致するときだけ。 */
    static boolean mayOfferStoredCredentials(@Nullable String host, @Nullable String realm, @Nullable String pageUrl) {
        if (host == null || realm == null || pageUrl == null) return false;
        return pageUrl.regionMatches(true, 0, "https://", 0, 8)
                && BASIC_AUTH_HOST.equalsIgnoreCase(host)
                && BASIC_AUTH_REALM.equals(realm);
    }

    /** 確認ダイアログの答えを待っているチャレンジ (同じ realm に同時に来た分もまとめて答える)。 */
    private final java.util.Map<String, List<HttpAuthHandler>> pendingAuth = new java.util.HashMap<>();

    private static String realmKey(String host, String realm, String user, String pass) {
        // パスワードそのものは持たない。
        return host + "/" + realm + "#" + (user + "\u0000" + pass).hashCode();
    }

    private WebView webView;
    private LinearProgressIndicator progress;
    private TextView titleView;
    private TextView hostView;
    private MaterialButton back, forward, reload;

    /**
     * ログアウト時に呼ぶ。CookieManager はプロセスをまたいでディスクに残る。
     * WebView の Basic 認証の保存先 (http_auth.db、平文) も消す。今はそこへ書かないが、
     * 以前の版が setHttpAuthUsernamePassword で大学のパスワードを書いていた。
     */
    public static void clearWebSession(@NonNull Context context) {
        rejectedRealms.clear();
        attemptedRealms.clear();
        // WebView が使えない (無効化・更新中) 端末では getInstance が例外を投げる。ログアウトを止めない。
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            android.webkit.WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword();
        } catch (RuntimeException e) {
            android.util.Log.w("InAppBrowser", "could not clear WebView data on logout: " + e);
        }
    }

    /**
     * 以前の版が http_auth.db に平文で書いたパスワードを、更新後に 1 度だけ消す (UoaApplication から)。
     * WebView が使えない (更新中など) ときは印を付けず、次の起動でやり直す。
     */
    public static void clearLegacyHttpAuthOnce(@NonNull Context context) {
        android.content.SharedPreferences marks =
                context.getSharedPreferences("webview_migrations", Context.MODE_PRIVATE);
        if (marks.getBoolean("http_auth_cleared_v1", false)) return;
        try {
            android.webkit.WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword();
            marks.edit().putBoolean("http_auth_cleared_v1", true).apply();
        } catch (RuntimeException e) {
            android.util.Log.w("InAppBrowser", "could not clear WebView http auth: " + e);
        }
    }

    public static void open(@NonNull Context context, @NonNull String url) {
        Intent i = new Intent(context, InAppBrowserActivity.class);
        i.putExtra(EXTRA_URL, url);
        context.startActivity(i);
    }

    /** このWebView内での遷移を許すホスト。学内とSAMLのIdPのみ。 */
    static boolean isAllowedHost(@Nullable String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("u-aizu.ac.jp")
                || host.endsWith(".u-aizu.ac.jp")
                || host.equals("slink.secioss.com");
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 強制アップデートの対象の版では、cookie を写したり大学のページを読み込んだりする前に止める。
        // UoaApplication の差し替えは onResume なので、ここで止めないと通信が先に出る
        // (タスクの復元や通知から直接開かれた場合)。
        if (com.shakenokirimi12.uoa_app.services.ForceUpdateGate.getInstance(this).isBlocked()) {
            com.shakenokirimi12.uoa_app.ui.ForceUpdateActivity.show(this);
            return;
        }
        setContentView(R.layout.activity_in_app_browser);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) { finish(); return; }

        // 初回の loadUrl() には shouldOverrideUrlLoading が掛からない。ここで弾かないと、
        // 通知の url など外から来た値で任意のホストをこの画面の中に開いてしまう。
        Uri initial = Uri.parse(url);
        String scheme = initial.getScheme() != null ? initial.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!(scheme.equals("http") || scheme.equals("https")) || !isAllowedHost(initial.getHost())) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, initial));
            } catch (Exception ignored) {
                // 開けるアプリが無いだけ
            }
            finish();
            return;
        }

        webView = findViewById(R.id.web_view);
        progress = findViewById(R.id.progress);
        titleView = findViewById(R.id.title);
        hostView = findViewById(R.id.host);
        back = findViewById(R.id.nav_back);
        forward = findViewById(R.id.nav_forward);
        reload = findViewById(R.id.nav_reload);

        EdgeToEdge.apply(this, findViewById(R.id.browser_root), findViewById(R.id.toolbar_bottom));

        findViewById(R.id.close).setOnClickListener(v -> finish());
        findViewById(R.id.menu).setOnClickListener(this::showMenu);
        findViewById(R.id.nav_open_external).setOnClickListener(v -> openExternal());
        back.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        forward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        reload.setOnClickListener(v -> {
            if (progress.getVisibility() == View.VISIBLE) webView.stopLoading(); else webView.reload();
        });

        // 戻るキーはまず WebView の履歴を戻る。
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack(); else finish();
            }
        });

        setupWebView();
        hostView.setText(Uri.parse(url).getHost());
        ensureSessionThenLoad(url);
    }

    /**
     * cookie の入れ物はプロセス内メモリなので、起動直後や長時間放置の後は空のことがある。
     * その状態で読み込むと Moodle のログイン画面が出る (iOS の ensureLoggedIn に相当)。
     * Moodle のホストで cookie が無ければ、先にログインしてから読み込む。
     */
    private void ensureSessionThenLoad(String url) {
        String host = Uri.parse(url).getHost();
        PreferenceManager prefs = PreferenceManager.getInstance(this);
        boolean isMoodle = host != null && host.equals(Uri.parse(
                com.shakenokirimi12.uoa_app.services.MoodleService.currentBaseUrl()).getHost());
        boolean hasSession = !NetworkClient.cookiesForHost(host).isEmpty();

        if (!isMoodle || hasSession || !prefs.hasCredentials() || prefs.isCredentialsInvalid()) {
            injectCookies(url);
            webView.loadUrl(url);
            return;
        }

        progress.setVisibility(View.VISIBLE);
        new com.shakenokirimi12.uoa_app.services.MoodleService().ensureLoggedIn(
                prefs.getUsername(), prefs.getPassword(),
                com.shakenokirimi12.uoa_app.services.AuthorizationContext.INTERACTIVE,
                new com.shakenokirimi12.uoa_app.services.ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (isFinishing() || isDestroyed()) return;
                injectCookies(url);
                webView.loadUrl(url);
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                com.shakenokirimi12.uoa_app.services.MoodleService
                        .markInvalidIfCredentialsError(InAppBrowserActivity.this, message);
                // ログインできなくても開く。ページ側のログイン画面が逃げ道になる。
                injectCookies(url);
                webView.loadUrl(url);
            }
        });
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 同じ WebView で target="_blank" を開くため。複数ウィンドウを許すと
        // WebChromeClient.onCreateWindow の実装が要り、無ければリンクが無反応になる。
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(NetworkClient.getUserAgent());
        // targetSdk 21 以降の既定だが、Basic 認証の https 判定がこれに依存するので明示する。
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // iframe の読み込みもここへ来る。区別しないと、埋め込み動画のような許可外
                // ホストの iframe があるだけで、ユーザーが何もしていないのに外部アプリが起動する。
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
                // ページ自身が作ったコンテンツは通す。
                if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data")) return false;
                // 大学のセッションcookieを積んだWebViewなので、学内とIdP以外へは行かせない。
                // 外部サイトと mailto:/tel: はシステムに渡す。
                if ((scheme.equals("http") || scheme.equals("https")) && isAllowedHost(uri.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    // 開けるアプリが無いだけ。WebView 側で読み込まないことは変わらない。
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                hostView.setText(Uri.parse(url).getHost());
                updateNav();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                updateNav();
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                // Basic 認証。保存済みの資格情報 (大学のパスワード) を渡してよいのは web-int の 1 か所だけで、
                // 渡す前に毎回利用者に確かめる。他のチャレンジは断る (学内の任意のホストが 401 を返すだけで
                // パスワードを持っていけた。CLAUDE-SECURITY F3)。
                //
                // https の判定はページの URL で行う。チャレンジ元のリソースの URL はここでは
                // 分からないが、setMixedContentMode(NEVER_ALLOW) により https ページ内の
                // 平文サブリソースは読み込まれないので、ページが https なら平文経路は無い。
                PreferenceManager prefs = PreferenceManager.getInstance(InAppBrowserActivity.this);
                String user = prefs.getUsername();
                String pass = prefs.getPassword();
                // ID/PW 誤りが確定している間は送らない。ここも「大学の資格情報を送る経路」なので
                // 自動同期と同じ理由でロックアウト対策の対象になる。
                if (!mayOfferStoredCredentials(host, realm, view.getUrl()) || prefs.isCredentialsInvalid()
                        || user == null || user.isEmpty() || pass == null || pass.isEmpty()) {
                    handler.cancel();
                    Toast.makeText(InAppBrowserActivity.this,
                            getString(R.string.browser_auth_failed, host), Toast.LENGTH_LONG).show();
                    return;
                }

                String key = realmKey(host, realm, user, pass);

                // 断られた組み合わせはこの起動中もう送らない。弾かれた資格情報を送り直すと
                // 大学側の試行回数を消費してアカウントロックへ近づく。
                //
                // 「断られた」の判定: 一度送った後に同じ realm で再び聞かれ、かつ WebView が
                // 「保存済みの資格情報は使えない」と言っている (useHttpAuthUsernamePassword が偽 =
                // サーバーに一度拒否された) とき。同一 realm のサブリソースが同時にチャレンジ
                // された場合はまだ拒否されていないので真のままで、結果が返る前の 2 件目を
                // 「断られた」と誤認しない。
                if (rejectedRealms.contains(key)
                        || (attemptedRealms.contains(key) && !handler.useHttpAuthUsernamePassword())) {
                    rejectedRealms.add(key);
                    handler.cancel();
                    Toast.makeText(InAppBrowserActivity.this,
                            getString(R.string.browser_auth_failed, host), Toast.LENGTH_LONG).show();
                    return;
                }

                List<HttpAuthHandler> waiting = pendingAuth.get(key);
                if (waiting != null) {
                    waiting.add(handler);
                    return;
                }
                waiting = new java.util.ArrayList<>();
                waiting.add(handler);
                pendingAuth.put(key, waiting);
                confirmBasicAuth(host, key, user, pass);
            }
        });

        // これを設定しないと JS の alert/confirm が出ない (既定実装が組み込みダイアログを出す)。
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                titleView.setText(title != null ? title : "");
            }
        });

        // 表示できないもの (添付ファイル等) は端末のダウンロードへ。
        // WebView は cookie を DownloadManager に自動では渡さないので、ここで付ける。
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.addRequestHeader("User-Agent", userAgent);
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                req.setTitle(name);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(req);
                Toast.makeText(this, getString(R.string.browser_download_started, name), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, R.string.browser_download_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * アプリの OkHttp が持つ cookie のうち、対象ホスト宛のものだけを WebView へ写す。
     * cookie の入れ物はホスト単位なので、csweb や SECIOSS の cookie を巻き込まない。
     */
    private void injectCookies(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null) return;
        List<Cookie> cookies = NetworkClient.cookiesForHost(host);
        CookieManager cm = CookieManager.getInstance();
        String origin = uri.getScheme() + "://" + host;
        for (Cookie c : cookies) {
            StringBuilder sb = new StringBuilder();
            sb.append(c.name()).append('=').append(c.value());
            sb.append("; Path=").append(c.path().isEmpty() ? "/" : c.path());
            // ドメイン cookie は Domain を付けないと、書き込み先ホスト限定に格下げされて
            // 同じドメインの別ホストへ遷移したときに送られない。
            if (!c.hostOnly()) sb.append("; Domain=").append(c.domain());
            if (c.secure()) sb.append("; Secure");
            if (c.httpOnly()) sb.append("; HttpOnly");
            cm.setCookie(origin, sb.toString());
        }
        cm.flush();
    }

    private void updateNav() {
        back.setEnabled(webView.canGoBack());
        back.setAlpha(webView.canGoBack() ? 1f : 0.3f);
        forward.setEnabled(webView.canGoForward());
        forward.setAlpha(webView.canGoForward() ? 1f : 0.3f);
    }

    private void openExternal() {
        String current = webView.getUrl();
        if (current == null) return;
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(current)));
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.browser_share);
        menu.getMenu().add(0, 2, 1, R.string.browser_open_external);
        menu.setOnMenuItemClickListener(item -> {
            String current = webView.getUrl();
            if (current == null) return true;
            if (item.getItemId() == 1) {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, current);
                startActivity(Intent.createChooser(send, null));
            } else {
                openExternal();
            }
            return true;
        });
        menu.show();
    }

    /**
     * 大学の ID とパスワードを送るか確かめる。WebView の保存先 (http_auth.db) には書かない
     * (setHttpAuthUsernamePassword は平文の SQLite に書き、ログアウトでも消えなかった。CLAUDE-SECURITY F10)。
     * 送った資格情報は WebView がこの起動中だけメモリに持つ。
     */
    private void confirmBasicAuth(String host, String key, String user, String pass) {
        if (isFinishing() || isDestroyed()) {
            answerBasicAuth(key, null, null);
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.browser_auth_confirm_title)
                .setMessage(getString(R.string.browser_auth_confirm_message, host))
                .setPositiveButton(R.string.browser_auth_confirm_send, (d, w) -> answerBasicAuth(key, user, pass))
                .setNegativeButton(R.string.cancel, (d, w) -> answerBasicAuth(key, null, null))
                .setOnCancelListener(d -> answerBasicAuth(key, null, null))
                .show();
    }

    /** user が null なら断る。 */
    private void answerBasicAuth(String key, @Nullable String user, @Nullable String pass) {
        List<HttpAuthHandler> waiting = pendingAuth.remove(key);
        if (waiting == null) return;
        if (user != null) attemptedRealms.add(key);
        for (HttpAuthHandler h : waiting) {
            if (user != null) h.proceed(user, pass);
            else h.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        // 答えを待っているチャレンジは断ってから閉じる (放置すると WebView の読み込みが止まったままになる)。
        for (List<HttpAuthHandler> waiting : pendingAuth.values()) {
            for (HttpAuthHandler h : waiting) h.cancel();
        }
        pendingAuth.clear();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
