package com.shakenokirimi12.uoa_app.services.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.shakenokirimi12.uoa_app.data.models.GakushokuMenu;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;

public class LunchMenuPlanTest {

    /** 日本時間の y/m/d h:mi。 */
    private static long tokyo(int y, int m, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(LunchMenuPlan.TOKYO);
        c.clear();
        c.set(y, m - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    private static GakushokuMenu menuWith(int month, GakushokuMenu.Day... days) {
        GakushokuMenu.Week w = new GakushokuMenu.Week();
        w.month = month;
        w.days = new ArrayList<>(Arrays.asList(days));
        GakushokuMenu menu = new GakushokuMenu();
        menu.weeks = new ArrayList<>(Collections.singletonList(w));
        return menu;
    }

    private static GakushokuMenu.Day day(int d, boolean hasMenu) {
        GakushokuMenu.Day day = new GakushokuMenu.Day();
        day.day = d;
        day.dateString = "10/" + d;
        day.hasMenu = hasMenu;
        return day;
    }

    @Test
    public void nextTrigger_laterToday_orTomorrowWhenPassed() {
        long morning = tokyo(2026, 10, 16, 9, 0);
        assertEquals(tokyo(2026, 10, 16, 11, 30), LunchMenuPlan.nextTriggerAt(morning, "11:30"));

        long afternoon = tokyo(2026, 10, 16, 12, 0);
        assertEquals(tokyo(2026, 10, 17, 11, 30), LunchMenuPlan.nextTriggerAt(afternoon, "11:30"));

        // ちょうどその時刻 (= 今動いている実行) なら翌日。
        assertEquals(tokyo(2026, 10, 17, 11, 30), LunchMenuPlan.nextTriggerAt(tokyo(2026, 10, 16, 11, 30), "11:30"));
        assertEquals(-1, LunchMenuPlan.nextTriggerAt(morning, "25:00"));
        assertEquals(-1, LunchMenuPlan.nextTriggerAt(morning, "garbage"));
    }

    @Test
    public void decide_postsTodaysRealMenu() {
        GakushokuMenu.Day today = day(16, true);
        today.lunch = Arrays.asList("カレー", "サラダ付き");
        today.fish = Collections.singletonList("鮭定食");
        GakushokuMenu menu = menuWith(10, day(15, true), today);

        long at = tokyo(2026, 10, 16, 11, 30);
        LunchMenuPlan.Content content = LunchMenuPlan.decide(menu, at, at + 60_000);

        assertNotNull(content);
        assertEquals("今日の学食メニュー (10/16)", content.title);
        assertEquals("ランチ: カレー / サラダ付き\n魚: 鮭定食", content.body);
    }

    @Test
    public void decide_nothingWhenTheDayHasNoMenu() {
        long at = tokyo(2026, 10, 16, 11, 30);
        // 休業日 (hasMenu=false)。
        assertNull(LunchMenuPlan.decide(menuWith(10, day(16, false)), at, at));
        // hasMenu だが中身が空。
        assertNull(LunchMenuPlan.decide(menuWith(10, day(16, true)), at, at));
        // その日が載っていない。
        GakushokuMenu.Day other = day(15, true);
        other.lunch = Collections.singletonList("カレー");
        assertNull(LunchMenuPlan.decide(menuWith(10, other), at, at));
        // メニューが取れなかった (キャッシュも無い)。
        assertNull(LunchMenuPlan.decide(null, at, at));
    }

    @Test
    public void decide_nothingWhenRunTooLate() {
        GakushokuMenu.Day today = day(16, true);
        today.noodles = Collections.singletonList("ラーメン");
        GakushokuMenu menu = menuWith(10, today);
        long at = tokyo(2026, 10, 16, 11, 30);

        assertNotNull(LunchMenuPlan.decide(menu, at, at + LunchMenuPlan.MAX_LATENESS_MS));
        assertNull(LunchMenuPlan.decide(menu, at, at + LunchMenuPlan.MAX_LATENESS_MS + 1));
    }
}
