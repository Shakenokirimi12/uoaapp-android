package com.shakenokirimi12.uoa_app.wear

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * スマホの授業ライブアップデート (app/…/ClassLiveUpdate) の時計版。
 *
 * スマホの常駐通知は時計へ転送されない (スマホ側は setLocalOnly で明示的に止めている) ので、
 * 時計では Wear OS の Ongoing Activity として自前で出す。文字盤の下部やアプリ一覧の先頭に
 * 残り時間付きで表示される。出し分けはスマホと同じ:
 * - 授業中: 「授業中」と終了までのカウントダウン
 * - 開始 30 分前から: 「次の授業」と開始までのカウントダウン
 * - それ以外: 出さない
 * 残り時間は Status の TimerPart が自分で進めるので、アプリが動くのは切り替わりの時刻だけ。
 */
object ClassOngoingActivity {
    private const val TAG = "ClassOngoingActivity"
    private const val CHANNEL_ID = "class_ongoing"
    private const val NOTIFICATION_ID = 1001
    private const val LEAD_MS = 30 * 60_000L
    /** 切り替わりちょうどに起きると境界の手前と判定されうるので、少し後ろにずらす。 */
    private const val ALARM_MARGIN_MS = 2_000L

    fun refresh(context: Context) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        val events = TimetableStore.load(ctx).filter { !it.isCancelled }
        val current = events.firstOrNull { it.isOngoing(now) }
        val next = events.firstOrNull { it.startMs > now }

        val boundary: Long = when {
            current != null -> {
                post(ctx, "授業中", current, countdownTo = current.endMs)
                current.endMs
            }
            next != null && next.startMs - now <= LEAD_MS -> {
                post(ctx, "次の授業", next, countdownTo = next.startMs)
                next.startMs
            }
            next != null -> {
                cancel(ctx)
                next.startMs - LEAD_MS
            }
            else -> {
                // 手元の時間割 (2 週間分) に授業がもう無い。次に時間割が届いたら組み直す。
                cancel(ctx)
                cancelAlarm(ctx)
                return
            }
        }
        scheduleAt(ctx, boundary + ALARM_MARGIN_MS)
    }

    private fun post(ctx: Context, label: String, c: ClassEvent, countdownTo: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW: 音も振動も出さない。Ongoing Activity の表示には影響しない。
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "授業のライブアップデート", NotificationManager.IMPORTANCE_LOW)
        )

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = listOf(c.timeRange(), c.location).filter { it.isNotEmpty() }.joinToString("  ")
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_class)
            .setContentTitle(if (label == "授業中") c.title else "$label: ${c.title}")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setWhen(countdownTo)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)

        // TimerPart は elapsedRealtime 基準。壁時計の目標時刻をその基準に直す。
        val zeroAt = SystemClock.elapsedRealtime() + (countdownTo - System.currentTimeMillis())
        val status = Status.Builder()
            .addTemplate("#label# #time#")
            .addPart("label", Status.TextPart(if (label == "授業中") "授業中 残り" else "開始まで"))
            .addPart("time", Status.TimerPart(zeroAt))
            .build()

        OngoingActivity.Builder(ctx, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_class)
            .setTouchIntent(open)
            .setStatus(status)
            .setTitle(c.title)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
            .apply(ctx)

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // 通知の許可 (POST_NOTIFICATIONS) が無い。MainActivity で求めている。
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleAt(ctx: Context, at: Long) {
        // 正確なアラームの権限は求めない (スマホ版と同じ)。数分ずれても残り時間の表示は TimerPart が正しく進める。
        ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
    }

    private fun cancelAlarm(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx))
    }

    /** 切り替わりの時刻に起きて作り直す。再起動後も組み直す。 */
    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // タイルとコンプリケーションも同じ切り替わりで古くなるので一緒に更新する (refresh も中で呼ばれる)。
            TimetableListenerService.refreshSurfaces(context)
        }
    }
}
