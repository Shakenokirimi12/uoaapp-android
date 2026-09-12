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
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;

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
 * 遷移先を学内と IdP に限定、ホスト名の常時表示、Basic 認証は学内 https にのみ
 * 保存済み資格情報を 1 回だけ、表示できないものはダウンロードへ。
 */
public class InAppBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";

    /** この起動中に、保存済み資格情報を断られた Basic 認証の realm (host + realm)。 */
    private static final Set<String> rejectedRealms = new HashSet<>();

    private WebView webView;
    private ProgressBar progress;
    private TextView titleView;
    private TextView hostView;
    private ImageButton back, forward, reload;

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
        setContentView(R.layout.activity_in_app_browser);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) { finish(); return; }

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
        injectCookies(url);
        hostView.setText(Uri.parse(url).getHost());
        webView.loadUrl(url);
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 同じ WebView で target="_blank" を開くため。複数ウィンドウを許すと
        // WebChromeClient.onCreateWindow の実装が要り、無ければリンクが無反応になる。
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(NetworkClient.getUserAgent());

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
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
                // Basic認証。web-int.u-aizu.ac.jp のように、Moodleのセッションとは別に
                // 認証が掛かっているホストが学内にある。
                // 送るのは学内ホスト、かつ https のときだけ。断られた組み合わせはこの起動中
                // もう送らない。弾かれた資格情報を送り直すと大学側の試行回数を消費して
                // アカウントロックへ近づく。
                String key = host + "/" + realm;
                boolean https = view.getUrl() != null && view.getUrl().startsWith("https://");
                if (!isAllowedHost(host) || !https || rejectedRealms.contains(key)) {
                    handler.cancel();
                    if (rejectedRealms.contains(key)) {
                        Toast.makeText(InAppBrowserActivity.this,
                                getString(R.string.browser_auth_failed, host), Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                PreferenceManager prefs = PreferenceManager.getInstance(InAppBrowserActivity.this);
                String user = prefs.getUsername();
                String pass = prefs.getPassword();
                if (user == null || user.isEmpty() || pass == null || pass.isEmpty()) {
                    handler.cancel();
                    return;
                }
                // 送った事実を先に記録する。同じ realm で再び聞かれたら「断られた」と分かる。
                rejectedRealms.add(key);
                handler.proceed(user, pass);
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
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(current)));
            }
            return true;
        });
        menu.show();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
