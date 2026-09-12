package com.shakenokirimi12.uoa_app.services.push;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.PushNotificationService;
import com.shakenokirimi12.uoa_app.ui.browser.InAppBrowserActivity;

import java.util.Map;

/**
 * サーバー (uoa-app-push) からの FCM を受ける。課題の期限通知と、管理画面からの告知。
 *
 * iOS では APNs の alert がここに当たる。サーバーは data メッセージで
 * title / body / url を送ってくる (notification ペイロードではない)。
 * data にしているのは、アプリが前面でも背面でも同じ経路でこのクラスを通し、
 * 通知の見た目と押したときの遷移をアプリ側で決めるため。
 */
public class AppFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "FCM";
    public static final String CHANNEL_ID = "assignment_reminders";
    private static final String KEY_TITLE = "title";
    private static final String KEY_BODY = "body";
    private static final String KEY_URL = "url";

    @Override
    public void onNewToken(@NonNull String token) {
        // トークンは端末側で入れ替わる。届いたらすぐサーバーへ登録し直す。
        PreferenceManager prefs = PreferenceManager.getInstance(getApplicationContext());
        prefs.setFcmToken(token);
        PushNotificationService.shared(this).registerDevice(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String title = data.get(KEY_TITLE);
        String body = data.get(KEY_BODY);
        String url = data.get(KEY_URL);
        // notification ペイロードで来た場合 (管理画面からの手動送信など) も拾う
        if (message.getNotification() != null) {
            if (title == null) title = message.getNotification().getTitle();
            if (body == null) body = message.getNotification().getBody();
        }
        if (body == null || body.isEmpty()) {
            Log.w(TAG, "Dropping message without body");
            return;
        }
        showNotification(title, body, url);
    }

    private void showNotification(String title, String body, String url) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_HIGH));

        Intent open;
        if (url != null && !url.isEmpty()) {
            // 課題の URL はアプリ内ブラウザで開く。ログイン済みのセッションを引き継ぐため。
            open = new Intent(this, InAppBrowserActivity.class).putExtra(InAppBrowserActivity.EXTRA_URL, url);
        } else {
            open = new Intent(this, MainActivity.class);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, (int) (System.currentTimeMillis() & 0x7fffffff),
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title != null ? title : getString(R.string.app_name))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        try {
            NotificationManagerCompat.from(this).notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; dropping notification");
        }
    }
}
