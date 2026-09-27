package com.shakenokirimi12.uoa_app.services.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.TimetableDiff;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * TimetableDiff が拾った休講・教室変更・補講を 1 通の通知にまとめて出す。
 * 同期のたびに差分は前回のキャッシュとだけ取るので、同じ変更を二度知らせることは無い。
 */
public final class ClassChangeNotifier {
    public static final String CHANNEL_ID = "class_changes";
    // 他の固定 ID (1001 授業, 4100 同期, 700000~ カウントダウン) と重ならない値。
    private static final int NOTIFICATION_ID = 4200;
    /** これを超える差分は学期の切り替えなどで時間割ごと入れ替わったとみなし、個別には並べない。 */
    private static final int BULK_THRESHOLD = 8;

    private ClassChangeNotifier() {}

    public static void post(@NonNull Context context, @NonNull List<TimetableDiff.Change> changes) {
        if (changes.isEmpty()) return;
        Context ctx = context.getApplicationContext();
        if (!PreferenceManager.getInstance(ctx).isClassChangeNotifyEnabled()) return;

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.class_change_channel_name), NotificationManager.IMPORTANCE_DEFAULT));

        String title;
        String text;
        NotificationCompat.Style style = null;
        if (changes.size() > BULK_THRESHOLD) {
            title = "時間割が更新されました";
            text = "今後 2 週間の授業に " + changes.size() + " 件の変更があります。カレンダーで確認してください。";
        } else if (changes.size() == 1) {
            TimetableDiff.Change c = changes.get(0);
            title = label(c.kind) + ": " + subject(c);
            text = describe(c);
        } else {
            title = "時間割の変更が " + changes.size() + " 件あります";
            NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
            StringBuilder first = null;
            for (TimetableDiff.Change c : changes) {
                String line = label(c.kind) + " " + subject(c) + " (" + describe(c) + ")";
                inbox.addLine(line);
                if (first == null) first = new StringBuilder(line);
            }
            text = first.toString();
            style = inbox;
        }

        PendingIntent open = PendingIntent.getActivity(ctx, NOTIFICATION_ID,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT);
        if (style != null) b.setStyle(style);
        else b.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS 未許可。
        }
    }

    private static String label(TimetableDiff.Kind kind) {
        switch (kind) {
            case CANCELLED: return "休講";
            case ROOM_CHANGED: return "教室変更";
            default: return "補講・追加";
        }
    }

    private static String subject(TimetableDiff.Change c) {
        String s = c.event.getSummary() == null ? "" : c.event.getSummary();
        return s.replaceAll("[\\[【(（]?休講[\\]】)）]?", "").trim();
    }

    private static String describe(TimetableDiff.Change c) {
        SimpleDateFormat fmt = new SimpleDateFormat("M/d(E) H:mm", Locale.JAPAN);
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        String when = fmt.format(c.event.getDtstart());
        if (c.kind == TimetableDiff.Kind.ROOM_CHANGED) {
            String from = c.previousLocation == null || c.previousLocation.isEmpty() ? "未設定" : c.previousLocation;
            return when + " " + from + " → " + c.event.getLocation();
        }
        if (c.kind == TimetableDiff.Kind.ADDED && !c.event.getLocation().isEmpty()) {
            return when + " " + c.event.getLocation();
        }
        return when;
    }
}
