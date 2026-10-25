package com.shakenokirimi12.uoa_app.services.attendance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.ApplicationExitInfo;

import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.services.attendance.PresenceEvidence.Entry;
import com.shakenokirimi12.uoa_app.services.attendance.PresenceEvidence.Kind;
import com.shakenokirimi12.uoa_app.services.attendance.PresenceEvidence.Verdict;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 自動の欠席。監視が授業の間ずっと動いていたと記録から言えるときだけ欠席にし、言えなければ何も付けない。
 * iOS の AutoAbsenceTests と同じ表を見る。
 */
public class AutoAbsenceTest {
    private static final TimeZone TOKYO = TimeZone.getTimeZone("Asia/Tokyo");

    /** 2026-10-26 (月) の Asia/Tokyo の時刻。 */
    private static long at(int hour, int minute, int day) {
        Calendar c = Calendar.getInstance(TOKYO);
        c.clear();
        c.set(2026, Calendar.OCTOBER, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static long at(int hour, int minute) { return at(hour, minute, 26); }

    private static long at(int hour) { return at(hour, 0); }

    private static Entry e(Kind kind, long at) { return new Entry(kind, at, null, null); }

    private static Entry obs(Kind kind, long at, boolean crossing) { return new Entry(kind, at, crossing, null); }

    private static Entry gap(long since, long at) { return new Entry(Kind.GAP, at, null, since); }

    /** 10:30-12:00 の授業。家で 8:00 にアプリを開き (外)、13:00 にまた開いた (監視は動いていて、外にいた)。 */
    private static List<Entry> stayedHome() {
        return new ArrayList<>(Arrays.asList(e(Kind.MONITORING_OK, at(8)), obs(Kind.OUTSIDE, at(8), false),
                e(Kind.MONITORING_OK, at(13)), obs(Kind.OUTSIDE, at(13), false)));
    }

    private static List<Entry> with(List<Entry> base, Entry... more) {
        List<Entry> out = new ArrayList<>(base);
        out.addAll(Arrays.asList(more));
        return out;
    }

    private static Verdict verdict(List<Entry> log, long now) {
        return PresenceEvidence.verdict(at(10, 15), at(12), log, now, PresenceEvidence.DELIVERY_MARGIN_MS);
    }

    private static Verdict verdict(List<Entry> log) { return verdict(log, at(13)); }

    // ---- 授業 1 回分の判定 ----

    @Test public void absent_whenOutsideAndMonitoredForTheWholeClass() {
        assertEquals(Verdict.ABSENT, verdict(stayedHome()));
    }

    @Test public void notAbsent_whenEnteredDuringClass() {
        assertEquals(Verdict.NOT_ABSENT, verdict(with(stayedHome(), obs(Kind.INSIDE, at(11, 50), false))));
    }

    /** 終わる直前に着いて、通知が遅れて届いた。 */
    @Test public void notAbsent_whenEntryArrivesWithinTheDeliveryMargin() {
        assertEquals(Verdict.NOT_ABSENT, verdict(with(stayedHome(), obs(Kind.INSIDE, at(12, 10), false))));
    }

    @Test public void notAbsent_whenAlreadyOnCampusBeforeClass() {
        List<Entry> log = Arrays.asList(e(Kind.MONITORING_OK, at(8)), obs(Kind.INSIDE, at(9), false), e(Kind.MONITORING_OK, at(13)));
        assertEquals(Verdict.NOT_ABSENT, verdict(log));
    }

    @Test public void unknown_whenNoStateWasKnownBeforeClass() {
        assertEquals(Verdict.UNKNOWN, verdict(Arrays.asList(e(Kind.MONITORING_OK, at(8)), e(Kind.MONITORING_OK, at(13)))));
    }

    @Test public void unknown_whenMonitoringStartedLate() {
        List<Entry> log = Arrays.asList(obs(Kind.OUTSIDE, at(8), false), e(Kind.MONITORING_OK, at(10, 45)), e(Kind.MONITORING_OK, at(13)));
        assertEquals(Verdict.UNKNOWN, verdict(log));
    }

    @Test public void unknown_whenPermissionWasDowngradedDuringClass() {
        assertEquals(Verdict.UNKNOWN, verdict(with(stayedHome(), e(Kind.MONITORING_LOST, at(11)))));
    }

    @Test public void unknown_whenTheCheckAfterClassFoundMonitoringOff() {
        List<Entry> log = Arrays.asList(e(Kind.MONITORING_OK, at(8)), obs(Kind.OUTSIDE, at(8), false), e(Kind.MONITORING_LOST, at(13)));
        assertEquals(Verdict.UNKNOWN, verdict(log));
    }

    /** 前面に出た直後は、登録し直したときの状態の通知がまだ届いていない。届くまで決めない。 */
    @Test public void unknown_untilTheStateIsObservedAfterClass() {
        List<Entry> log = Arrays.asList(e(Kind.MONITORING_OK, at(8)), obs(Kind.OUTSIDE, at(8), false), e(Kind.MONITORING_OK, at(13)));
        assertEquals(Verdict.UNKNOWN, verdict(log));
    }

    @Test public void unknown_whenNothingWasCheckedAfterClass() {
        List<Entry> log = Arrays.asList(e(Kind.MONITORING_OK, at(8)), obs(Kind.OUTSIDE, at(8), false));
        assertEquals(Verdict.UNKNOWN, verdict(log));
    }

    /** 強制停止・再起動などで、監視が続いていたか分からない区間が授業にかかる。 */
    @Test public void unknown_whenAGapCoversTheClass() {
        assertEquals(Verdict.UNKNOWN, verdict(with(stayedHome(), gap(at(9), at(12, 30)))));
    }

    @Test public void gapBeforeTheLastOutsideStateDoesNotMatter() {
        List<Entry> log = new ArrayList<>(Collections.singletonList(gap(at(1), at(7, 50))));
        log.addAll(stayedHome());
        assertEquals(Verdict.ABSENT, verdict(log));
    }

    @Test public void pending_untilTheDeliveryMarginHasPassed() {
        assertEquals(Verdict.PENDING, verdict(stayedHome(), at(12, 5)));
    }

    /** 後で中にいると分かったが、授業の後に外にいた記録も通過の通知も無い。いつ入ったか分からない。 */
    @Test public void unknown_whenLaterInsideIsNotExplained() {
        List<Entry> log = Arrays.asList(e(Kind.MONITORING_OK, at(8)), obs(Kind.OUTSIDE, at(8), false),
                e(Kind.MONITORING_OK, at(13)), obs(Kind.INSIDE, at(13, 1), false));
        assertEquals(Verdict.UNKNOWN, verdict(log));
    }

    @Test public void absent_whenLaterInsideFollowsAnOutsideStateAfterClass() {
        List<Entry> log = Arrays.asList(e(Kind.MONITORING_OK, at(8)), obs(Kind.OUTSIDE, at(8), false),
                e(Kind.MONITORING_OK, at(13)), obs(Kind.OUTSIDE, at(12, 30), false), obs(Kind.INSIDE, at(14), false));
        assertEquals(Verdict.ABSENT, verdict(log, at(15)));
    }

    // ---- どの授業に付けるか ----

    private static final AutoAbsencePlanner.Course ALGORITHMS = new AutoAbsencePlanner.Course("42", "Algorithms", "ALG01");

    private static CalendarEvent lecture(String summary, long start, long end) {
        return new CalendarEvent(summary, "M1", new Date(start), new Date(end));
    }

    private static CalendarEvent lecture() { return lecture("Algorithms", at(10, 30), at(12)); }

    private static List<String> plan(List<CalendarEvent> events, List<AutoAbsencePlanner.Course> courses, long fetchedAt,
                                     Map<String, Set<String>> recorded, Set<String> marked, List<Entry> evidence, long now) {
        List<String> keys = new ArrayList<>();
        for (AutoAbsencePlanner.Mark m : AutoAbsencePlanner.plan(events, courses, fetchedAt, recorded, marked, evidence, now, TOKYO)) {
            keys.add(m.key());
        }
        return keys;
    }

    private static List<String> plan(List<CalendarEvent> events) {
        return plan(events, Collections.singletonList(ALGORITHMS), at(12, 30), new HashMap<>(), new HashSet<>(), stayedHome(), at(13));
    }

    @Test public void plan_marksTheClassTheUserProvablyMissed() {
        assertEquals(Collections.singletonList("42|2026-10-26"), plan(Collections.singletonList(lecture())));
    }

    @Test public void plan_keepsAnExistingRecord() {
        Map<String, Set<String>> recorded = new HashMap<>();
        recorded.put("42", new HashSet<>(Collections.singletonList("2026-10-26")));
        assertTrue(plan(Collections.singletonList(lecture()), Collections.singletonList(ALGORITHMS), at(12, 30),
                recorded, new HashSet<>(), stayedHome(), at(13)).isEmpty());
    }

    /** 一度付けた分は、消されても付け直さない (何度走っても同じ)。 */
    @Test public void plan_isIdempotent() {
        assertTrue(plan(Collections.singletonList(lecture()), Collections.singletonList(ALGORITHMS), at(12, 30),
                new HashMap<>(), new HashSet<>(Collections.singletonList("42|2026-10-26")), stayedHome(), at(13)).isEmpty());
    }

    @Test public void plan_skipsCancelledAndOnlineClasses() {
        assertTrue(plan(Collections.singletonList(lecture("Algorithms 休講", at(10, 30), at(12)))).isEmpty());
        CalendarEvent cancelled = lecture();
        cancelled.setStatus("CANCELLED");
        assertTrue(plan(Collections.singletonList(cancelled)).isEmpty());
        CalendarEvent online = lecture();
        online.setLocation("オンライン");
        assertTrue(plan(Collections.singletonList(online)).isEmpty());
    }

    /** 授業の後に時間割を取っていない (授業の前に取ったものしか無い・一度も取れていない)。後から出た休講を知らない。 */
    @Test public void plan_skipsAClassWhoseTimetableWasNotFetchedAfterIt() {
        List<CalendarEvent> events = Collections.singletonList(lecture());
        assertTrue(plan(events, Collections.singletonList(ALGORITHMS), at(20, 0, 25),
                new HashMap<>(), new HashSet<>(), stayedHome(), at(13)).isEmpty());
        assertTrue(plan(events, Collections.singletonList(ALGORITHMS), at(11),
                new HashMap<>(), new HashSet<>(), stayedHome(), at(13)).isEmpty());
        assertTrue(plan(events, Collections.singletonList(ALGORITHMS), 0L,
                new HashMap<>(), new HashSet<>(), stayedHome(), at(13)).isEmpty());
    }

    @Test public void plan_skipsWhenTheCourseIsAmbiguousOrUnnamed() {
        List<CalendarEvent> events = Collections.singletonList(lecture());
        AutoAbsencePlanner.Course other = new AutoAbsencePlanner.Course("43", "Algorithms II", "ALG02");
        assertTrue(plan(events, Arrays.asList(ALGORITHMS, other), at(12, 30),
                new HashMap<>(), new HashSet<>(), stayedHome(), at(13)).isEmpty());
        assertTrue(plan(events, Collections.singletonList(new AutoAbsencePlanner.Course("1", "", "")), at(12, 30),
                new HashMap<>(), new HashSet<>(), stayedHome(), at(13)).isEmpty());
    }

    /** 同じ日に同じ科目が 2 コマあり、後のコマには来た。 */
    @Test public void plan_needsEveryClassOfTheDayToBeMissed() {
        List<CalendarEvent> events = Arrays.asList(lecture(), lecture("Algorithms", at(13, 10), at(14, 40)));
        List<Entry> evidence = with(stayedHome(), obs(Kind.OUTSIDE, at(12, 30), false),
                obs(Kind.INSIDE, at(13, 5), false), e(Kind.MONITORING_OK, at(15)));
        assertTrue(plan(events, Collections.singletonList(ALGORITHMS), at(15),
                new HashMap<>(), new HashSet<>(), evidence, at(15)).isEmpty());
    }

    // ---- 起動の扱い ----

    @Test public void gapSince_isNullForAHarmlessExitOrTheFirstLaunch() {
        List<long[]> lowMemory = Collections.singletonList(new long[] {at(10), ApplicationExitInfo.REASON_LOW_MEMORY});
        assertNull(PresenceEvidenceLog.gapSince(at(9), at(1), lowMemory, 0L));
        assertNull(PresenceEvidenceLog.gapSince(0L, at(1), Collections.emptyList(), 0L));
    }

    /** 強制停止 (最近のアプリから消したときも同じ理由) の後は、停止した時刻から分からない区間にする。 */
    @Test public void gapSince_startsAtAForceStop() {
        List<long[]> exits = Arrays.asList(
                new long[] {at(10), ApplicationExitInfo.REASON_USER_REQUESTED},
                new long[] {at(7), ApplicationExitInfo.REASON_USER_REQUESTED});
        assertEquals(Long.valueOf(at(10)), PresenceEvidenceLog.gapSince(at(9), at(1), exits, at(8)));
    }

    /** 再起動するとジオフェンスが消える。止まった時刻は分からないので、前に生きていた時刻から。 */
    @Test public void gapSince_startsAtTheLastAliveTimeAfterAReboot() {
        assertEquals(Long.valueOf(at(9)), PresenceEvidenceLog.gapSince(at(9), at(11), Collections.emptyList(), 0L));
    }
}
