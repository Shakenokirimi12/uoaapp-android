package com.shakenokirimi12.uoa_app.services.notification;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.GakushokuMenu;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * 学食メニュー通知の判断 (LunchMenuPlanTest で固定)。判断は Android に依存しない。文字にする Content.title / body だけ Context を取る。
 * iOS の NotificationManager.scheduleLunchNotifications と同じ文面・同じ条件:
 * その日のメニューが公開されている日だけ、設定した時刻に 1 回。メニューが無い日は何も出さない。
 */
public final class LunchMenuPlan {
    private LunchMenuPlan() {}

    /** メニューの日付は日本時間。端末のタイムゾーンに関係なく、印刷された日に出す。 */
    static final TimeZone TOKYO = TimeZone.getTimeZone("Asia/Tokyo");

    /**
     * 予定時刻からこれ以上遅れて動いたら出さない。WorkManager は Doze 中に実行を遅らせることがあり、
     * 夕方に「今日の学食メニュー」が届いても意味がない。
     */
    static final long MAX_LATENESS_MS = 2 * 60 * 60_000L;

    /** 本文の 1 行 (カテゴリ名の資源 ID と品目)。文字にするのは通知を出す側 (Context が要るため)。 */
    public static final class Line {
        @StringRes public final int label;
        @NonNull public final List<String> items;

        Line(@StringRes int label, @NonNull List<String> items) {
            this.label = label;
            this.items = items;
        }
    }

    public static final class Content {
        /** 通知の題に入れる日付 (メニューに印刷された表記、無ければ「月/日」)。 */
        @NonNull public final String date;
        @NonNull public final List<Line> lines;

        Content(@NonNull String date, @NonNull List<Line> lines) {
            this.date = date;
            this.lines = lines;
        }

        @NonNull
        public String title(@NonNull Context ctx) {
            return ctx.getString(R.string.lunch_menu_notification_title_format, date);
        }

        @NonNull
        public String body(@NonNull Context ctx) {
            List<String> out = new ArrayList<>();
            for (Line line : lines) out.add(ctx.getString(line.label) + ": " + String.join(" / ", line.items));
            return String.join("\n", out);
        }
    }

    /** "HH:mm" を [時, 分] に。読めなければ null (通知を予約しない)。 */
    @Nullable
    static int[] parseTime(@Nullable String hhmm) {
        if (hhmm == null) return null;
        String[] parts = hhmm.trim().split(":");
        if (parts.length != 2) return null;
        try {
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[] {h, m};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** now より後で最初に来る (日本時間の) hh:mm。時刻が読めなければ -1。 */
    public static long nextTriggerAt(long now, @Nullable String hhmm) {
        int[] t = parseTime(hhmm);
        if (t == null) return -1;
        Calendar c = Calendar.getInstance(TOKYO);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, t[0]);
        c.set(Calendar.MINUTE, t[1]);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /**
     * scheduledFor に出す予定だった通知の中身。出さないなら null:
     * メニューが無い / その日が載っていない / その日の欄が空 / 予定より大きく遅れた。
     */
    @Nullable
    public static Content decide(@Nullable GakushokuMenu menu, long scheduledFor, long now) {
        if (menu == null) return null;
        if (now - scheduledFor > MAX_LATENESS_MS) return null;
        Calendar day = Calendar.getInstance(TOKYO);
        day.setTimeInMillis(scheduledFor);
        GakushokuMenu.Day entry = menu.findDay(day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
        if (entry == null || !entry.hasContent()) return null;

        // hasMenu だけでは全カテゴリ空のことがある。どのカテゴリだけの日でも本文が空にならないよう全部を候補にする。
        List<Line> lines = new ArrayList<>();
        addLine(lines, R.string.gakushoku_lunch, entry.lunch);
        addLine(lines, R.string.gakushoku_category_noodles, entry.noodles);
        addLine(lines, R.string.gakushoku_category_fish, entry.fish);
        addLine(lines, R.string.gakushoku_salad, entry.salad);
        addLine(lines, R.string.gakushoku_category_dinner, entry.dinner);
        if (lines.isEmpty()) return null;

        String date = entry.dateString != null && !entry.dateString.isEmpty()
                ? entry.dateString
                : (day.get(Calendar.MONTH) + 1) + "/" + day.get(Calendar.DAY_OF_MONTH);
        return new Content(date, lines);
    }

    private static void addLine(List<Line> lines, @StringRes int label, @Nullable List<String> items) {
        if (items == null || items.isEmpty()) return;
        lines.add(new Line(label, items));
    }
}
