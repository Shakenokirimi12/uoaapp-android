package com.shakenokirimi12.uoa_app.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * 文字盤のアプリショートカット。アイコンだけを出し、タップで時間割アプリを開く。
 * 中身は変わらないので更新周期は持たない (マニフェストの UPDATE_PERIOD_SECONDS=0)。
 * 次の授業を文字で出したいときは NextClassComplicationService の方を選んでもらう。
 */
class AppShortcutComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type)

    private fun build(type: ComplicationType): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val desc = PlainComplicationText.Builder(getString(R.string.app_name) + "を開く").build()
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_class)).build(), desc
            ).setTapAction(tap).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(Icon.createWithResource(this, R.mipmap.ic_launcher), SmallImageType.ICON).build(), desc
            ).setTapAction(tap).build()
            else -> null
        }
    }
}
