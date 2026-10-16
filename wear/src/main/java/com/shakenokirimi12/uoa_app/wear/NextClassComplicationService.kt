package com.shakenokirimi12.uoa_app.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant

/**
 * 文字盤に次の授業を出す。
 * SHORT_TEXT: 開始時刻 (授業中は「〜終了時刻」) と教室 / LONG_TEXT: 時刻・科目・教室。
 */
class NextClassComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val now = System.currentTimeMillis()
        val next = TimetableStore.currentOrNext(TimetableStore.load(this), now)
        return build(request.complicationType, next, now)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val start = System.currentTimeMillis() + 30 * 60_000L
        return build(type, ClassEvent("線形代数", "M1", start, start + 100 * 60_000L), start - 30 * 60_000L)
    }

    private fun build(type: ComplicationType, c: ClassEvent?, now: Long): ComplicationData? {
        val today = Instant.ofEpochMilli(now).atZone(TimetableStore.ZONE).toLocalDate()
        val day = c?.let { Instant.ofEpochMilli(it.startMs).atZone(TimetableStore.ZONE).toLocalDate() }
        val time = when {
            c == null -> "—"
            c.isOngoing(now) -> "〜${formatTime(c.endMs)}"
            day == today -> formatTime(c.startMs)
            else -> formatDay(day!!, today)
        }
        val description = if (c == null) "次の授業はありません" else "次の授業 ${c.title} ${c.timeRange()} ${c.location}"
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_class)).build()
        val desc = PlainComplicationText.Builder(description).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(time).build(), desc
            )
                .setTitle(PlainComplicationText.Builder(c?.location?.ifEmpty { null } ?: "授業").build())
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(c?.title ?: "次の授業はありません").build(), desc
            )
                .setTitle(
                    PlainComplicationText.Builder(
                        if (c == null) "時間割" else listOf(time, c.location).filter { it.isNotEmpty() }.joinToString(" ")
                    ).build()
                )
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> null
        }
    }
}
