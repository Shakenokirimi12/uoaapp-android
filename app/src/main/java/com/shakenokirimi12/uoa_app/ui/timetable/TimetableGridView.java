package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.timetable.SharedClass;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableGrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1 週間の時間割の表 (見出しの行 = 1〜2 限…、列 = 月〜金。続いている授業は 1 つの箱)。友達の時間割を出し、
 * mine を渡すと自分との重なりも出す。iOS の TimetableGridView と同じ見た目と動き: 友達の授業・自分の授業・どちらも授業を
 * 別の色にし、空きは灰色。曜日をタップするとその列を広げ、比べているときは友達と自分の 2 列にする。
 */
public class TimetableGridView extends LinearLayout {
    private static final int HEADER_WIDTH_DP = 34;
    /** 1 時限の高さ。高さを決めておくと、曜日の列と左の見出しの行がずれない (2 時限の行 = 80dp)。 */
    private static final int PERIOD_HEIGHT_DP = 38;
    private static final int GAP_DP = 4;

    public TimetableGridView(Context context) { this(context, null); }

    public TimetableGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    /** タップして広げた曜日。もう一度タップすると戻す。 */
    @Nullable private Integer expandedDay;
    @NonNull private List<SharedClass> classes = Collections.emptyList();
    @Nullable private List<SharedClass> mine;

    /** @param mine 自分の時間割。null なら重なりを出さない。 */
    public void setData(@NonNull List<SharedClass> classes, @Nullable List<SharedClass> mine) {
        this.classes = classes;
        this.mine = mine;
        render();
    }

    /** 広げた列の幅 (ほかの列の何倍か)。自分と比べているときは、友達と自分の 2 列を並べるのでもっと広げる。 */
    private float expandedWeight() { return mine == null ? 1.8f : 2.6f; }

    /** 広げた曜日を、友達と自分の 2 列に分けて出すか。 */
    private boolean showsBoth(int day) { return expandedDay != null && expandedDay == day && mine != null; }

    private float weight(int day) { return expandedDay != null && expandedDay == day ? expandedWeight() : 1f; }

    private void toggle(int day) {
        expandedDay = expandedDay != null && expandedDay == day ? null : day;
        TransitionManager.beginDelayedTransition(this, new AutoTransition().setDuration(180));
        render();
    }

    private void render() {
        removeAllViews();
        List<SharedClass> other = mine != null ? mine : Collections.emptyList();
        List<Integer> days = TimetableGrid.days(classes, other);
        List<int[]> rows = TimetableGrid.rows(classes, other);
        if (expandedDay != null && !days.contains(expandedDay)) expandedDay = null;
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);

