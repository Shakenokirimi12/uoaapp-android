package com.shakenokirimi12.uoa_app.services.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.R;

/**
 * 複数の場所から使う通知チャンネルの定義をここに集める。
 *
 * チャンネルの名前と重要度は最初に作った側の値で固定され、後から作り直しても変わらない。
 * 同じ ID を 2 か所で別の名前・重要度で作っていると、どちらが先に走ったかで設定画面の表示が変わる
 * (class_ongoing が「授業通知」と「授業」で二重定義されていた)。
 */
public final class NotificationChannels {
    private NotificationChannels() {}

    /** 授業中・次の授業の常駐通知と、自動出席登録の完了通知。 */
    public static final String CLASS_ONGOING = "class_ongoing";

    /** 作成済みなら何もしない (createNotificationChannel は冪等)。 */
    @NonNull
    public static String ensureClassOngoing(@NonNull Context ctx) {
        NotificationChannel channel = new NotificationChannel(
                CLASS_ONGOING, ctx.getString(R.string.class_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(ctx.getString(R.string.class_channel_description));
        channel.setShowBadge(false);
        manager(ctx).createNotificationChannel(channel);
        return CLASS_ONGOING;
    }

    /** 学食メニューの通知 (1 日 1 回、設定した時刻)。 */
    public static final String LUNCH_MENU = "lunch_menu";

    @NonNull
    public static String ensureLunchMenu(@NonNull Context ctx) {
        manager(ctx).createNotificationChannel(new NotificationChannel(
                LUNCH_MENU, ctx.getString(R.string.lunch_channel_name), NotificationManager.IMPORTANCE_DEFAULT));
        return LUNCH_MENU;
    }

    /** バックグラウンド同期で「アプリを開いてログインしてください」を伝える (1 日 1 回まで)。 */
    public static final String LOGIN_REQUIRED = "login_required";

    @NonNull
    public static String ensureLoginRequired(@NonNull Context ctx) {
        manager(ctx).createNotificationChannel(new NotificationChannel(
                LOGIN_REQUIRED, ctx.getString(R.string.login_required_channel_name), NotificationManager.IMPORTANCE_DEFAULT));
        return LOGIN_REQUIRED;
    }

    private static NotificationManager manager(@NonNull Context ctx) {
        return ctx.getSystemService(NotificationManager.class);
    }
}
