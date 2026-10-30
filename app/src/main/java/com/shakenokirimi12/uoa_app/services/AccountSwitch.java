package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ユーザー名を別のアカウントに変える前 (確認のログインの前) の後始末。設定のアカウントのダイアログと初期設定で使う。
 *
 * 順序が大事。確認のログインのあとで捨てると、確認で得た新しいアカウントのセッションまで消えて次の同期で
 * もう一度ログインする。前のアカウントの SSO を残したまま確認すると、パスワードを送らずに通ってしまう
 * (PreferenceManager.discardAccountStateForSwitch)。
 */
public final class AccountSwitch {
    private AccountSwitch() {}

    /**
     * アカウントの世代。切り替えを始めたとき (prepare) と、切り替えが決まったとき (committed) に進む。
     * 取得は始めに世代を控え、結果を渡す直前に今の世代と比べる (Ticket)。入口の確認 (isStale) を通ったあとに
     * 切り替えが始まった取得は、前のアカウントのデータを画面やキャッシュに渡さずに捨てる。
     */
    private static final AtomicLong generation = new AtomicLong();

    /** 取得の始めに控える。 */
    @NonNull
    public static Ticket ticket() { return new Ticket(generation.get()); }

    /** 取得を始めたときの世代。結果を渡す直前に isCurrent で確かめる。 */
    public static final class Ticket {
        private final long generation;

        private Ticket(long generation) { this.generation = generation; }

        /** 控えてからアカウントの切り替えが始まっていなければ true。 */
        public boolean isCurrent() { return generation == AccountSwitch.generation.get(); }
    }

    /** 世代を進める。prepare と committed のほか、テストから呼ぶ。 */
    static void advance() { generation.incrementAndGet(); }

    /**
     * 確認のログインが通り、別のアカウントのユーザー名を保存したあとに呼ぶ (PreferenceManager.setUsername が
     * true を返したとき)。切り替えの確認の最中に前のアカウントで始まった取得も、ここで古くなる。
     */
    public static void committed() { advance(); }

    /** newUser が別のアカウントなら、前のアカウントの SSO・セッション・方式の控えを捨てて true。 */
    public static boolean prepare(@NonNull PreferenceManager prefs, @NonNull String newUser) {
        if (!prefs.isOtherAccount(newUser)) return false;
        advance();
        prefs.discardAccountStateForSwitch();
        // メモリ上のセッションと直前の失敗の記録も捨てる (バックオフをアカウントをまたいで残さない)。
        CampusSquareService.clearSession();
        MoodleService.clearSession();
        return true;
    }

    /**
     * username で始めた取得が、もう今のアカウントのものではないか (保存しているユーザー名が別にある)。
     * 切り替えの前に積まれた取得がここに来たら、ログインも保存もせずに止める (AccountChangedException)。
     */
    public static boolean isStale(@NonNull String username) {
        String current = PreferenceManager.getInstance().getUsername().trim();
        return !current.isEmpty() && !current.equals(username.trim());
    }
}
