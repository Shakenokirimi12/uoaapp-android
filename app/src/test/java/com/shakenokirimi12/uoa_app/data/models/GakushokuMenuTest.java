package com.shakenokirimi12.uoa_app.data.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.List;

public class GakushokuMenuTest {

    private static GakushokuMenu menu() {
        // Next week first, as the proxy returns it; the 9月 第5週 runs into October.
        String json = "{\"weeks\":["
                + "{\"title\":\"9月 第4週\",\"month\":9,\"weekIndex\":4,\"days\":["
                + "{\"dateString\":\"21日（月）\",\"day\":21,\"weekday\":\"月\",\"lunch\":[\"A\",\"a1\"],\"hasMenu\":true}]},"
                + "{\"title\":\"9月 第3週\",\"month\":9,\"weekIndex\":3,\"days\":["
                + "{\"dateString\":\"14日（月）\",\"day\":14,\"weekday\":\"月\",\"lunch\":[\"B\"],\"hasMenu\":true},"
                + "{\"dateString\":\"15日（火）\",\"day\":15,\"weekday\":\"火\",\"lunch\":[],\"hasMenu\":false}]},"
                + "{\"title\":\"9月 第5週\",\"month\":9,\"weekIndex\":5,\"days\":["
                + "{\"dateString\":\"29日（月）\",\"day\":29,\"weekday\":\"月\",\"lunch\":[\"C\"],\"hasMenu\":true},"
                + "{\"dateString\":\"1日（水）\",\"day\":1,\"weekday\":\"水\",\"lunch\":[\"D\"],\"hasMenu\":true}]}"
                + "],\"regularSets\":[{\"name\":\"日替わり丼\",\"price\":null}],\"hours\":[]}";
        return new Gson().fromJson(json, GakushokuMenu.class);
    }

    @Test
    public void findDay_resolvesByMonthAndDay_includingMonthRollover() {
        GakushokuMenu m = menu();
        assertEquals("B", m.findDay(9, 14).lunchMain());
        assertNotNull(m.findDay(10, 1));
        assertEquals("D", m.findDay(10, 1).lunchMain());
        assertNull(m.findDay(9, 1));
        assertNull(m.findDay(9, 16));
    }

    @Test
    public void weeksSortedFrom_putsCurrentWeekFirstThenChronological() {
        List<GakushokuMenu.Week> weeks = menu().weeksSortedFrom(9, 21);
        assertEquals("9月 第4週", weeks.get(0).title);
        assertEquals("9月 第5週", weeks.get(1).title);
        assertEquals("9月 第3週", weeks.get(2).title);
    }

    @Test
    public void weeksSortedFrom_onUnlistedDay_startsWithNextUpcomingWeek() {
        List<GakushokuMenu.Week> weeks = menu().weeksSortedFrom(9, 19);
        assertEquals("9月 第4週", weeks.get(0).title);
    }

    @Test
    public void lunchSides_areLinesAfterTheMain() {
        GakushokuMenu.Day day = menu().findDay(9, 21);
        assertEquals(1, day.lunchSides().size());
        assertEquals("a1", day.lunchSides().get(0));
    }
}
