package com.shakenokirimi12.uoa_app.services.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 利用状況: 送るのは期と区分だけ、送る日は終わった日の 7 日分だけ。 */
public class UsageStatsLogicTest {

    @Test
    public void cohort_takesOnlyTheTwoDigitsAfterThePrefix() {
        UsageCohort c = UsageCohort.parse("s1270123");
        assertEquals("s1", c.category);
        assertEquals(27, c.cohort);
        assertEquals(5, UsageCohort.parse("s1050123").cohort);
        assertEquals("m5", UsageCohort.parse("m5281234").category);
        assertEquals(28, UsageCohort.parse("m5281234").cohort);
        assertEquals("d8", UsageCohort.parse("d8261234").category);
        // 前後の空白と大文字は入力の揺れとして許す。
        assertEquals(27, UsageCohort.parse("  S1270123 ").cohort);
    }

    @Test
    public void cohort_unknownFormatsAreNull() {
        for (String s : Arrays.asList(null, "", "s127012", "s12701234", "s2270123", "a1270123", "s1x70123",
                "s1270123@u-aizu.ac.jp", "teacher", "1270123")) {
            assertNull(String.valueOf(s), UsageCohort.parse(s));
        }
    }

    @Test
    public void ledger_sendsOnlyFinishedDaysWithinSeven_andDropsOlder() {
        LocalDate today = LocalDate.of(2026, 10, 24);
        UsageLedger ledger = new UsageLedger();
        for (int back = 0; back <= 9; back++) ledger.increment(today.minusDays(back), UsageFeature.HOME);
        // 時計が先に進んでいた日 (明日・明後日)。
        ledger.increment(today.plusDays(1), UsageFeature.HOME);
        ledger.increment(today.plusDays(2), UsageFeature.HOME);

        assertTrue(ledger.dropExpired(today));
        List<String> days = ledger.sendableDays(today);
        assertEquals(Arrays.asList("2026-10-17", "2026-10-18", "2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23"), days);
        // 今日と明日は残るが送らない。8 日前より古い日と明後日は捨てた。
        assertEquals(1, (int) ledger.counts("2026-10-24").get(UsageFeature.HOME));
        assertEquals(1, (int) ledger.counts("2026-10-25").get(UsageFeature.HOME));
        assertTrue(ledger.counts("2026-10-16").isEmpty());
        assertTrue(ledger.counts("2026-10-26").isEmpty());
        assertFalse(ledger.dropExpired(today));

        ledger.remove(days);
        assertTrue(ledger.sendableDays(today).isEmpty());
        assertFalse(ledger.isEmpty());
    }

    @Test
    public void ledger_capsCounts_andIgnoresUnknownFeatures() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        UsageLedger ledger = new UsageLedger();
        for (int i = 0; i < UsageLedger.MAX_COUNT + 50; i++) ledger.increment(day, UsageFeature.GRADES);
        ledger.increment(day, "timetable_contents");
        assertEquals(Collections.singletonMap(UsageFeature.GRADES, UsageLedger.MAX_COUNT), ledger.counts("2026-10-23"));
    }

    @Test
    public void ledger_roundTripsThroughJson_andDropsGarbage() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        UsageLedger ledger = new UsageLedger();
        ledger.increment(day, UsageFeature.HOME);
        ledger.increment(day, UsageFeature.HOME);
        UsageLedger back = UsageLedger.fromJson(ledger.toJson());
        assertEquals(2, (int) back.counts("2026-10-23").get(UsageFeature.HOME));

        UsageLedger garbage = UsageLedger.fromJson("{\"not-a-day\":{\"home\":3},\"2026-10-22\":{\"home\":-1,\"evil\":5,\"grades\":99999}}");
        assertEquals(Collections.singletonMap(UsageFeature.GRADES, UsageLedger.MAX_COUNT), garbage.counts("2026-10-22"));
        assertTrue(garbage.counts("not-a-day").isEmpty());
        assertTrue(UsageLedger.fromJson("not json").isEmpty());
    }

    @Test
    public void body_carriesOnlyTheContractFields() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        UsageLedger ledger = new UsageLedger();
        ledger.increment(day, UsageFeature.HOME);
        String id = "0123456789abcdef0123456789abcdef";
        JsonObject body = ledger.buildBody(id, "1.0.0", UsageCohort.parse("s1270123"), Collections.singletonList("2026-10-23"));
        assertEquals(Arrays.asList("installId", "os", "appVersion", "cohort", "category", "days"), Arrays.asList(body.keySet().toArray(new String[0])));
        assertEquals(27, body.get("cohort").getAsInt());
        assertEquals("s1", body.get("category").getAsString());
        assertEquals("android", body.get("os").getAsString());
        assertEquals("{\"day\":\"2026-10-23\",\"counts\":{\"home\":1}}", body.getAsJsonArray("days").get(0).toString());
        // 学籍番号の残りの桁はどこにも入らない。
        assertFalse(body.toString().contains("s1270123"));

        JsonObject unknown = ledger.buildBody(id, "1.0.0", null, Collections.singletonList("2026-10-23"));
        assertTrue(unknown.get("cohort").isJsonNull());
        assertTrue(unknown.get("category").isJsonNull());
    }

    @Test
    public void response_dropsSentDaysOnlyWhenRetryingCannotHelp() {
        assertTrue(UsageStats.isFinal(200));
        assertTrue(UsageStats.isFinal(204));
        assertTrue(UsageStats.isFinal(400));
        assertTrue(UsageStats.isFinal(413));
        assertFalse(UsageStats.isFinal(429));
        assertFalse(UsageStats.isFinal(500));
        assertFalse(UsageStats.isFinal(503));
        assertFalse(UsageStats.isFinal(404));
    }
}
