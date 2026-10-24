package com.shakenokirimi12.uoa_app.services.sync;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.notification.NotificationChannels;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * バックグラウンド同期で保存済みセッションが切れていたときの「アプリを開いてログインしてください」。
 *
 * バックグラウンドでログインし直せないとき (メール方式は 1 回 = OTP メール 1 通なので裏ではしない。認証アプリ方式でも
 * 鍵が無い・前回の失敗で止めているときはしない。BackgroundLoginPolicy)。代わりにユーザーに開いてもらう。
 * 同期は 15〜60 分ごとに走るので、毎回出すとうるさい。日本時間の 1 日 1 回までにする。
 */
public final class LoginRequiredNotice {
    private static final int NOTIFICATION_ID = 4101;

    private LoginRequiredNotice() {}

    /** 前回出した日 (yyyy-MM-dd, 日本時間) と今日から、今日出してよいか。 */
    static boolean shouldPost(@Nullable String lastPostedDay, @NonNull String today) {
        return !today.equals(lastPostedDay);
    }

    static String dayKey(long now) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        return f.format(new Date(now));
    }

    static void postAtMostOncePerDay(@NonNull Context ctx) {
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        String today = dayKey(System.currentTimeMillis());
        if (!shouldPost(prefs.getLoginRequiredNotifiedDay(), today)) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        PendingIntent open = PendingIntent.getActivity(ctx, NOTIFICATION_ID,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String text = ctx.getString(R.string.login_required_notify_text);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NotificationChannels.ensureLoginRequired(ctx))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(ctx.getString(R.string.login_required_notify_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true);
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, b.build());
        prefs.setLoginRequiredNotifiedDay(today);
    }

    static void cancel(@NonNull Context ctx) {
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
    }
}
