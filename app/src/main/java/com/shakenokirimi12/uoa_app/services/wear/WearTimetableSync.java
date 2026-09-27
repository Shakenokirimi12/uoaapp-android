package com.shakenokirimi12.uoa_app.services.wear;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 時間割を Wear OS アプリ (wear モジュール) へ Data Layer で送る。
 *
 * 送るのは前日から 2 週間先までの授業だけ (DataItem は 100KB まで)。形式は wear 側の
 * TimetableStore と揃えた JSON 1 本で、同じ内容なら Data Layer 側で差分なしとして捨てられる。
 * 時計が繋がっていない・Wear OS が入っていない端末では API が失敗するだけなので、握りつぶす。
 */
public final class WearTimetableSync {
    private static final String TAG = "WearTimetableSync";
    /** wear/…/TimetableStore.PATH と同じ。 */
    static final String PATH = "/timetable";
    static final String KEY_JSON = "json";
    private static final long DAY_MS = 24L * 60 * 60_000L;
    private static final long WINDOW_BEFORE_MS = DAY_MS;
    private static final long WINDOW_AFTER_MS = 14 * DAY_MS;

    private WearTimetableSync() {}

    public static void push(@NonNull Context context, @NonNull List<CalendarEvent> events) {
        Context ctx = context.getApplicationContext();
        String json;
        try {
            json = toJson(events, System.currentTimeMillis());
        } catch (Exception e) {
            Log.w(TAG, "Could not serialize timetable", e);
            return;
        }
        PutDataMapRequest req = PutDataMapRequest.create(PATH);
        req.getDataMap().putString(KEY_JSON, json);
        PutDataRequest put = req.asPutDataRequest().setUrgent();
        Wearable.getDataClient(ctx).putDataItem(put)
                .addOnFailureListener(e -> Log.d(TAG, "Wear sync skipped: " + e.getMessage()));
    }

    /** 起動時など、取り直しを待たずに手元のキャッシュを送る。 */
    public static void pushFromCache(@NonNull Context context) {
        push(context, DataCache.getInstance(context).loadEvents());
    }

    /** ログアウトのとき。時計に前のユーザーの時間割を残さない。 */
    public static void clear(@NonNull Context context) {
        push(context, java.util.Collections.emptyList());
    }

    static String toJson(List<CalendarEvent> events, long now) throws Exception {
        JSONArray arr = new JSONArray();
        for (CalendarEvent e : events) {
            if (e.getDtstart() == null || e.getDtend() == null) continue;
            long start = e.getDtstart().getTime();
            if (start < now - WINDOW_BEFORE_MS || start > now + WINDOW_AFTER_MS) continue;
            arr.put(new JSONObject()
                    .put("t", e.getSummary() == null ? "" : e.getSummary())
                    .put("l", e.getLocation())
                    .put("s", start)
                    .put("e", e.getDtend().getTime()));
        }
        // updatedAt は入れない。入れると中身が同じでも毎回別の DataItem になり、時計を無駄に起こす。
        return new JSONObject().put("v", 1).put("events", arr).toString();
    }
}
