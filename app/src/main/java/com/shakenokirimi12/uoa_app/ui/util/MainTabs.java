package com.shakenokirimi12.uoa_app.ui.util;

import android.view.Menu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
 * 下タブは main の先頭 MAX_MAIN 個 + 「その他」。「その他」画面の機能の行は moreTabKeys から作る
 * (タブに置ける画面のうち下タブに無いもの)。タブに置ける画面の一覧はここの TABS だけに持つ。
 */
public final class MainTabs {
    /** BottomNavigationView は 5 個まで。「その他」を除いて 4 個。 */
    public static final int MAX_MAIN = 4;

    /** startDestination。必ず下タブに置く (無いとタブに無い画面が初期表示になる)。 */
    public static final String HOME = "home";

    private static final class Tab {
        final int destinationId;
        final int iconRes;
        final String label;
        /** 「その他」の行に出す名前。下タブより幅があるので、略さない名前にする。 */
        final String moreLabel;

        Tab(int destinationId, int iconRes, String label, String moreLabel) {
            this.destinationId = destinationId;
            this.iconRes = iconRes;
            this.label = label;
            this.moreLabel = moreLabel;
        }
    }

    private static final Map<String, Tab> TABS = new LinkedHashMap<>();
    static {
        TABS.put(HOME, new Tab(R.id.navigation_home, R.drawable.ic_home, "ホーム", "ホーム"));
        TABS.put("calendar", new Tab(R.id.navigation_calendar, R.drawable.ic_calendar, "カレンダー", "カレンダー"));
        TABS.put("courses", new Tab(R.id.navigation_courses, R.drawable.ic_courses, "履修", "履修"));
        TABS.put("grades", new Tab(R.id.navigation_grades, R.drawable.ic_grades, "成績", "成績一覧"));
        TABS.put("gakushoku", new Tab(R.id.navigation_gakushoku, R.drawable.ic_restaurant, "学食", "学食メニュー"));
        TABS.put("facilities", new Tab(R.id.navigation_facilities, R.drawable.ic_building, "施設利用", "施設利用状況"));
        TABS.put("reviews", new Tab(R.id.navigation_reviews, R.drawable.ic_review, "授業評価", "授業評価"));
    }

    private MainTabs() {}

    /** 下タブに出すキー (設定順、未知のキーは除外、最大 MAX_MAIN)。home が無ければ先頭に足す。 */
    @NonNull
    public static List<String> mainTabKeys(@NonNull PreferenceManager prefs) {
        return mainTabKeys(prefs.getMainTabs());
    }

    /**
     * 保存値 (カンマ区切り) から下タブのキーを決める。古い版や別の端末から来た未知のキーは捨てる。
     * home は必ず含むので、結果が空になることはない。
     */
    @NonNull
    public static List<String> mainTabKeys(@Nullable String storedMain) {
        List<String> keys = knownKeys(storedMain);
        if (!keys.contains(HOME)) keys.add(0, HOME);
        return keys.size() > MAX_MAIN ? new ArrayList<>(keys.subList(0, MAX_MAIN)) : keys;
    }

    /** 「その他」に並べるキー。タブに置ける画面のうち、下タブに無いものすべて。 */
    @NonNull
    public static List<String> moreTabKeys(@NonNull PreferenceManager prefs) {
        return moreTabKeys(mainTabKeys(prefs), prefs.getOtherTabs());
    }

    /**
     * TABS から main を除いたもの。並びは「タブの表示設定」の「その他」側の保存順で、そこに無いもの
     * (保存値が古い・壊れている) は TABS の順で後ろに足す。保存順は並びにしか使わないので、
     * other の保存値が欠けていても画面が「その他」から消えることはない。
     */
    @NonNull
    public static List<String> moreTabKeys(@NonNull List<String> mainKeys, @Nullable String storedOther) {
        List<String> keys = new ArrayList<>();
        for (String key : knownKeys(storedOther)) {
            if (!mainKeys.contains(key)) keys.add(key);
        }
        for (String key : TABS.keySet()) {
            if (!mainKeys.contains(key) && !keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    private static List<String> knownKeys(@Nullable String stored) {
        List<String> keys = new ArrayList<>();
        if (stored == null) return keys;
        for (String key : stored.split(",")) {
            String k = key.trim();
            if (TABS.containsKey(k) && !keys.contains(k)) keys.add(k);
        }
        return keys;
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

    public static int destinationId(@NonNull String key) { return TABS.get(key).destinationId; }

    public static int iconRes(@NonNull String key) { return TABS.get(key).iconRes; }

    @NonNull
    public static String label(@NonNull String key) { return TABS.get(key).label; }

    @NonNull
    public static String moreLabel(@NonNull String key) { return TABS.get(key).moreLabel; }
}
