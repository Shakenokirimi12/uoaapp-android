package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

/**
 * ユーザー名を別のアカウントに変える前 (確認のログインの前) の後始末。設定のアカウントのダイアログと初期設定で使う。
 *
 * 順序が大事。確認のログインのあとで捨てると、確認で得た新しいアカウントのセッションまで消えて次の同期で
 * もう一度ログインする。前のアカウントの SSO を残したまま確認すると、パスワードを送らずに通ってしまう
 * (PreferenceManager.discardAccountStateForSwitch)。
 */
public final class AccountSwitch {
    private AccountSwitch() {}

    /** newUser が別のアカウントなら、前のアカウントの SSO・セッション・方式の控えを捨てて true。 */
    public static boolean prepare(@NonNull PreferenceManager prefs, @NonNull String newUser) {
        if (!prefs.isOtherAccount(newUser)) return false;
        prefs.discardAccountStateForSwitch();
        // メモリ上のセッションと直前の失敗の記録も捨てる (バックオフをアカウントをまたいで残さない)。
        CampusSquareService.clearSession();
        MoodleService.clearSession();
        return true;
    }
}
