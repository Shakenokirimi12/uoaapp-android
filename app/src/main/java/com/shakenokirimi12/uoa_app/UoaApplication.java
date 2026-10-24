package com.shakenokirimi12.uoa_app;

import android.app.Activity;
import android.app.Application;
import android.app.LocaleManager;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.services.ForceUpdateGate;
import com.shakenokirimi12.uoa_app.ui.ForceUpdateActivity;

public class UoaApplication extends Application {
    /**
     * 今前面にいる Activity。OTP 入力ダイアログを出せるかの判定に使う (OtpPromptCoordinator)。
     * サービス層は Context を持たないので、ここで 1 箇所だけ持つ。
     */
    @Nullable private static volatile Activity foregroundActivity;

    /** 前面に Activity が無ければ null (バックグラウンド同期中など)。 */
    @Nullable
    public static Activity getForegroundActivity() { return foregroundActivity; }

    /** 開始している (画面に見えている) Activity の数。0 から 1 になったときがアプリの前面復帰。 */
    private int startedActivities = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        // 同期結果の通知は消した。端末の通知設定に、もう使わないチャンネルを残さない。
        getSystemService(android.app.NotificationManager.class)
                .deleteNotificationChannel(com.shakenokirimi12.uoa_app.services.sync.SyncWorker.REMOVED_CHANNEL_ID);
        // 端末に残っている強制アップデートの条件を、最初の画面より先に読む (取得を待たずに止めるため)。
        ForceUpdateGate gate = ForceUpdateGate.getInstance(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(@NonNull Activity activity) {
                foregroundActivity = activity;
                // どの画面 (初期設定・アプリ内ブラウザ・通知から開いた画面) から入っても止める。
                if (gate.isBlocked() && !(activity instanceof ForceUpdateActivity)) {
                    ForceUpdateActivity.show(activity);
                }
            }

            @Override public void onActivityPaused(@NonNull Activity activity) {
                // 別の Activity が既に resume していたら、そちらを消さない。
                if (foregroundActivity == activity) foregroundActivity = null;
            }

            @Override public void onActivityDestroyed(@NonNull Activity activity) {
                if (foregroundActivity == activity) foregroundActivity = null;
            }

            @Override public void onActivityStarted(@NonNull Activity activity) {
                // 起動時と前面復帰のたびに、どの画面から入ってもリモート設定 (強制更新・メンテナンス・
                // killswitch) を取り直す。以前は MainActivity.onResume だけで、初期設定や通知から開いた
                // 画面では取り直していなかった。
                if (startedActivities++ == 0) {
                    AppConfigService.getInstance().refresh(activity);
                    // 前日までの利用状況があれば送る (1 日 1 回。送る分が無ければ通信しない)。
                    com.shakenokirimi12.uoa_app.services.usage.UsageStats.sendIfDueAsync(activity);
                }
            }

            @Override public void onActivityStopped(@NonNull Activity activity) {
                if (startedActivities > 0) startedActivities--;
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle out) { }
        });
        // 画面を開いている間に取得の結果で止める必要が出たら、その場で差し替える。
        gate.blocked().observeForever(blocked -> {
            Activity current = foregroundActivity;
            if (Boolean.TRUE.equals(blocked) && gate.isBlocked()
                    && current != null && !(current instanceof ForceUpdateActivity) && !current.isFinishing()) {
                ForceUpdateActivity.show(current);
            }
        });
        // Context を持たないサービス層が PreferenceManager.getInstance() で取れるよう、ここで初期化する。
        PreferenceManager.getInstance(this);
        // 以前の版がアプリ内ブラウザの Basic 認証で WebView の http_auth.db (平文) に書いたパスワードを消す。
        com.shakenokirimi12.uoa_app.ui.browser.InAppBrowserActivity.clearLegacyHttpAuthOnce(this);
        // The UI is Japanese only. Without a Japanese locale on the context, Android picks
        // Chinese glyph forms for kanji (Han unification), which is what an English-locale
        // device or emulator shows. Pin the per-app locale so text always renders with the
        // Japanese font, independent of the device language.
        if (Build.VERSION.SDK_INT >= 33) {
            // AppCompatDelegate only forwards to LocaleManager once an activity delegate exists,
            // so from Application.onCreate it is a no-op on 33+. Call the framework directly.
            LocaleManager lm = getSystemService(LocaleManager.class);
            if (lm != null && lm.getApplicationLocales().isEmpty()) {
                lm.setApplicationLocales(LocaleList.forLanguageTags("ja"));
            }
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ja"));
        }
    }
}
