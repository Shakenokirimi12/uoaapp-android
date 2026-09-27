package com.shakenokirimi12.uoa_app.services.sync;

import android.content.Context;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.TimetableDiff;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.services.calendar.DeviceCalendarSync;
import com.shakenokirimi12.uoa_app.services.notification.ClassChangeNotifier;
import com.shakenokirimi12.uoa_app.services.notification.ClassLiveUpdate;
import com.shakenokirimi12.uoa_app.services.wear.WearTimetableSync;
import com.shakenokirimi12.uoa_app.widget.ClassScheduleWidgetProvider;

import java.util.List;

/**
 * CampusSquare から時間割を取り直したら、ここを通して保存する。
 *
 * 前面 (ホーム・カレンダー) と定期同期の 3 か所が別々に保存していたため、休講の検知や
 * 端末カレンダーへの書き出しをどこか 1 か所に足すと、他の経路で取ったときに漏れる。
 * 保存・差分の通知・通知/ウィジェット/端末カレンダーの組み直しを 1 つにまとめる。
 */
public final class TimetableUpdates {
    private TimetableUpdates() {}

    public static void onFetched(@NonNull Context context, @NonNull List<CalendarEvent> events) {
        Context ctx = context.getApplicationContext();
        List<TimetableDiff.Change> changes;
        // 前面と定期同期が同時に取り終えると、両方が同じ「前回」と比べて同じ変更を 2 回知らせる。
        synchronized (TimetableUpdates.class) {
            DataCache cache = DataCache.getInstance(ctx);
            List<CalendarEvent> before = cache.loadEvents();
            // ClassLiveUpdate とウィジェットはキャッシュを読むので、比べたらすぐ保存する。
            cache.saveEvents(events);
            changes = TimetableDiff.diff(before, events, System.currentTimeMillis());
        }
        ClassChangeNotifier.post(ctx, changes);
        ClassLiveUpdate.refresh(ctx);
        ClassScheduleWidgetProvider.refresh(ctx);
        DeviceCalendarSync.syncAsync(ctx, events);
        WearTimetableSync.push(ctx, events);
    }
}
