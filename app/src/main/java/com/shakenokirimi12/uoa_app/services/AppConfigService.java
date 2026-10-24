package com.shakenokirimi12.uoa_app.services;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.AppConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.CacheControl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * サーバー側の設定 (feature flag / killswitch / メンテナンスモード / 強制更新) を取得する。
 * iOS の PushNotificationService.checkAppConfig と同じエンドポイントを読むだけで、
 * サーバー側の追加は無い。
 *
 * 大学側のサイト変更で機能が壊れたときに、アプリ更新なしでリモートから止めるためのもの。
 * 起動時と前面復帰時に取り直す。取得できていない間は「明示的に false でなければ有効」に倒す
 * (isFeatureEnabled)。これは iOS と同じ既定で、サーバーが落ちていてもアプリが止まらないようにするため。
 */
public final class AppConfigService {
    private static final String TAG = "AppConfigService";
    /** killswitch で止めた機能の呼び出しに返すメッセージ。 */
    public static final String FEATURE_DISABLED_MESSAGE = "この機能は現在利用できません";
    private static final String BASE_URL = "https://uoa-app.shakenokiri.me/push";

    private static AppConfigService instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final MutableLiveData<AppConfig> config = new MutableLiveData<>();
    /**
     * 最後に反映した応答。LiveData の値は postValue がメインスレッドで処理されるまで古いままなので、
     * Worker が refreshBlocking の直後に killswitch を読むときのためにここにも持つ。
     */
    @Nullable private volatile AppConfig latest;

    private AppConfigService() {}

    public static synchronized AppConfigService getInstance() {
        if (instance == null) instance = new AppConfigService();
        return instance;
    }

    public LiveData<AppConfig> config() { return config; }

    /** 取得済みの設定。まだ無ければ null。 */
    @Nullable
    public AppConfig current() { return config.getValue(); }

    public interface RefreshCallback { void onDone(boolean succeeded); }

    public void refresh(@NonNull Context context) { refresh(context, null); }

