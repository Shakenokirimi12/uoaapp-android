package com.shakenokirimi12.uoa_app.ui.calendar;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.color.MaterialColors;
import com.shakenokirimi12.uoa_app.R;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 月表示のカレンダー。プラットフォームの CalendarView は日付ごとの装飾ができないため、iOS の
 * CustomCalendarView と同じく授業 (primary) と課題の締切 (warning) を日付の下の点で示すために自前で描く。
 */
public final class MonthCalendarView extends LinearLayout {
    public static final int MARK_CLASS = 1;
    public static final int MARK_ASSIGNMENT = 2;

    public interface OnDateSelectedListener {
        void onDateSelected(int year, int month, int dayOfMonth);
    }

    private static final String[] WEEKDAYS = {"日", "月", "火", "水", "木", "金", "土"};

    private final TextView textMonth;
    private final LinearLayout rowWeekdays;
    private final GridLayout gridDays;
    /** 表示中の月 (1 日)。 */
    private final Calendar shown = Calendar.getInstance();
    private final Calendar selected = Calendar.getInstance();
    /** キーは dayKey()。値は MARK_* のビット和。 */
    private Map<Long, Integer> marks = new HashMap<>();
    @Nullable private OnDateSelectedListener listener;

    public MonthCalendarView(Context context) {
        this(context, null);
    }

    public MonthCalendarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_month_calendar, this, true);
        textMonth = findViewById(R.id.text_month);
        rowWeekdays = findViewById(R.id.row_weekdays);
        gridDays = findViewById(R.id.grid_days);
        findViewById(R.id.button_prev_month).setOnClickListener(v -> shiftMonth(-1));
        findViewById(R.id.button_next_month).setOnClickListener(v -> shiftMonth(1));

        for (String name : WEEKDAYS) {
            TextView tv = new TextView(context);
            tv.setText(name);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextAppearance(R.style.TextAppearance_Uoa_CalendarWeekDay);
            tv.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
            rowWeekdays.addView(tv, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        shown.set(Calendar.DAY_OF_MONTH, 1);
        render();
    }

    public void setOnDateSelectedListener(@Nullable OnDateSelectedListener listener) {
        this.listener = listener;
    }

    /** 選択日を変える (通知はしない)。月が違えば表示もその月へ動かす。 */
    public void setSelectedDate(int year, int month, int dayOfMonth) {
        selected.set(year, month, dayOfMonth);
        shown.set(year, month, 1);
        render();
    }

    public void setMarks(@NonNull Map<Long, Integer> marks) {
        this.marks = marks;
        render();
    }

    /** setMarks のキー。時刻を落とした日付の epoch ミリ秒。 */
    public static long dayKey(long timeMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timeMillis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void shiftMonth(int delta) {
        shown.add(Calendar.MONTH, delta);
        render();
    }

    private void render() {
        textMonth.setText(String.format(Locale.JAPAN, "%d年%d月", shown.get(Calendar.YEAR), shown.get(Calendar.MONTH) + 1));
        gridDays.removeAllViews();

        Calendar today = Calendar.getInstance();
        long todayKey = dayKey(today.getTimeInMillis());
        long selectedKey = dayKey(selected.getTimeInMillis());
        int primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        int onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary);
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);

        Calendar cursor = (Calendar) shown.clone();
        int leading = cursor.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int daysInMonth = cursor.getActualMaximum(Calendar.DAY_OF_MONTH);
        LayoutInflater inflater = LayoutInflater.from(getContext());

        // 6 週ぶん (42 マス) 固定にして、月をまたいでも高さが揺れないようにする。
        for (int cell = 0; cell < 42; cell++) {
            int day = cell - leading + 1;
            View item = inflater.inflate(R.layout.item_calendar_day, gridDays, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            item.setLayoutParams(lp);
            TextView textDay = item.findViewById(R.id.text_day);
            View dotClass = item.findViewById(R.id.dot_class);
            View dotAssignment = item.findViewById(R.id.dot_assignment);

            if (day < 1 || day > daysInMonth) {
                textDay.setText("");
                item.setClickable(false);
                gridDays.addView(item);
                continue;
            }
            cursor.set(Calendar.DAY_OF_MONTH, day);
            long key = dayKey(cursor.getTimeInMillis());
            textDay.setText(String.valueOf(day));

            if (key == selectedKey) {
                Drawable bg = DrawableCompat.wrap(ContextCompat.getDrawable(getContext(), R.drawable.bg_calendar_selected_day).mutate());
                DrawableCompat.setTint(bg, primary);
                textDay.setBackground(bg);
                textDay.setTextColor(onPrimary);
            } else if (key == todayKey) {
                Drawable bg = DrawableCompat.wrap(ContextCompat.getDrawable(getContext(), R.drawable.bg_calendar_today).mutate());
                DrawableCompat.setTint(bg, primary);
                textDay.setBackground(bg);
                textDay.setTextColor(primary);
            } else {
                textDay.setBackground(null);
                textDay.setTextColor(onSurface);
            }

            Integer mark = marks.get(key);
            int m = mark != null ? mark : 0;
            dotClass.setVisibility((m & MARK_CLASS) != 0 ? VISIBLE : INVISIBLE);
            dotAssignment.setVisibility((m & MARK_ASSIGNMENT) != 0 ? VISIBLE : INVISIBLE);

            final int y = cursor.get(Calendar.YEAR);
            final int mo = cursor.get(Calendar.MONTH);
            final int d = day;
            item.setOnClickListener(v -> {
                selected.set(y, mo, d);
                render();
                if (listener != null) listener.onDateSelected(y, mo, d);
            });
            gridDays.addView(item);
        }
    }
}
