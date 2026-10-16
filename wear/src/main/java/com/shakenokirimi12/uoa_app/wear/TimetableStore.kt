package com.shakenokirimi12.uoa_app.wear

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 時計に置く授業 1 コマ。時刻はエポックミリ秒。 */
data class ClassEvent(
    val title: String,
    val location: String,
    val startMs: Long,
    val endMs: Long,
) {
    fun isOngoing(now: Long) = now in startMs until endMs

    /** CampusSquare の予定名に付く「休講」をそのまま拾う (スマホ側の TimetableDiff と同じ判定)。 */
    val isCancelled: Boolean get() = title.contains("休講")

    fun timeRange(): String = "${formatTime(startMs)}–${formatTime(endMs)}"
}

/**
 * スマホ (app/…/WearTimetableSync) から届いた時間割の置き場所。
 * Data Layer の DataItem は時計側にも残るが、タイルやコンプリケーションから毎回 GMS を叩かずに
 * 済むよう、受け取った JSON を SharedPreferences にも写しておく。
 */
object TimetableStore {
    /** app/…/WearTimetableSync.PATH と同じ。 */
    const val PATH = "/timetable"
    const val KEY_JSON = "json"

    private const val PREFS = "timetable"
    val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

    fun save(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, json)
            .putLong("received_at", System.currentTimeMillis())
            .apply()
    }

    fun hasData(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_JSON)

    fun load(context: Context): List<ClassEvent> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null)
            ?: return emptyList()
        return parse(json)
    }

    fun parse(json: String): List<ClassEvent> = try {
        val arr = JSONObject(json).optJSONArray("events") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ClassEvent(o.optString("t"), o.optString("l"), o.getLong("s"), o.getLong("e"))
        }.sortedBy { it.startMs }
    } catch (e: Exception) {
        emptyList()
    }

    /** 今の授業、無ければ次の授業 (休講は飛ばす)。今日以降に無ければ null。 */
    fun currentOrNext(events: List<ClassEvent>, now: Long): ClassEvent? =
        events.firstOrNull { !it.isCancelled && it.endMs > now }

    /** その日 (日本時間) の授業。 */
    fun onDay(events: List<ClassEvent>, day: LocalDate): List<ClassEvent> =
        events.filter { Instant.ofEpochMilli(it.startMs).atZone(ZONE).toLocalDate() == day }

    /** 今日と、今日以降で授業のある日を最大 [days] 日分 (空の日は飛ばす)。 */
    fun upcomingDays(events: List<ClassEvent>, now: Long, days: Int): List<Pair<LocalDate, List<ClassEvent>>> {
        val today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
        return events
            .filter { it.endMs > now || Instant.ofEpochMilli(it.startMs).atZone(ZONE).toLocalDate() == today }
            .groupBy { Instant.ofEpochMilli(it.startMs).atZone(ZONE).toLocalDate() }
            .filterKeys { !it.isBefore(today) }
            .toSortedMap()
            .entries
            .take(days)
            .map { it.key to it.value }
    }

    /** 次に表示が変わる時刻 (授業の開始か終了)。タイルの鮮度に使う。 */
    fun nextBoundary(events: List<ClassEvent>, now: Long): Long? =
        events.flatMap { listOf(it.startMs, it.endMs) }.filter { it > now }.minOrNull()
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.JAPAN)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPAN)

fun formatTime(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms).atZone(TimetableStore.ZONE))

fun formatDay(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "今日"
    today.plusDays(1) -> "明日"
    else -> DAY.format(day)
}
