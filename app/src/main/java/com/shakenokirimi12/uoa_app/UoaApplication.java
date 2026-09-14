package com.shakenokirimi12.uoa_app;

import android.app.Application;
import android.app.LocaleManager;
import android.os.Build;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

public class UoaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
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
