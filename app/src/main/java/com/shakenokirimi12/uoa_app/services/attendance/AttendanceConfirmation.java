package com.shakenokirimi12.uoa_app.services.attendance;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.AttendanceManager;
import com.shakenokirimi12.uoa_app.services.notification.NotificationChannels;

/**
 * 「欠席？（要確認）」を付けた授業について、出席したかを 1 回だけ通知で尋ねる (iOS の
 * NotificationManager.askAttendanceConfirmation と同じ)。ボタンで選ぶと手で選んだ記録になる。
 */
public final class AttendanceConfirmation {
    private static final String TAG = "AttendanceConfirm";
    private static final String EXTRA_COURSE_ID = "course_id";
    private static final String EXTRA_HISTORY_ID = "history_id";
    private static final String EXTRA_STATUS = "status";
    private static final String EXTRA_NOTIFICATION_ID = "notification_id";
    /** 尋ねたときのアカウント。切り替えたあとに押されても、そのアカウントの記録を変える。 */
    private static final String EXTRA_USERNAME = "username";

    private AttendanceConfirmation() {}

    public static void ask(@NonNull Context ctx, @NonNull String username, @NonNull String courseId,
                           @NonNull String historyId, @NonNull String courseName, @NonNull String key) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return; // 画面の「欠席？（要確認）」から選べる
        // 同じ授業の日は 1 通だけ (同じ id なら置き換わる)。
        // アカウントごとに別の通知にする (別のアカウントの同じ授業の日の通知を置き換えない)。
        int notificationId = (username + "|" + key).hashCode();
        String channel = NotificationChannels.ensureAttendanceConfirm(ctx);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat_calendar)
                .setContentTitle(ctx.getString(R.string.attendance_confirm_title, courseName))
                .setContentText(ctx.getString(R.string.attendance_confirm_body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .addAction(0, ctx.getString(R.string.attendance_present),
                        action(ctx, username, courseId, historyId, AttendanceManager.Status.PRESENT, notificationId, 1))
                .addAction(0, ctx.getString(R.string.attendance_absent),
                        action(ctx, username, courseId, historyId, AttendanceManager.Status.ABSENT, notificationId, 2));
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    private static PendingIntent action(Context ctx, String username, String courseId, String historyId,
                                        AttendanceManager.Status status, int notificationId, int slot) {
        Intent intent = new Intent(ctx, Receiver.class)
                .putExtra(EXTRA_USERNAME, username)
                .putExtra(EXTRA_COURSE_ID, courseId)
                .putExtra(EXTRA_HISTORY_ID, historyId)
                .putExtra(EXTRA_STATUS, status.name())
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId);
        // 通知ごと・ボタンごとに別の PendingIntent にする (同じ requestCode だと extras が上書きされる)。
        return PendingIntent.getBroadcast(ctx, notificationId * 4 + slot, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String courseId = intent.getStringExtra(EXTRA_COURSE_ID);
            String historyId = intent.getStringExtra(EXTRA_HISTORY_ID);
            String status = intent.getStringExtra(EXTRA_STATUS);
            if (courseId == null || historyId == null || status == null) return;
            AttendanceManager.Status chosen;
            try {
                chosen = AttendanceManager.Status.valueOf(status);
            } catch (IllegalArgumentException e) {
                return;
            }
            if (chosen != AttendanceManager.Status.PRESENT && chosen != AttendanceManager.Status.ABSENT) return;
            // 前の版の通知にはアカウントが無い。そのときは今のアカウントの記録を変える (前の版の動き)。
            String username = intent.getStringExtra(EXTRA_USERNAME);
            AttendanceManager.getInstance(context).updateStatus(username, courseId, historyId, chosen);
            NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0));
        }
    }
}
