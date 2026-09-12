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
import com.shakenokirimi12.uoa_app.BuildConfig;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.AppConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final String BASE_URL = "https://uoa-app-push.shakenokirimi12.workers.dev";

    private static AppConfigService instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final MutableLiveData<AppConfig> config = new MutableLiveData<>();

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

    /**
     * サーバーから取り直す。完了は callback と LiveData の両方で伝える。
     * メンテナンス画面の「再確認する」ボタンが完了を待ちたいので callback がある。
     */
    public void refresh(@NonNull Context context, @Nullable RefreshCallback callback) {
        final String deviceId = PreferenceManager.getInstance(context.getApplicationContext()).getDeviceId();
        executor.execute(() -> {
            boolean ok = false;
            try {
                OkHttpClient client = NetworkClient.getNoCookieClient();
                // Flagship の % ロールアウトが端末ごとに安定するよう deviceId を渡す (iOS と同じ)。
                // メンテナンス解除の再確認で古い応答を読まないよう、キャッシュは使わない。
                // os=android を付ける。強制更新の最低バージョンは iOS (3.x) と系列が違うので、
                // サーバーはこれを見て Android 向けの値 (未設定なら無し) を返す。
                Request req = new Request.Builder()
                        .url(BASE_URL + "/api/app-config?deviceId=" + deviceId + "&os=android")
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        AppConfig parsed = gson.fromJson(resp.body().string(), AppConfig.class);
                        if (parsed != null) {
                            config.postValue(parsed);
                            ok = true;
                        }
                    } else {
                        Log.w(TAG, "app-config HTTP " + resp.code());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "app-config fetch failed: " + e.getMessage());
            }
            if (callback != null) {
                final boolean result = ok;
                mainHandler.post(() -> callback.onDone(result));
            }
        });
    }

    @Nullable
    public String flagValue(@NonNull String key) {
        AppConfig c = config.getValue();
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

    /** 現在のバージョンが minRequiredVersion より古ければ true。 */
    public boolean isForceUpdateRequired() {
        AppConfig c = config.getValue();
        if (c == null || c.minRequiredVersion == null || c.minRequiredVersion.trim().isEmpty()) return false;
        return compareVersions(BuildConfig.VERSION_NAME, c.minRequiredVersion.trim()) < 0;
    }

    /** "1.2.3" 形式を数値で比較する。桁数が違えば足りない側を 0 として扱う。 */
    static int compareVersions(@NonNull String a, @NonNull String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