    // 取得に失敗したときの再試行。killswitch は「取れなければ有効」に倒しているので、
    // 起動直後に Wi-Fi がまだ繋がっていない等で 1 回落ちると、止めたはずの機能の入口が出続ける。
    // 以前は 3 回で打ち切り、しかも回数を成功するまで戻さなかったので、一度 3 回失敗すると
    // そのプロセスでは二度と再試行しなかった。間隔を延ばしながら、取れるまで続ける
    // (最長 5 分おき。プロセスが裏で凍結されている間は Handler も止まる)。
    // 起動や前面復帰からの取り直しは、間隔を最初からにする。
    private static final long[] RETRY_DELAYS_MS = {5_000L, 15_000L, 45_000L, 120_000L, 300_000L};
    private int retryAttempt = 0;
    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (retryContext == null) return;
            fetchInBackground(retryContext, null);
        }
    };
    @Nullable private Context retryContext;

    /** attempt 回目 (0 始まり) の再試行までの待ち時間。最後の間隔で頭打ちにする。 */
    static long retryDelayMs(int attempt) {
        return RETRY_DELAYS_MS[Math.min(Math.max(attempt, 0), RETRY_DELAYS_MS.length - 1)];
    }

    private void scheduleRetry(@NonNull Context appContext) {
        retryContext = appContext;
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.postDelayed(retryRunnable, retryDelayMs(retryAttempt));
        if (retryAttempt < RETRY_DELAYS_MS.length) retryAttempt++;
    }

    /**
     * サーバーから取り直す。完了は callback と LiveData の両方で伝える。
     * メンテナンス画面の「再確認する」ボタンが完了を待ちたいので callback がある。
     */
    public void refresh(@NonNull Context context, @Nullable RefreshCallback callback) {
        // 呼び出し側 (起動・前面復帰・再確認) からの取り直し。再試行の間隔を最初からにする。
        mainHandler.post(() -> retryAttempt = 0);
        fetchInBackground(context.getApplicationContext(), callback);
    }

    private void fetchInBackground(@NonNull Context appContext, @Nullable RefreshCallback callback) {
        executor.execute(() -> {
            final boolean result = fetchAndApply(appContext, NetworkClient.getNoCookieClient());
            mainHandler.post(() -> {
                if (result) {
                    retryAttempt = 0;
                    mainHandler.removeCallbacks(retryRunnable);
                } else {
                    scheduleRetry(appContext);
                }
                if (callback != null) callback.onDone(result);
            });
        });
    }

    /**
     * バックグラウンドの仕事 (WorkManager) から、通信を始める前に呼ぶ。呼んだスレッドで取り直して待つ。
     * 強制アップデートの対象になった版が裏で通信し続けないよう、この後で ForceUpdateGate を確かめること。
     * 取れなくても、前に取れた条件 (ForceUpdateGate が端末に残している) で判定できる。
     */
    public boolean refreshBlocking(@NonNull Context context, long timeoutMs) {
        OkHttpClient client = NetworkClient.getNoCookieClient().newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        return fetchAndApply(context.getApplicationContext(), client);
    }

    /**
     * 1 回取得して反映する。HTTP 2xx で JSON が読めて success が true のときだけ反映する。
     * それ以外 (通信の失敗・5xx・{} や success:false) は何も変えない。以前は JSON が読めれば
     * success を見ずに反映しており、{} が返ると強制更新の最低バージョンが消えて全面表示が外れた。
     */
    private boolean fetchAndApply(@NonNull Context appContext, @NonNull OkHttpClient client) {
        final String deviceId = PreferenceManager.getInstance(appContext).getDeviceId();
        try {
            // Flagship の % ロールアウトが端末ごとに安定するよう deviceId を渡す (iOS と同じ)。
            // メンテナンス解除の再確認で古い応答を読まないよう、キャッシュは使わない。
            // os=android を付ける。強制更新の最低バージョンは iOS (3.x) と系列が違うので、
            // サーバーはこれを見て Android 向けの値 (未設定なら無し) を返す。
            Request req = new Request.Builder()
                    .url(BASE_URL + "/api/app-config?deviceId=" + deviceId + "&os=android")
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.w(TAG, "app-config HTTP " + resp.code());
                    return false;
                }
                AppConfig parsed = gson.fromJson(resp.body().string(), AppConfig.class);
                if (!isUsable(parsed)) {
                    Log.w(TAG, "app-config response without success:true; ignoring");
                    return false;
                }
                Log.d(TAG, "app-config ok: " + parsed.flagsOrEmpty().size() + " flags, minRequiredVersion=" + parsed.minRequiredVersion);
                ForceUpdateGate.getInstance(appContext).onFetched(parsed);
                latest = parsed;
                config.postValue(parsed);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "app-config fetch failed: " + e.getMessage());
            return false;
        }
    }

    /** 反映してよい応答か。success が true のものだけ (エラー応答や {} は使わない)。 */
    static boolean isUsable(@Nullable AppConfig parsed) {
        return parsed != null && parsed.success;
    }

    @Nullable
    public String flagValue(@NonNull String key) {
        AppConfig c = latest;
        return c != null ? c.flagsOrEmpty().get(key) : null;
    }

    /** 明示的に "true" のときだけ有効。maintenance_mode のような「立てたときだけ効く」もの向け。 */
    public boolean isFlagEnabled(@NonNull String key) {
        return "true".equals(flagValue(key));
    }

    /**
     * killswitch 用。明示的に "false" のときだけ無効で、未設定・未取得なら有効。
     * サーバーが落ちている間もアプリの機能が止まらないようにするため、既定を有効側に倒す。
     */
    public boolean isFeatureEnabled(@NonNull String key) {
        return !"false".equals(flagValue(key));
    }

    /** ベース URL 等、値が空なら既定値へ戻す文字列フラグ。末尾のスラッシュは吸収する。 */
    @NonNull
    public String stringFlag(@NonNull String key, @NonNull String fallback) {
        String v = flagValue(key);
        if (v == null || v.trim().isEmpty()) return fallback;
        v = v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
