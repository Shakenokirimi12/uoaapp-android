package com.shakenokirimi12.uoa_app.ui.util;

import android.view.Menu;

import androidx.annotation.NonNull;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「タブの表示設定」(NavSettingsFragment で保存する main/other のキー列) を実際の画面に反映する。
 * 以前は設定を保存するだけで、下タブは固定メニュー (home/calendar/courses/more) のままだった。
 * 下タブは main の先頭 MAX_MAIN 個 + 「その他」、その他画面には main に無いものだけを並べる (iOS と同じ)。
 */
public final class MainTabs {
    /** BottomNavigationView は 5 個まで。「その他」を除いて 4 個。 */
    public static final int MAX_MAIN = 4;

    private static final class Tab {
        final int destinationId;
        final int iconRes;
        final String label;

        Tab(int destinationId, int iconRes, String label) {
            this.destinationId = destinationId;
            this.iconRes = iconRes;
            this.label = label;
        }
    }

    private static final Map<String, Tab> TABS = new LinkedHashMap<>();
    static {
        TABS.put("home", new Tab(R.id.navigation_home, R.drawable.ic_home, "ホーム"));
        TABS.put("calendar", new Tab(R.id.navigation_calendar, R.drawable.ic_calendar, "カレンダー"));
        TABS.put("courses", new Tab(R.id.navigation_courses, R.drawable.ic_courses, "履修"));
        TABS.put("grades", new Tab(R.id.navigation_grades, R.drawable.ic_grades, "成績"));
        TABS.put("gakushoku", new Tab(R.id.navigation_gakushoku, R.drawable.ic_restaurant, "学食"));
        TABS.put("facilities", new Tab(R.id.navigation_facilities, R.drawable.ic_building, "施設利用"));
        TABS.put("reviews", new Tab(R.id.navigation_reviews, R.drawable.ic_review, "授業評価"));
    }

    private MainTabs() {}

    /** 下タブに出すキー (設定順、未知のキーは除外、最大 MAX_MAIN)。home が無ければ先頭に足す。 */
    @NonNull
    public static List<String> mainTabKeys(@NonNull PreferenceManager prefs) {
        List<String> keys = new ArrayList<>();
        for (String key : prefs.getMainTabs().split(",")) {
            String k = key.trim();
            if (TABS.containsKey(k) && !keys.contains(k)) keys.add(k);
        }
        // ホームは startDestination なので必ず下タブに要る (無いとタブに無い画面が初期表示になる)。
        if (!keys.contains("home")) keys.add(0, "home");
        return keys.size() > MAX_MAIN ? new ArrayList<>(keys.subList(0, MAX_MAIN)) : keys;
    }

    /** 下タブのメニューを設定どおりに組み直す。NavigationUI と結び付ける前に呼ぶこと。 */
    public static void applyTo(@NonNull BottomNavigationView bottomNav, @NonNull PreferenceManager prefs) {
        Menu menu = bottomNav.getMenu();
        menu.clear();
        for (String key : mainTabKeys(prefs)) {
            Tab tab = TABS.get(key);
            // item id = destination id にしておくと NavigationUI がそのまま遷移させてくれる。
            menu.add(Menu.NONE, tab.destinationId, Menu.NONE, tab.label).setIcon(tab.iconRes);
        }
        menu.add(Menu.NONE, R.id.navigation_more, Menu.NONE, "その他").setIcon(R.drawable.ic_more);
    }

    public static boolean isInMainTabs(@NonNull PreferenceManager prefs, @NonNull String key) {
        return mainTabKeys(prefs).contains(key);
    }

    /**
     * 取りうるタブの遷移先 id すべて + 「その他」。AppBarConfiguration のトップレベル集合に使う。
     * どのタブが下タブに出ていても根として扱えるようにしておけば、タブ構成変更で再結線が要らない。
     */
    @NonNull
    public static java.util.Set<Integer> allRootDestinationIds() {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Tab tab : TABS.values()) ids.add(tab.destinationId);
        ids.add(R.id.navigation_more);
        return ids;
    }

    public static List<String> allKeys() { return new ArrayList<>(Arrays.asList(TABS.keySet().toArray(new String[0]))); }
}
