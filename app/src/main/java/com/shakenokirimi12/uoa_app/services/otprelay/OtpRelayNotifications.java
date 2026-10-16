package com.shakenokirimi12.uoa_app.services.otprelay;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.services.notification.NotificationChannels;
import com.shakenokirimi12.uoa_app.ui.otprelay.OtpRelayActivity;

/**
 * PC 中継の通知。文面は iOS (NotificationService / AuthPresentations) に合わせる。
 * 1 件の依頼につき通知は 1 つ (requestId から決めた ID を差し替えていく)。
 */
public final class OtpRelayNotifications {
    private static final String TAG = "OtpRelay";

    private OtpRelayNotifications() {}

    static int notificationId(@NonNull String requestId) {
        return 0x52000000 | (requestId.hashCode() & 0x00ffffff);
    }

    /** メールを読んでいる間 (Worker の前景表示にも使う)。 */
    @NonNull
    static Notification progress(@NonNull Context ctx, @NonNull String browserName) {
        return new NotificationCompat.Builder(ctx, NotificationChannels.ensureOtpRelayProgress(ctx))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle("「" + browserName + "」の OTP")
                .setContentText("大学のメールから OTP を読んでいます")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * 承認を求める。「承認して送る」は端末のロック解除を要求する (API 31+)。
     * @param escalated 自動で送れなかった (メールが届かなかった・中継に返せなかった) ために出す場合。
     */
    static void approval(@NonNull Context ctx, @NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request) {
        int id = notificationId(request.requestId);
        PendingIntent approve = OtpRelayActionReceiver.pendingIntent(ctx, OtpRelayActionReceiver.ACTION_APPROVE, request, id);
        PendingIntent reject = OtpRelayActionReceiver.pendingIntent(ctx, OtpRelayActionReceiver.ACTION_REJECT, request, id);
        Intent open = OtpRelayActivity.requestIntent(ctx, request);
        PendingIntent content = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action approveAction = new NotificationCompat.Action.Builder(0, "承認して送る", approve)
                .setAuthenticationRequired(true)
                .build();
        NotificationCompat.Action rejectAction = new NotificationCompat.Action.Builder(0, "拒否", reject).build();
        String body = "「承認して送る」を押すと、大学のメールに届いたコードを PC に送ります。心当たりが無ければ拒否してください。";
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NotificationChannels.ensureOtpRelay(ctx))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle("「" + pairing.browserName + "」が OTP を求めています")
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(content)
                .setAutoCancel(true)
                .addAction(approveAction)
                .addAction(rejectAction);
        long remaining = request.expiresAt - System.currentTimeMillis();
        if (remaining > 0) b.setTimeoutAfter(remaining);
        notify(ctx, id, b.build());
    }

    static void sent(@NonNull Context ctx, @NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request) {
        String body = "「" + pairing.browserName + "」の OTP 画面に入力します。心当たりが無ければ、アプリの設定から連携を解除してください。";
        result(ctx, request, "PC にコードを送りました", body, false);
    }

    static void failed(@NonNull Context ctx, @NonNull OtpRelayRequest request, @NonNull String reason) {
        result(ctx, request, "PC に OTP を送れませんでした", OtpRelayResponder.failureText(reason), true);
    }

    static void cancel(@NonNull Context ctx, @NonNull OtpRelayRequest request) {
        NotificationManagerCompat.from(ctx).cancel(notificationId(request.requestId));
    }

    private static void result(Context ctx, OtpRelayRequest request, String title, String body, boolean alert) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NotificationChannels.ensureOtpRelay(ctx))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setOnlyAlertOnce(!alert)
                .setSilent(!alert)
                .setAutoCancel(true);
        notify(ctx, notificationId(request.requestId), b.build());
    }

    private static void notify(Context ctx, int id, Notification n) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; relay notification dropped");
            return;
        }
        NotificationManagerCompat.from(ctx).notify(id, n);
    }
}
