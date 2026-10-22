package com.shakenokirimi12.uoa_app.ui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** 下タブから外した画面が「その他」に必ず出ることを確かめる。 */
public class MainTabsTest {

    @Test
    public void hiddenTabsAppearInMoreWithoutDuplicatesOrLoss() {
        List<String> main = MainTabs.mainTabKeys("home,grades");
        List<String> more = MainTabs.moreTabKeys(main, "gakushoku,facilities");

        assertTrue(more.containsAll(Arrays.asList("calendar", "courses")));
        assertEquals(new HashSet<>(more).size(), more.size());
        for (String key : main) assertFalse(more.contains(key));
        List<String> all = new ArrayList<>(main);
        all.addAll(more);
        assertEquals(new HashSet<>(MainTabs.allKeys()), new HashSet<>(all));
        assertEquals(MainTabs.allKeys().size(), all.size());
        // 保存した「その他」の並びが先、保存値に無いものは後ろ。
        assertEquals(Arrays.asList("gakushoku", "facilities"), more.subList(0, 2));
    }

    @Test
    public void unknownOrEmptySavedKeysStillLeaveATabAndLoseNothing() {
        List<String> main = MainTabs.mainTabKeys("map,,removed_tab");
        assertEquals(Arrays.asList(MainTabs.HOME), main);

        List<String> more = MainTabs.moreTabKeys(main, "map,calendar,calendar");
        assertEquals("calendar", more.get(0));
        assertEquals(MainTabs.allKeys().size() - 1, more.size());
        assertFalse(more.contains("map"));
    }

    @Test
    public void homeBeyondTheLimitIsKeptInTheBottomNav() {
        List<String> main = MainTabs.mainTabKeys("calendar,courses,grades,gakushoku,home");
        assertEquals(Arrays.asList(MainTabs.HOME, "calendar", "courses", "grades"), main);
        assertTrue(MainTabs.moreTabKeys(main, "").contains("gakushoku"));
    }
}
