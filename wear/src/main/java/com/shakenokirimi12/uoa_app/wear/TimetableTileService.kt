package com.shakenokirimi12.uoa_app.wear

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.time.Instant

/**
 * タイル: 今の授業 (無ければ次の授業) の時刻・科目・教室と、今日の残りコマ数。
 * 次に授業が始まる/終わる時刻を鮮度に設定し、切り替わりで作り直させる。
 */
class TimetableTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val now = System.currentTimeMillis()
        val events = TimetableStore.load(this)
        val layout = layout(this, requestParams.deviceConfiguration, events, now)
        val freshness = TimetableStore.nextBoundary(events, now)
            ?.let { (it - now).coerceIn(60_000L, 6 * 60 * 60_000L) }
            ?: (6 * 60 * 60_000L)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(freshness)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile) }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val res = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        return CallbackToFutureAdapter.getFuture { it.set(res) }
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private val COLORS = Colors.DEFAULT

        fun layout(
            context: Context,
            device: DeviceParameters,
            events: List<ClassEvent>,
            now: Long,
        ): LayoutElementBuilders.LayoutElement {
            val target = TimetableStore.currentOrNext(events, now)
            val today = Instant.ofEpochMilli(now).atZone(TimetableStore.ZONE).toLocalDate()

            val label: String
            val main: String
            val sub: String
            if (target == null) {
                label = "時間割"
                main = if (TimetableStore.hasData(context)) "予定なし" else "スマホで開いてください"
                sub = ""
            } else {
                val day = Instant.ofEpochMilli(target.startMs).atZone(TimetableStore.ZONE).toLocalDate()
                label = when {
                    target.isOngoing(now) -> "授業中 〜${formatTime(target.endMs)}"
                    day == today -> "次 ${formatTime(target.startMs)}"
                    else -> "${formatDay(day, today)} ${formatTime(target.startMs)}"
                }
                main = target.title
                val remainingToday = TimetableStore.onDay(events, today)
                    .count { !it.isCancelled && it.startMs > now && it != target }
                sub = listOfNotNull(
                    target.location.takeIf { it.isNotEmpty() },
                    if (day == today && remainingToday > 0) "あと${remainingToday}コマ" else null,
                ).joinToString(" ・ ")
            }

            val open = ModifiersBuilders.Clickable.Builder()
                .setId("open")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(context.packageName)
                                .setClassName(MainActivity::class.java.name)
                                .build()
                        ).build()
                ).build()

            val builder = PrimaryLayout.Builder(device)
                .setResponsiveContentInsetEnabled(true)
                .setPrimaryLabelTextContent(
                    Text.Builder(context, label)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(argb(COLORS.primary))
                        .build()
                )
                .setContent(
                    Text.Builder(context, main)
                        .setTypography(Typography.TYPOGRAPHY_TITLE3)
                        .setColor(argb(COLORS.onSurface))
                        .setMaxLines(2)
                        .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
                        .build()
                )
            if (sub.isNotEmpty()) {
                builder.setSecondaryLabelTextContent(
                    Text.Builder(context, sub)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(argb(COLORS.onSurface))
                        .build()
                )
            }
            return builder.build()
        }
    }
}
