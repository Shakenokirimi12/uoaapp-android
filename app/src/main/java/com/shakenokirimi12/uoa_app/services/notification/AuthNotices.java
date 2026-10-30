package com.shakenokirimi12.uoa_app.services.notification;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.UoaApplication;

/**
 * ログインについてのローカル通知 (SPEC.md「12. 通知」)。
 * - バックグラウンドのメール方式のログインで OTP メールの送信を頼むとき (届いたメールがアプリのものと分かるように)
 * - 保存している ID / パスワードが誤りと分かったとき (同じ資格情報では 1 回だけ。数えるのは呼び出し側)
 * 通知の許可が無ければ出さないだけで、ログインは続ける。「ログインが必要です」(セッション切れ) の通知は出さない。
 */
public final class AuthNotices {
    private static final int MAIL_DISPATCH_ID = 4102;
    private static final int CREDENTIALS_INVALID_ID = 4103;

    private AuthNotices() {}

    public static void postMailDispatch() {
        post(MAIL_DISPATCH_ID, R.string.auth_mail_dispatch_notify_title, R.string.auth_mail_dispatch_notify_text);
    }

    public static void postCredentialsInvalid() {
        post(CREDENTIALS_INVALID_ID, R.string.auth_credentials_invalid_title, R.string.auth_credentials_invalid_text);
    }

    private static void post(int id, @StringRes int title, @StringRes int text) {
        Context ctx = UoaApplication.getAppContext();
        if (ctx == null) return;
        try {
            show(ctx, id, ctx.getString(title), ctx.getString(text));
        } catch (RuntimeException e) {
            // 通知が出せなくてもログインの流れは止めない。
            android.util.Log.w("AuthNotices", "notification not posted: " + e.getClass().getSimpleName());
        }
    }

    private static void show(@NonNull Context ctx, int id, @NonNull String title, @Nullable String text) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        PendingIntent open = PendingIntent.getActivity(ctx, id,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NotificationChannels.ensureLoginRequired(ctx))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true);
        NotificationManagerCompat.from(ctx).notify(id, b.build());
    }
}
