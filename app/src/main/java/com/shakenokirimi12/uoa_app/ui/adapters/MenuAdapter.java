package com.shakenokirimi12.uoa_app.ui.adapters;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenu;

import java.util.ArrayList;
import java.util.List;

/** Week headers followed by one card per day. */
public class MenuAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_WEEK = 0;
    private static final int TYPE_DAY = 1;

    /** Either a week (header) or a day (card). */
    private static final class Row {
        final GakushokuMenu.Week week;
        final GakushokuMenu.Day day;
        Row(GakushokuMenu.Week week, GakushokuMenu.Day day) { this.week = week; this.day = day; }
    }

    private final List<Row> rows = new ArrayList<>();
    private List<GakushokuMenu.Week> weeks = new ArrayList<>();
    // 過ぎた日のメニューは見ても仕方がないので既定では隠し、週見出しのタップで開く。
    private boolean showPast = false;

    public void setWeeks(List<GakushokuMenu.Week> weeks) {
        this.weeks = weeks;
        rebuild();
    }

    private void rebuild() {
        rows.clear();
        for (GakushokuMenu.Week week : weeks) {
            if (week.days().isEmpty()) continue;
            List<GakushokuMenu.Day> visible = new ArrayList<>();
            for (GakushokuMenu.Day day : week.days()) {
                if (showPast || !GakushokuMenu.isPast(week, day)) visible.add(day);
            }
            if (visible.isEmpty()) continue;
            rows.add(new Row(week, null));
            for (GakushokuMenu.Day day : visible) rows.add(new Row(null, day));
        }
        notifyDataSetChanged();
    }

    /** Number of days hidden because they are already over. */
    public int hiddenPastCount() {
        if (showPast) return 0;
        int n = 0;
        for (GakushokuMenu.Week week : weeks) {
            for (GakushokuMenu.Day day : week.days()) if (GakushokuMenu.isPast(week, day)) n++;
        }
        return n;
    }

    public void setShowPast(boolean show) {
        if (showPast == show) return;
        showPast = show;
        rebuild();
    }

    public boolean isShowPast() { return showPast; }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).week != null ? TYPE_WEEK : TYPE_DAY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_WEEK) {
            return new WeekHolder(inflater.inflate(R.layout.item_menu_week_header, parent, false));
        }
        return new DayHolder(inflater.inflate(R.layout.item_menu_day, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof WeekHolder) {
            ((WeekHolder) holder).title.setText(row.week.title);
            return;
        }
        DayHolder h = (DayHolder) holder;
        GakushokuMenu.Day day = row.day;
        h.textDate.setText(day.dateString);
        String main = day.lunchMain();
        boolean hasMenu = day.hasContent();
        h.textNone.setVisibility(hasMenu ? View.GONE : View.VISIBLE);
        h.textHeadline.setVisibility(hasMenu && main != null ? View.VISIBLE : View.GONE);
        h.textHeadline.setText(main);
        if (hasMenu) {
            bindCategoryRows(h.layoutRows, day);
        } else {
            h.layoutRows.removeAllViews();
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private static boolean isEmpty(List<String> lines) {
        return lines == null || lines.isEmpty();
    }

    /**
     * Fills container with one labeled row per non-empty category. Shared with the Home card so
     * the two places show the same breakdown: lunch sides, noodles, fish, salad, dinner.
     */
    public static void bindCategoryRows(LinearLayout container, GakushokuMenu.Day day) {
        container.removeAllViews();
        addCategoryRow(container, "副菜", day.lunchSides());
        addCategoryRow(container, "麺類", day.noodles);
        addCategoryRow(container, "魚", day.fish);
        addCategoryRow(container, "サラダ", day.salad);
        addCategoryRow(container, "夕食", day.dinner);
    }

    private static void addCategoryRow(LinearLayout container, String label, List<String> lines) {
        if (isEmpty(lines)) return;
        View row = LayoutInflater.from(container.getContext())
                .inflate(R.layout.item_menu_category_row, container, false);
        ((TextView) row.findViewById(R.id.text_menu_category_label)).setText(label);
        ((TextView) row.findViewById(R.id.text_menu_category_value)).setText(TextUtils.join("、", lines));
        container.addView(row);
    }

    static class WeekHolder extends RecyclerView.ViewHolder {
        final TextView title;
        WeekHolder(View view) {
            super(view);
            title = view.findViewById(R.id.text_menu_week_title);
        }
    }

    static class DayHolder extends RecyclerView.ViewHolder {
        final TextView textDate;
        final TextView textHeadline;
        final LinearLayout layoutRows;
        final TextView textNone;

        DayHolder(View view) {
            super(view);
            textDate = view.findViewById(R.id.text_menu_date);
            textHeadline = view.findViewById(R.id.text_menu_headline);
            layoutRows = view.findViewById(R.id.layout_menu_rows);
            textNone = view.findViewById(R.id.text_menu_none);
        }
    }
}
