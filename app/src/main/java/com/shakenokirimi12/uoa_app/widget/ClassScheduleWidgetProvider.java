package com.shakenokirimi12.uoa_app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ホーム画面ウィジェット。次の授業 (または進行中の授業) と今日の一覧を出す
 * (iOS の ClassScheduleWidget の systemSmall / systemMedium に相当)。
 *
 * 残り時間は RemoteViews の Chronometer が刻むので、30 分ごとの更新でも表示は古くならない。
 * ただし「次の授業」の切り替わり自体は更新のタイミングに依存する。同期の直後にも更新する。
 */
public class ClassScheduleWidgetProvider extends AppWidgetProvider {
    private static final int[] ROW_IDS = {R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4};
    private static final int[] ROW_TIME_IDS = {R.id.row_1_time, R.id.row_2_time, R.id.row_3_time, R.id.row_4_time};
    private static final int[] ROW_NAME_IDS = {R.id.row_1_name, R.id.row_2_name, R.id.row_3_name, R.id.row_4_name};

    /** 同期の直後など、データが変わったときにアプリ側から呼ぶ。 */
    public static void refresh(@NonNull Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, ClassScheduleWidgetProvider.class));
        if (ids.length == 0) return;
        for (int id : ids) mgr.updateAppWidget(id, build(context));
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) appWidgetManager.updateAppWidget(id, build(context));
    }

    static RemoteViews build(Context ctx) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_class_schedule);

        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, open);

        List<GroupedClass> today = todayClasses(ctx);
        Date now = new Date();
        GroupedClass current = null;
        GroupedClass next = null;
        for (GroupedClass g : today) {
            if (g.isActiveAt(now)) { current = g; break; }
            if (g.getDtstart().after(now) && next == null) next = g;
        }

        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.JAPANESE);
        GroupedClass headline = current != null ? current : next;
        if (headline != null) {
            rv.setViewVisibility(R.id.headline, View.VISIBLE);
            rv.setViewVisibility(R.id.headline_empty, View.GONE);
            rv.setTextViewText(R.id.headline_label,
                    ctx.getString(current != null ? R.string.widget_in_progress : R.string.widget_next_class));
            rv.setTextViewText(R.id.headline_name, headline.getSummary());
            String sub = time.format(headline.getDtstart()) + " - " + time.format(headline.getDtend());
            if (!headline.getLocation().isEmpty()) sub = headline.getLocation() + "  " + sub;
            rv.setTextViewText(R.id.headline_sub, sub);
            // 進行中なら終了まで、これからなら開始までを数える。
            // Chronometer の base は elapsedRealtime 基準なので壁時計との差を足す。
            Date target = current != null ? headline.getDtend() : headline.getDtstart();
            long base = SystemClock.elapsedRealtime() + (target.getTime() - System.currentTimeMillis());
            rv.setChronometerCountDown(R.id.headline_countdown, true);
            rv.setChronometer(R.id.headline_countdown, base, null, true);
        } else {
            rv.setViewVisibility(R.id.headline, View.GONE);
            rv.setViewVisibility(R.id.headline_empty, View.VISIBLE);
        }

        for (int i = 0; i < ROW_IDS.length; i++) {
            if (i < today.size()) {
                GroupedClass g = today.get(i);
                rv.setViewVisibility(ROW_IDS[i], View.VISIBLE);
                rv.setTextViewText(ROW_TIME_IDS[i], time.format(g.getDtstart()));
                rv.setTextViewText(ROW_NAME_IDS[i], g.getSummary());
            } else {
                rv.setViewVisibility(ROW_IDS[i], View.GONE);
            }
        }
        int more = today.size() - ROW_IDS.length;
        rv.setViewVisibility(R.id.row_more, more > 0 ? View.VISIBLE : View.GONE);
        if (more > 0) rv.setTextViewText(R.id.row_more, ctx.getString(R.string.widget_more, more));
        rv.setViewVisibility(R.id.today_empty, today.isEmpty() ? View.VISIBLE : View.GONE);
        return rv;
    }

    private static List<GroupedClass> todayClasses(Context ctx) {
        List<CalendarEvent> events = DataCache.getInstance(ctx).loadEvents();
        if (events == null) return new ArrayList<>();
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, 1);
        List<CalendarEvent> today = new ArrayList<>();
        for (CalendarEvent e : events) {
            if (e.getDtstart() == null) continue;
            if (!e.getDtstart().before(start.getTime()) && e.getDtstart().before(end.getTime())) today.add(e);
        }
        return GroupedClass.groupBySubject(today);
    }
}
