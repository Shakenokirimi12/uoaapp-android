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

public class UoaApplication extends Application {
    /**
     * 今前面にいる Activity。OTP 入力ダイアログを出せるかの判定に使う (OtpPromptCoordinator)。
     * サービス層は Context を持たないので、ここで 1 箇所だけ持つ。
     */
    @Nullable private static volatile Activity foregroundActivity;

    /** 前面に Activity が無ければ null (バックグラウンド同期中など)。 */
    @Nullable
    public static Activity getForegroundActivity() { return foregroundActivity; }

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(@NonNull Activity activity) { foregroundActivity = activity; }

            @Override public void onActivityPaused(@NonNull Activity activity) {
                // 別の Activity が既に resume していたら、そちらを消さない。
                if (foregroundActivity == activity) foregroundActivity = null;
            }

            @Override public void onActivityDestroyed(@NonNull Activity activity) {
                if (foregroundActivity == activity) foregroundActivity = null;
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityStopped(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle out) { }
        });
        // Context を持たないサービス層が PreferenceManager.getInstance() で取れるよう、ここで初期化する。
        PreferenceManager.getInstance(this);
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
