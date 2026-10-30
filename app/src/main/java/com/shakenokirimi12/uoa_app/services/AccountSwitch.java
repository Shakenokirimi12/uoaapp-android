package com.shakenokirimi12.uoa_app.services;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import com.shakenokirimi12.uoa_app.services.notification.AssignmentCountdownNotifier;
import com.shakenokirimi12.uoa_app.services.notification.ClassNotificationService;
import com.shakenokirimi12.uoa_app.services.push.AssignmentReminderSync;
import com.shakenokirimi12.uoa_app.widget.ClassScheduleWidgetProvider;

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
     * アカウントの世代。切り替えを始めたとき (prepare)、切り替えが決まったとき (committed)、ログアウト (loggedOut) で進む。
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

    /** 世代を進めることと runIfCurrent の確認から保存までを、互いに割り込ませない。 */
    private static final Object lock = new Object();

    /** 確認のログインで読んだ、まだ保存していないアカウントの OTP の方式 (notePendingOtpMethod)。lock で守る。 */
    @Nullable private static String pendingOtpUser;
    @Nullable private static OtpMethod pendingOtpMethod;

    /** 世代を進める。prepare・committed・loggedOut のほか、テストから呼ぶ。 */
    static void advance() {
        synchronized (lock) { generation.incrementAndGet(); }
    }

    /**
     * ticket がまだ今の世代なら action を実行して true。メインスレッドの外で結果を保存する取得 (SyncWorker) 用。
     * 確認と保存のあいだに切り替えやログアウトが割り込むと、片付けたあとのキャッシュに前のアカウントの
     * データを書き戻すので、世代を進める側と同じロックの中で両方を行う。
     */
    public static boolean runIfCurrent(@NonNull Ticket ticket, @NonNull Runnable action) {
        synchronized (lock) {
            if (!ticket.isCurrent()) return false;
            action.run();
            return true;
        }
    }

    /**
     * 確認のログインが通り、別のアカウントのユーザー名を保存したあとに呼ぶ (PreferenceManager.setUsername が
     * true を返したとき)。切り替えの確認の最中に前のアカウントで始まった取得も、ここで古くなる。
     * 前のアカウントの画面のキャッシュと課題のリマインダーも、ログアウトと同じように片付ける。残すと、次に取り直す
     * までの間、新しいアカウントの画面・ウィジェット・通知に前のアカウントの成績や課題が出る。
     */
    public static void committed(@NonNull Context context, @NonNull PreferenceManager prefs, @NonNull String newUser) {
        advance();
        // 確認のログインの最中は、保存しているユーザー名がまだ前のアカウントなので、読んだ方式を控えに書けなかった
        // (OtpProviders)。ユーザー名が決まったここで書く。
        OtpMethod method = takePendingOtpMethod(newUser);
        if (method != null) prefs.recordOtpMethodFromIdp(method);
        // サーバーの予約は今の deviceId のものなので、前のアカウントの課題の分を空にしておく。新しいアカウントの分は
        // 次の課題の取得 (sync) で入る。
        AssignmentReminderSync.clear(context);
        clearAccountCaches(context);
    }

    /** ログアウトで呼ぶ。ログアウトの前に始まった取得の結果を、空にしたキャッシュに書き戻さない。 */
    public static void loggedOut() { advance(); }

    /**
     * アカウントのデータを映している画面のキャッシュ・ウィジェット・通知を片付ける。ログアウトと切り替えで共通。
     * ウィジェットは資格情報の有無も見るので、ログアウトでは prefs.clearAll のあとに呼ぶ。
     */
    public static void clearAccountCaches(@NonNull Context context) {
        DataCache.getInstance(context).clearAll();
        // 放置すると次の定期更新まで前のユーザーの時間割が出続ける。
        ClassScheduleWidgetProvider.refresh(context);
        AssignmentCountdownNotifier.clearAll(context);
        ClassNotificationService.stop(context);
    }

    /**
     * 保存しているアカウントとは別の uid で確認のログインをしている最中に、IdP の OTP 入力画面から読んだ方式を
     * 控えておく (OtpProviders)。このときの控えは前のアカウントの欄なので、そこには書かない。
     */
    public static void notePendingOtpMethod(@NonNull String uid, @NonNull OtpMethod method) {
        synchronized (lock) {
            pendingOtpUser = uid;
            pendingOtpMethod = method;
        }
    }

    /** uid の確認のログインで控えた方式を取り出して消す。別の uid の控えなら null (それも消す)。 */
    @Nullable
    static OtpMethod takePendingOtpMethod(@NonNull String uid) {
        synchronized (lock) {
            OtpMethod method = uid.equals(pendingOtpUser) ? pendingOtpMethod : null;
            pendingOtpUser = null;
            pendingOtpMethod = null;
            return method;
        }
    }

    /** newUser が別のアカウントなら、前のアカウントの SSO・セッション・方式の控えを捨てて true。 */
    public static boolean prepare(@NonNull PreferenceManager prefs, @NonNull String newUser) {
        if (!prefs.isOtherAccount(newUser)) return false;
        advance();
        // 前に試して確認が通らなかったアカウントの方式を、今回の確認の結果と取り違えない。
        takePendingOtpMethod(newUser);
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
