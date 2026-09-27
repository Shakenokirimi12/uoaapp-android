package com.shakenokirimi12.uoa_app.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** スマホから時間割が届いたら保存し、タイルとコンプリケーションに更新を促す。 */
class TimetableListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != TimetableStore.PATH) continue
            val json = DataMapItem.fromDataItem(item).dataMap.getString(TimetableStore.KEY_JSON) ?: continue
            TimetableStore.save(this, json)
        }
        refreshSurfaces(this)
    }

    companion object {
        fun refreshSurfaces(context: Context) {
            ClassOngoingActivity.refresh(context)
            TileService.getUpdater(context).requestUpdate(TimetableTileService::class.java)
            ComplicationDataSourceUpdateRequester.create(
                context, ComponentName(context, NextClassComplicationService::class.java)
            ).requestUpdateAll()
        }
    }
}