        LinearLayout header = row();
        header.addView(new View(getContext()), new LayoutParams(dp(HEADER_WIDTH_DP), 1));
        for (int day : days) {
            LinearLayout cell = column();
            TextView label = text(TimetableGrid.dayLabel(getContext(), day), 14, true,
                    expandedDay != null && expandedDay == day ? primary : onSurface);
            label.setGravity(Gravity.CENTER);
            cell.addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            if (showsBoth(day)) {
                LinearLayout sub = row();
                sub.addView(subHeader(R.string.timetable_grid_friend, onSurfaceVariant), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                LayoutParams mineLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                mineLp.setMarginStart(dp(GAP_DP));
                sub.addView(subHeader(R.string.timetable_legend_mine_label, onSurfaceVariant), mineLp);
                LayoutParams subLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                subLp.topMargin = dp(2);
                cell.addView(sub, subLp);
            }
            cell.setPadding(0, 0, 0, dp(2));
            cell.setOnClickListener(v -> toggle(day));
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, weight(day));
            lp.setMarginStart(dp(GAP_DP));
            header.addView(cell, lp);
        }
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 列ごとに箱を縦に並べる。続いている授業は行をまたいで 1 つの箱にする (TimetableGrid.segments)。
        // 左の見出しは 2 時限ごとの行のまま。
        LinearLayout body = row();
        LinearLayout heads = column();
        for (int i = 0; i < rows.size(); i++) {
            int[] r = rows.get(i);
            LinearLayout head = new LinearLayout(getContext());
            head.setOrientation(VERTICAL);
            head.setGravity(Gravity.CENTER);
            head.addView(text(TimetableGrid.rowHeader(r), 12, true, onSurface));
            head.addView(text(TimetableGrid.rowStartTime(r), 9, false, onSurfaceVariant));
            heads.addView(head, stacked(r, i));
        }
        body.addView(heads, new LayoutParams(dp(HEADER_WIDTH_DP), LayoutParams.WRAP_CONTENT));
        for (int day : days) {
            // 広げた曜日は、友達 (左) と自分 (右) の時間割を 2 列で並べる (重なりの色は付けない)。
            LinearLayout pair = row();
            pair.addView(column(day, rows, classes, showsBoth(day) ? null : mine, false),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            if (showsBoth(day)) {
                LayoutParams mineLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                mineLp.setMarginStart(dp(GAP_DP));
                pair.addView(column(day, rows, mine, null, true), mineLp);
            }
            // 列をタップすると、その曜日を横に広げる (狭い列で切れた授業名を読むため)。
            pair.setOnClickListener(v -> toggle(day));
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, weight(day));
            lp.setMarginStart(dp(GAP_DP));
            body.addView(pair, lp);
        }
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(GAP_DP);
        addView(body, lp);
    }

    private TextView subHeader(int res, int color) {
        TextView t = text(getContext().getString(res), 9, true, color);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        return t;
    }

    /** 1 つの曜日の箱の列。list はこの列の時間割、against は比べる自分の時間割。 */
    private LinearLayout column(int day, List<int[]> rows, List<SharedClass> list, @Nullable List<SharedClass> against, boolean isMine) {
        LinearLayout col = column();
        List<int[]> segments = TimetableGrid.segments(list, against, day, rows);
        for (int i = 0; i < segments.size(); i++) {
            int[] seg = segments.get(i);
            col.addView(box(list, against, isMine, day, seg), stacked(seg, i));
        }
        return col;
    }

    /**
     * 縦に積む 1 つ (見出しの行か箱)。高さは時限の数で決め、2 つ目からは上に間を空ける。
     * 文字は sp なので、端末の文字サイズを大きくしたぶん時限の高さも伸ばす (dp のままだと授業名や教室が箱からはみ出して切れる)。
     */
    private LayoutParams stacked(int[] periods, int index) {
        int count = periods[1] - periods[0] + 1;
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(count * PERIOD_HEIGHT_DP * textScale() + (count - 1) * GAP_DP));
        if (index > 0) lp.topMargin = dp(GAP_DP);
        return lp;
    }

    /**
     * 箱の色。比べるときは、友達だけの授業・自分だけの授業・どちらも授業 (かぶり) を別の色にし、空きは灰色。
     * 凡例 (view_timetable_legend) と同じ色。
     */
    private int fill(boolean hasClass, @Nullable TimetableGrid.Overlap overlap) {
        int friend = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer);
        int free = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        if (overlap == null) return hasClass ? friend : free;
        switch (overlap) {
            case SAME:
            case BOTH_BUSY: return ContextCompat.getColor(getContext(), R.color.timetable_both);
            case MINE_ONLY: return ContextCompat.getColor(getContext(), R.color.timetable_mine);
            case FRIEND_ONLY: return friend;
            default: return free;
        }
    }

    /** 1 つの箱 (続いている同じ授業、または行の中の空き)。isMine は、広げた曜日の自分の列 (授業の箱を「自分」の色で出す)。 */
    private View box(List<SharedClass> list, @Nullable List<SharedClass> against, boolean isMine, int day, int[] r) {
        int count = r[1] - r[0] + 1;
        // 1 時限の箱は狭いので、授業名と教室を 1 行ずつだけ出す (重なりは色で分かる)。
        boolean compact = count == 1;
        int titleLines = compact ? 1 : count * 2;
        List<SharedClass> here = TimetableGrid.classes(list, day, r);
        List<SharedClass> mineHere = against != null ? TimetableGrid.classes(against, day, r) : Collections.emptyList();
        TimetableGrid.Overlap overlap = against != null ? TimetableGrid.overlap(list, against, day, r) : null;
        boolean friendFill = !isMine && !here.isEmpty() && (overlap == null || overlap == TimetableGrid.Overlap.FRIEND_ONLY);

        FrameLayout frame = new FrameLayout(getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(isMine && !here.isEmpty()
                ? ContextCompat.getColor(getContext(), R.color.timetable_mine) : fill(!here.isEmpty(), overlap));
        frame.setBackground(bg);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        int pad = dp(compact ? 4 : 5);
        content.setPadding(pad, pad, pad, pad);
        int onSurface = MaterialColors.getColor(this, friendFill
                ? com.google.android.material.R.attr.colorOnPrimaryContainer : com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        List<String> spoken = new ArrayList<>();
        spoken.add(getContext().getString(R.string.timetable_grid_day_spoken_format,
                TimetableGrid.dayLabel(getContext(), day), TimetableGrid.rowLabel(getContext(), r)));
        if (!here.isEmpty()) {
            SharedClass first = here.get(0);
            TextView title = text(first.title, 11, true, onSurface);
            title.setMaxLines(titleLines);
            title.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(title);
            if (compact && here.size() > 1) {
                // 1 時限の箱は 2 行しか入らないので、同じ時限にほかの授業があるときは教室の代わりに件数を出す。
                TextView more = text(getContext().getString(R.string.timetable_grid_more_format, here.size() - 1), 9, false, onSurfaceVariant);
                more.setMaxLines(1);
                content.addView(more);
            } else if (!first.room.isEmpty()) {
                TextView room = text(first.room, 10, false, onSurfaceVariant);
                room.setMaxLines(1);
                room.setEllipsize(TextUtils.TruncateAt.END);
                content.addView(room);
            }
            if (here.size() > 1 && !compact) content.addView(text(getContext().getString(R.string.timetable_grid_more_format, here.size() - 1), 9, false, onSurfaceVariant));
            if (isMine) {
                // 広げた曜日の自分の列。友達の授業の有無は隣の列で読むので、ここでは自分の授業だけを読む。
                for (SharedClass c : here) spoken.add(getContext().getString(R.string.timetable_grid_mine_class_spoken_format,
                        c.room.isEmpty() ? c.title : c.title + " " + c.room));
            } else {
                for (SharedClass c : here) spoken.add(c.room.isEmpty() ? c.title : c.title + " " + c.room);
            }
        } else {
            if (isMine) spoken.add(getContext().getString(R.string.timetable_legend_mine_label));
            spoken.add(getContext().getString(R.string.timetable_grid_no_class_spoken));
            if (overlap == TimetableGrid.Overlap.MINE_ONLY && !mineHere.isEmpty()) {
                // 友達は空き、自分は授業。自分の授業名を薄く出して「空き」と取り違えないようにする。
                content.addView(text(getContext().getString(R.string.timetable_legend_mine_label), 9, true, onSurfaceVariant));
                TextView mineTitle = text(mineHere.get(0).title, 10, false, onSurfaceVariant);
                mineTitle.setMaxLines(compact ? 1 : titleLines - 1);
                mineTitle.setEllipsize(TextUtils.TruncateAt.END);
                content.addView(mineTitle);
            }
        }
        // 下に寄せる印 (同じ / 自分も授業)。
        content.addView(new View(getContext()), new LayoutParams(1, 0, 1f));
        if (compact) {
            if (overlap == TimetableGrid.Overlap.SAME) spoken.add(getContext().getString(R.string.timetable_grid_same_class_spoken));
        } else if (overlap == TimetableGrid.Overlap.SAME) {
            TextView badge = text(getContext().getString(R.string.timetable_grid_same_badge), 9, true, ContextCompat.getColor(getContext(), R.color.white));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(8));
            pill.setColor(ContextCompat.getColor(getContext(), R.color.timetable_both_badge));
            badge.setBackground(pill);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            content.addView(badge, lp);
            spoken.add(getContext().getString(R.string.timetable_grid_same_class_spoken));
        } else if (overlap == TimetableGrid.Overlap.BOTH_BUSY) {
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            TextView meToo = text(getContext().getString(R.string.timetable_grid_mine_busy), 9, false, onSurfaceVariant);
            meToo.setMaxLines(1);
            meToo.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(meToo, lp);
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

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(VERTICAL);
        return l;
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

    /** 箱の授業名 (11sp) が、文字サイズの設定で何倍になっているか。小さくする設定では縮めない。Android 14 以降の非線形の拡大にも合う。 */
    private float textScale() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, metrics);
        float dip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 11, metrics);
        return Math.max(1f, sp / dip);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
