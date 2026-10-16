package com.shakenokirimi12.uoa_app.wear

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant

/** 今日から数日分の時間割。スマホで取り直した内容が Data Layer 経由で届く。 */
class MainActivity : ComponentActivity() {
    private var events by mutableStateOf<List<ClassEvent>>(emptyList())
    private var hasData by mutableStateOf(false)
    private var now by mutableLongStateOf(System.currentTimeMillis())

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ClassOngoingActivity.refresh(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TimetableApp(events, hasData, now) }
        // 授業のライブアップデートは通知として出すので、Wear OS 4 (API 33) 以降は許可が要る。
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        // リスナーが取りこぼした場合 (インストール直後など) に備え、Data Layer に残っている最新を読み直す。
        lifecycleScope.launch {
            try {
                val items = Wearable.getDataClient(this@MainActivity)
                    .getDataItems(Uri.parse("wear://*" + TimetableStore.PATH)).await()
                try {
                    items.firstOrNull()?.let { item ->
                        DataMapItem.fromDataItem(item).dataMap.getString(TimetableStore.KEY_JSON)?.let {
                            TimetableStore.save(this@MainActivity, it)
                            TimetableListenerService.refreshSurfaces(this@MainActivity)
                        }
                    }
                } finally {
                    items.release()
                }
                reload()
            } catch (e: Exception) {
                // スマホと繋がっていない。手元の分を出す。
            }
        }
    }

    private fun reload() {
        ClassOngoingActivity.refresh(this)
        now = System.currentTimeMillis()
        events = TimetableStore.load(this)
        hasData = TimetableStore.hasData(this)
    }
}

@Composable
private fun TimetableApp(events: List<ClassEvent>, hasData: Boolean, now: Long) {
    MaterialTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                val today = Instant.ofEpochMilli(now).atZone(TimetableStore.ZONE).toLocalDate()
                val days = TimetableStore.upcomingDays(events, now, days = 5)
                TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                    item { ListHeader { Text("時間割") } }
                    if (days.isEmpty()) {
                        item {
                            Text(
                                if (hasData) "今後 2 週間の授業はありません"
                                else "スマホの UoA非公式アプリ を開くと、時間割がここに届きます",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    days.forEach { (day, classes) ->
                        item { ListHeader { Text(formatDay(day, today)) } }
                        classes.forEach { c -> item { ClassCard(c, now) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassCard(c: ClassEvent, now: Long) {
    val past = c.endMs <= now
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        enabled = !past,
    ) {
        Text(
            if (c.isOngoing(now)) "${c.timeRange()} ・ 授業中" else c.timeRange(),
            style = MaterialTheme.typography.labelSmall,
            color = if (c.isOngoing(now)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            c.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (c.isCancelled) TextDecoration.LineThrough else null,
        )
        if (c.location.isNotEmpty()) {
            Text(c.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
