package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.timetable.SharedClass;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableGrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1 週間の時間割の表 (行 = 1〜2 限…、列 = 月〜金)。友達の時間割を出し、mine を渡すと自分との重なりも出す。
 * iOS の TimetableGridView と同じ見た目: 同じ授業は緑の塗りと「同じ」、どちらも空きは緑の点線と「空き」。
 */
public class TimetableGridView extends LinearLayout {
    private static final int HEADER_WIDTH_DP = 34;
    private static final int CELL_MIN_HEIGHT_DP = 72;
    private static final int GAP_DP = 4;

    public TimetableGridView(Context context) { this(context, null); }

    public TimetableGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    /** @param mine 自分の時間割。null なら重なりを出さない。 */
    public void setData(@NonNull List<SharedClass> classes, @Nullable List<SharedClass> mine) {
        removeAllViews();
        List<SharedClass> other = mine != null ? mine : Collections.emptyList();
        List<Integer> days = TimetableGrid.days(classes, other);
        List<int[]> rows = TimetableGrid.rows(classes, other);

        LinearLayout header = row();
        header.addView(new View(getContext()), new LayoutParams(dp(HEADER_WIDTH_DP), 1));
        for (int day : days) {
            TextView label = text(TimetableGrid.dayLabel(getContext(), day), 14, true, MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface));
            label.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(GAP_DP));
            header.addView(label, lp);
        }
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        for (int[] r : rows) {
            LinearLayout line = row();
            LinearLayout head = new LinearLayout(getContext());
            head.setOrientation(VERTICAL);
            head.setGravity(Gravity.CENTER);
            head.addView(text(TimetableGrid.rowHeader(r), 12, true, MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)));
            head.addView(text(TimetableGrid.rowStartTime(r), 9, false, MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)));
            line.addView(head, new LayoutParams(dp(HEADER_WIDTH_DP), LayoutParams.MATCH_PARENT));
            for (int day : days) {
                line.addView(cell(classes, mine, day, r), cellParams());
            }
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(GAP_DP);
            addView(line, lp);
        }
    }

    /** 同じ授業の塗り。どちらも空きは、この色を薄くしたもの (FREE_ALPHA)。凡例 (view_timetable_legend) と同じ。 */
    static final int FREE_ALPHA = 77;

    private View cell(List<SharedClass> classes, @Nullable List<SharedClass> mine, int day, int[] r) {
        List<SharedClass> here = TimetableGrid.classes(classes, day, r);
        List<SharedClass> mineHere = mine != null ? TimetableGrid.classes(mine, day, r) : Collections.emptyList();
        TimetableGrid.Overlap overlap = mine != null ? TimetableGrid.overlap(classes, mine, day, r) : null;
        int success = ContextCompat.getColor(getContext(), R.color.success);
        int successContainer = ContextCompat.getColor(getContext(), R.color.success_container);

        FrameLayout frame = new FrameLayout(getContext());
        frame.setMinimumHeight(dp(CELL_MIN_HEIGHT_DP));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (overlap == TimetableGrid.Overlap.SAME) {
            bg.setColor(successContainer);
        } else if (overlap == TimetableGrid.Overlap.BOTH_FREE) {
            // どちらも空きは色だけで示す (文字や枠を全部の空きコマに入れると、表が埋まって授業が埋もれる)。
            bg.setColor(ColorUtils.setAlphaComponent(successContainer, FREE_ALPHA));
        } else if (overlap == TimetableGrid.Overlap.MINE_ONLY) {
            bg.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        } else if (!here.isEmpty()) {
            // 授業のあるコマと空きのコマが見分けられるよう、授業は色の付いた面、空きはカードより明るい面にする。
            bg.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer));
        } else {
            bg.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLowest));
        }
        frame.setBackground(bg);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(5), dp(5), dp(5), dp(5));
        int onSurface = MaterialColors.getColor(this, overlap == null && !here.isEmpty()
                ? com.google.android.material.R.attr.colorOnPrimaryContainer : com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        List<String> spoken = new ArrayList<>();
        spoken.add(getContext().getString(R.string.timetable_grid_day_spoken_format,
                TimetableGrid.dayLabel(getContext(), day), TimetableGrid.rowLabel(getContext(), r)));
        if (!here.isEmpty()) {
            SharedClass first = here.get(0);
            TextView title = text(first.title, 11, true, onSurface);
            title.setMaxLines(4);
            title.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(title);
            if (!first.room.isEmpty()) {
                TextView room = text(first.room, 10, false, onSurfaceVariant);
                room.setMaxLines(1);
                room.setEllipsize(TextUtils.TruncateAt.END);
                content.addView(room);
            }
            if (here.size() > 1) content.addView(text(getContext().getString(R.string.timetable_grid_more_format, here.size() - 1), 9, false, onSurfaceVariant));
            for (SharedClass c : here) spoken.add(c.room.isEmpty() ? c.title : c.title + " " + c.room);
        } else {
            spoken.add(getContext().getString(R.string.timetable_grid_no_class_spoken));
            if (overlap == TimetableGrid.Overlap.MINE_ONLY && !mineHere.isEmpty()) {
                // 友達は空き、自分は授業。自分の授業名を薄く出して「空き」と取り違えないようにする。
                content.addView(text(getContext().getString(R.string.timetable_legend_mine_label), 9, true, onSurfaceVariant));
                TextView mineTitle = text(mineHere.get(0).title, 10, false, onSurfaceVariant);
                mineTitle.setMaxLines(3);
                mineTitle.setEllipsize(TextUtils.TruncateAt.END);
                content.addView(mineTitle);
            }
        }
        // 下に寄せる印 (同じ / 自分も授業)。
        content.addView(new View(getContext()), new LayoutParams(1, 0, 1f));
        if (overlap == TimetableGrid.Overlap.SAME) {
            TextView badge = text(getContext().getString(R.string.timetable_grid_same_badge), 9, true, ContextCompat.getColor(getContext(), R.color.white));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(8));
            pill.setColor(success);
            badge.setBackground(pill);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            content.addView(badge, lp);
            spoken.add(getContext().getString(R.string.timetable_grid_same_class_spoken));
        } else if (overlap == TimetableGrid.Overlap.BOTH_BUSY) {
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            content.addView(text(getContext().getString(R.string.timetable_grid_mine_busy), 9, false, onSurfaceVariant), lp);
        }
        if ((overlap == TimetableGrid.Overlap.MINE_ONLY || overlap == TimetableGrid.Overlap.BOTH_BUSY) && !mineHere.isEmpty()) {
            spoken.add(getContext().getString(R.string.timetable_grid_mine_class_spoken_format, mineHere.get(0).title));
        }
        if (overlap == TimetableGrid.Overlap.BOTH_FREE) spoken.add(getContext().getString(R.string.timetable_legend_free));
        frame.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        frame.setContentDescription(TextUtils.join(getContext().getString(R.string.timetable_grid_list_separator), spoken));
        frame.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return frame;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(HORIZONTAL);
        return l;
    }

    private LayoutParams cellParams() {
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        lp.setMarginStart(dp(GAP_DP));
        return lp;
    }

    private TextView text(String s, float sp, boolean bold, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.05f);
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
