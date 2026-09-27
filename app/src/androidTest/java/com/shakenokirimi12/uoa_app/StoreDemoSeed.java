package com.shakenokirimi12.uoa_app;

import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.data.models.CreditSummary;
import com.shakenokirimi12.uoa_app.data.models.Grade;
import com.shakenokirimi12.uoa_app.data.models.MoodleCourse;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * ストア掲載用スクリーンショットのためのデモデータを入れる。授業名・課題・成績はすべて架空。
 *
 * 通常のテスト実行では何もしない (実行すると端末のログイン状態とキャッシュを上書きするため)。
 * 撮影するときだけ明示的に有効にする:
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e seed_store_demo true -e class com.shakenokirimi12.uoa_app.StoreDemoSeed \
 *       com.shakenokirimi12.uoa_app.test/androidx.test.runner.AndroidJUnitRunner
 * キャッシュは保存から 15 分間だけ「新しい」扱いなので、その間に撮る (過ぎると取り直しに行く)。
 */
@RunWith(AndroidJUnit4.class)
public class StoreDemoSeed {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final String[][] PERIODS = {
            { "09:00", "10:40" }, { "10:50", "12:30" }, { "13:20", "15:00" }, { "15:10", "16:50" }, { "17:00", "18:40" } };

    @Test
    public void seed() {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("seed_store_demo")));
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        PreferenceManager prefs = PreferenceManager.getInstance(ctx);
        prefs.setOnboardingDone(true);
        prefs.setHasSeenIdPTutorial(true);
        prefs.setUsername("s1300000");
        // 使われない値。キャッシュが新しい間は通信しない。
        prefs.setPassword("demo-password");

        DataCache cache = DataCache.getInstance(ctx);
        cache.saveEvents(timetable());
        cache.saveAssignments(assignments());
        cache.saveCourses(courses());
        cache.saveGrades(grades());
        cache.saveCreditSummary(creditSummary());

        // 設定もキャッシュも apply() で後から書かれる。書き終わる前に計測プロセスが終わると消えるので待つ。
        android.os.SystemClock.sleep(2000);
    }

    /** 今週と来週の月〜金。曜日ごとに同じ時間割。 */
    private static List<CalendarEvent> timetable() {
        Object[][] week = {
                // 曜日, 時限, 科目, 教室
                { DayOfWeek.MONDAY, 1, "線形代数II", "M5" },
                { DayOfWeek.MONDAY, 3, "英語コミュニケーションI", "S2" },
                { DayOfWeek.TUESDAY, 1, "コンピュータシステム概論", "std3" },
                { DayOfWeek.TUESDAY, 2, "微分積分II", "M7" },
                { DayOfWeek.TUESDAY, 4, "プログラミング入門", "std1" },
                { DayOfWeek.WEDNESDAY, 2, "離散数学", "M3" },
                { DayOfWeek.WEDNESDAY, 3, "物理学実験", "物理実験室" },
                { DayOfWeek.THURSDAY, 1, "線形代数II", "M5" },
                { DayOfWeek.THURSDAY, 4, "情報倫理", "講堂" },
                { DayOfWeek.FRIDAY, 2, "コンピュータシステム概論", "std3" },
                { DayOfWeek.FRIDAY, 3, "英語コミュニケーションI", "S2" },
        };
        LocalDate monday = LocalDate.now(JST).with(DayOfWeek.MONDAY);
        List<CalendarEvent> out = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            for (Object[] c : week) {
                LocalDate day = monday.plusWeeks(w).with((DayOfWeek) c[0]);
                String[] p = PERIODS[(int) c[1] - 1];
                out.add(new CalendarEvent((String) c[2], (String) c[3], at(day, p[0]), at(day, p[1])));
            }
        }
        return out;
    }

    private static List<Assignment> assignments() {
        long now = System.currentTimeMillis() / 1000;
        long h = 3600;
        return Arrays.asList(
                assignment(101, "第3回 演習課題 (行列の固有値)", "線形代数II", now + 5 * h),
                assignment(102, "レポート: 情報社会と倫理", "情報倫理", now + 2 * 24 * h),
                assignment(103, "Lab 4: 配列とループ", "プログラミング入門", now + 3 * 24 * h + 4 * h),
                assignment(104, "Weekly Quiz 5", "英語コミュニケーションI", now + 6 * 24 * h));
    }

    private static Assignment assignment(int id, String name, String course, long due) {
        Assignment a = new Assignment();
        a.setId(id);
        a.setName(name);
        a.setCourseName(course);
        a.setDueDate(due);
        return a;
    }

    private static List<MoodleCourse> courses() {
        String[] names = { "線形代数II", "微分積分II", "コンピュータシステム概論", "プログラミング入門",
                "離散数学", "物理学実験", "英語コミュニケーションI", "情報倫理" };
        List<MoodleCourse> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            MoodleCourse c = new MoodleCourse();
            c.setId(200 + i);
            c.setFullname(names[i]);
            c.setShortname(names[i]);
            c.setDisplayname(names[i]);
            c.setVisible(true);
            out.add(c);
        }
        return out;
    }

    private static List<Grade> grades() {
        Object[][] rows = {
                { "線形代数I", "A", "92" }, { "微分積分I", "A", "85" }, { "プログラミング基礎", "A", "96" },
                { "アカデミックスキル1", "B", "74" }, { "英語基礎", "B", "70" }, { "コンピュータリテラシー", "A", "88" },
                { "物理学I", "C", "58" }, { "社会学", "A", "84" },
        };
        List<Grade> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            Grade g = new Grade("11300" + i, (String) rows[i][0], (String) rows[i][1], "", (String) rows[i][2], "");
            g.setYear("2026");
            g.setSemester("1学期");
            out.add(g);
        }
        for (String name : new String[] { "線形代数II", "コンピュータシステム概論", "情報倫理" }) {
            Grade g = new Grade("", name, "履修中", "", "履修中", "");
            g.setYear("2026");
            g.setSemester("2学期");
            out.add(0, g);
        }
        return out;
    }

    private static CreditSummary creditSummary() {
        CreditSummary s = new CreditSummary();
        s.cumulativeGpa = 3.42;
        s.earnedCredits = 28.0;
        s.terms.add(new CreditSummary.TermGpa("2026", "１学期", 3.42));
        return s;
    }

    private static Date at(LocalDate day, String hm) {
        return Date.from(day.atTime(LocalTime.parse(hm)).atZone(JST).toInstant());
    }
}
