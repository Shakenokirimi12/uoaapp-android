package com.shakenokirimi12.uoa_app.services.otprelay;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.services.notification.NotificationChannels;
import com.shakenokirimi12.uoa_app.ui.otprelay.OtpRelayConsentActivity;

/**
 * PC 中継の通知。文面は iOS (OTPRelayNotificationService) に合わせる。操作のボタンは無い
 * (依頼ごとの承認は 2026-10-21 に廃止)。1 件の依頼につき通知は 1 つ (requestId から決めた ID を差し替えていく)。
 */
public final class OtpRelayNotifications {
    private static final String TAG = "OtpRelay";

    private OtpRelayNotifications() {}

    private static final String FAILED_TITLE = "PC に OTP を送れませんでした";

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

    /** 前景サービスの表示 (受信直後で、まだメールを読むかどうか決まっていない段階にも出る)。 */
    @NonNull
    static Notification working(@NonNull Context ctx, @NonNull String browserName) {
        return new NotificationCompat.Builder(ctx, NotificationChannels.ensureOtpRelayProgress(ctx))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle("「" + browserName + "」の OTP")
                .setContentText("PC からの OTP 依頼を処理しています")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    static void sent(@NonNull Context ctx, @NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request) {
        String body = "「" + pairing.browserName + "」の OTP 画面に入力されます。心当たりがなければ、アプリの設定から連携を解除してください。";
        result(ctx, request, "PC に OTP を送りました", body, false, null);
    }

    static void failed(@NonNull Context ctx, @NonNull OtpRelayRequest request, @Nullable String reason) {
        result(ctx, request, FAILED_TITLE, OtpRelayResponder.failureText(reason), true, null);
    }

    /** 見張りが時間切れの失敗を返し、中継サーバが受け付けた。 */
    static void timedOut(@NonNull Context ctx, @NonNull OtpRelayRequest request) {
        result(ctx, request, FAILED_TITLE,
                "時間内に大学のメールから OTP を読み取れませんでした。PC では、メールに届いた OTP を入力してください。", true, null);
    }

    /** 見張りが失敗を送ったが、届いたか分からない。事実と違うことを言わず、確かめ方だけを伝える。 */
    static void unknownResult(@NonNull Context ctx, @NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request) {
        result(ctx, request, "「" + pairing.browserName + "」からの OTP の依頼",
                "PC に OTP が入力されない場合は、メールに届いた OTP を入力してください。", true, null);
    }

    /** 同意を記録する前に作った連携の依頼を断った。押すとアプリで確認が出る。 */
    static void consentRequired(@NonNull Context ctx, @NonNull OtpRelayPairing pairing, @NonNull OtpRelayRequest request) {
        int id = notificationId(request.requestId);
        PendingIntent open = PendingIntent.getActivity(ctx, id, OtpRelayConsentActivity.intent(ctx),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        result(ctx, request, FAILED_TITLE,
                "「" + pairing.browserName + "」との連携の確認が済んでいません。アプリを開いて確認してください。", true, open);
    }

    static void cancel(@NonNull Context ctx, @NonNull OtpRelayRequest request) {
        NotificationManagerCompat.from(ctx).cancel(notificationId(request.requestId));
    }

    private static void result(Context ctx, OtpRelayRequest request, String title, String body, boolean alert,
                               @Nullable PendingIntent content) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NotificationChannels.ensureOtpRelay(ctx))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setOnlyAlertOnce(!alert)
                .setSilent(!alert)
                .setAutoCancel(true);
        if (content != null) b.setContentIntent(content);
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
