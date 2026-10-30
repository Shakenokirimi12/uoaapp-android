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

    /**
     * sync の「送ってよいかの確認・持っている印・送信の積み込み」と clear の「印の確認・削除の積み込み」を
     * 互いに割り込ませない。設定は clear を呼ぶ前に書き換わるので (設定画面の切り替え)、どちらが先にロックを
     * 取っても、同期を積んだあとに削除が積まれるか、同期が積まれないかのどちらかになる。ロックの外で確かめると、
     * 確認のあとに通知を止めた clear が「何も持っていない」と見て戻り、そのあと積まれた予約がサーバーに残る。
     * 送信は PushNotificationService の 1 本の executor に積んだ順に出る。
     */
    private static final Object lock = new Object();

    /**
     * sync が「持っている」と記録した回数 (lock で守る)。削除の応答はメインスレッドに遅れて届くので、そのあいだに
     * 次の同期が積まれていたら印を下ろさない。下ろすと、次の clear (ログアウトなど) が何も送らずに戻り、
     * あとから送った予約がサーバーに残る。
     */
    private static long holdingSeq;

    private static boolean enabled(PreferenceManager prefs) {
        return prefs.isAssignmentNotifyEnabled()
                && AppConfigService.getInstance().isFeatureEnabled("notifications_enabled")
                && AppConfigService.getInstance().isFeatureEnabled("assignment_reminders_push");
    }

    /** 課題の取得に成功したあとに呼ぶ。 */
    public static void sync(@NonNull Context context, @NonNull List<Assignment> assignments) {
        Context ctx = context.getApplicationContext();
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);

        if (!enabled(prefs)) {
            clear(ctx);
            return;
        }

        List<PushNotificationService.AssignmentReminder> reminders = build(ctx, prefs, assignments);
        synchronized (lock) {
            // build のあいだに通知を止めた (設定・killswitch) かもしれない。送らずに、持っている予約を消す。
            // 設定を止めた clear はもう戻っているかもしれず、killswitch は clear を呼ばないので、ここで消す。
            if (!enabled(prefs)) {
                clearLocked(ctx, prefs, null);
                return;
            }
            // 送る前に「持っている」と記録する。応答を待ってから記録すると、送っている最中のログアウトや
            // アカウントの切り替えで clear が何も送らずに戻り、そのあと届いた前のアカウントの予約が残る。
            // 送信が失敗しても印は残す (次の clear が余分に 1 回送るだけ)。
            if (!reminders.isEmpty()) {
                holdingSeq++;
                prefs.setServerHoldingReminders(true);
            }
            push(ctx).syncAssignmentReminders(reminders, false,
                    ok -> Log.d(TAG, "synced " + reminders.size() + " reminders, ok=" + ok));
        }
    }

    /** 通知を止めるとき (設定 OFF・ログアウト) に呼ぶ。設定を書き換えてから呼ぶこと (lock の説明)。 */
    public static void clear(@NonNull Context context) {
        clear(context, null);
    }

    /** onFailure: サーバーの予約を消せなかったときに呼ぶ (ログアウトで知らせる)。 */
    public static void clear(@NonNull Context context, @androidx.annotation.Nullable Runnable onFailure) {
        Context ctx = context.getApplicationContext();
        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        synchronized (lock) {
            clearLocked(ctx, prefs, onFailure);
        }
    }

    /** lock の中で呼ぶ。 */
    private static void clearLocked(Context ctx, PreferenceManager prefs,
                                    @androidx.annotation.Nullable Runnable onFailure) {
        // サーバーが何も持っていないと分かっているなら送る必要がない。
        if (!prefs.isServerHoldingReminders()) return;
        long seq = holdingSeq;
        push(ctx).syncAssignmentReminders(new ArrayList<>(), true, ok -> {
            if (ok) {
                synchronized (lock) {
                    // 「サーバーは空」と言い切れるのは clearAll のとき、それも削除のあとに同期を積んでいないときだけ。
                    if (holdingSeq == seq) prefs.setServerHoldingReminders(false);
                }
            } else if (onFailure != null) {
                onFailure.run();
            }
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
