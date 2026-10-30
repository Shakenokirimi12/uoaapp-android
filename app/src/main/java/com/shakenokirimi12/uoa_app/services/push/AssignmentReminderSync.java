package com.shakenokirimi12.uoa_app.services.push;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.PushNotificationService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 課題の期限通知をサーバーへ予約する (iOS の NotificationManager のサーバー配信経路)。
 *
 * Android にはこれまで期限通知が一切無かった。設定画面で通知タイミングを選べたが、
 * 裏で何も予約されていなかった。端末側で正確な時刻に通知するには Android 13 以降で
 * 正確なアラームの許可をユーザーに求める必要があり、14 以降は既定で拒否されるため、
 * 配信はサーバー (cron + FCM) に任せ、端末は予約の一覧を渡すだけにする。
 *
 * 同期は PushNotificationService の単一スレッドの executor に乗るので、呼び出し順に
 * サーバーへ届く (一覧まるごと差し替えなので順序が入れ替わると古い一覧が勝つ)。
 */
public final class AssignmentReminderSync {
    private static final String TAG = "AssignmentReminderSync";
    private static final Map<String, Integer> PRESET_MINUTES = new HashMap<>();
    static {
        for (int m : new int[] {10, 20, 30, 60, 120, 180, 360, 720, 1440, 2880, 4320, 10080}) {
            PRESET_MINUTES.put("prev_" + m, m);
        }
    }

    private AssignmentReminderSync() {}

    private static PushNotificationService push(Context ctx) {
        return PushNotificationService.shared(ctx);
    }

    /** 課題の取得に成功したあとに呼ぶ。 */
    public static void sync(@NonNull Context context, @NonNull List<Assignment> assignments) {
        Context ctx = context.getApplicationContext();
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);

        boolean enabled = prefs.isAssignmentNotifyEnabled()
                && AppConfigService.getInstance().isFeatureEnabled("notifications_enabled")
                && AppConfigService.getInstance().isFeatureEnabled("assignment_reminders_push");
        if (!enabled) {
            clear(ctx);
            return;
        }

        List<PushNotificationService.AssignmentReminder> reminders = build(ctx, prefs, assignments);
        push(ctx).syncAssignmentReminders(reminders, false, ok -> {
            // 「サーバーは空」と言い切れるのは clearAll のときだけ。通常の再同期は期限の来た
            // 未配信分を残すので、空を送っても空になったとは限らない。
            if (ok && !reminders.isEmpty()) prefs.setServerHoldingReminders(true);
            Log.d(TAG, "synced " + reminders.size() + " reminders, ok=" + ok);
        });
    }

    /** 通知を止めるとき (設定 OFF・ログアウト) に呼ぶ。 */
    public static void clear(@NonNull Context context) {
        clear(context, null);
    }

    /** onFailure: サーバーの予約を消せなかったときに呼ぶ (ログアウトで知らせる)。 */
    public static void clear(@NonNull Context context, @androidx.annotation.Nullable Runnable onFailure) {
        Context ctx = context.getApplicationContext();
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        // サーバーが何も持っていないと分かっているなら送る必要がない。
        if (!prefs.isServerHoldingReminders()) return;
        push(ctx).syncAssignmentReminders(new ArrayList<>(), true, ok -> {
            if (ok) prefs.setServerHoldingReminders(false);
            else if (onFailure != null) onFailure.run();
        });
    }

    static List<PushNotificationService.AssignmentReminder> build(Context ctx, PreferenceManager prefs,
                                                                   List<Assignment> assignments) {
        Map<String, Integer> minutesById = new HashMap<>(PRESET_MINUTES);
        Map<String, String> labelById = new HashMap<>();
        for (Map.Entry<String, Integer> e : PRESET_MINUTES.entrySet()) {
            labelById.put(e.getKey(), presetLabel(ctx, e.getValue()));
        }
        try {
            JSONArray customs = new JSONArray(prefs.getCustomNotifyTimes());
            for (int i = 0; i < customs.length(); i++) {
                JSONObject o = customs.getJSONObject(i);
                minutesById.put(o.getString("id"), o.getInt("minutesBefore"));
                labelById.put(o.getString("id"), o.optString("label", ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "custom notify times unreadable: " + e.getMessage());
        }

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        List<PushNotificationService.AssignmentReminder> out = new ArrayList<>();

        for (Assignment a : assignments) {
            long dueMs = a.getDueDate() * 1000L;
            if (dueMs <= now) continue;
            for (String id : prefs.getSelectedNotifyTimes().split(",")) {
                id = id.trim();
                Integer minutes = minutesById.get(id);
                if (minutes == null) continue;
                long triggerMs = dueMs - minutes * 60_000L;
                if (triggerMs <= now) continue;
                // 開く先が分からない古いキャッシュでは、別の課題を開くより Moodle のトップへ送る。
                String url = a.getUrl() != null ? a.getUrl() : MoodleService.currentBaseUrl() + "/my/";
                out.add(new PushNotificationService.AssignmentReminder(
                        a.getId(), id, iso.format(new Date(triggerMs)),
                        ctx.getString(R.string.assignment_reminder_title),
                        a.getName() + " (" + labelById.get(id) + ")",
                        url));
            }
        }
        return out;
    }

    /** 「10分前」「1時間前」「1日前」「1週間前」。設定画面の既定の選択肢と同じ文面。 */
    public static String presetLabel(Context ctx, int minutes) {
        if (minutes % 10080 == 0) return ctx.getString(R.string.notify_before_weeks_format, minutes / 10080);
        if (minutes % 1440 == 0) return ctx.getString(R.string.notify_before_days_format, minutes / 1440);
        if (minutes % 60 == 0) return ctx.getString(R.string.notify_before_hours_format, minutes / 60);
        return ctx.getString(R.string.notify_before_minutes_format, minutes);
    }
}
