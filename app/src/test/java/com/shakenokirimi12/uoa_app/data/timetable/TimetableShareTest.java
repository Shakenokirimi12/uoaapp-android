package com.shakenokirimi12.uoa_app.data.timetable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;

import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 時間割共有の形式・時間割の作り方・重なり。サーバー (uoa-app-push) と iOS と同じフィクスチャを読む。 */
public class TimetableShareTest {
    private static JsonObject fixture() {
        InputStream in = TimetableShareTest.class.getClassLoader().getResourceAsStream("timetable-share-v1.json");
        assertNotNull(in);
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static List<SharedClass> classes(JsonElement array) {
        List<SharedClass> list = new ArrayList<>();
        for (JsonElement e : array.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            list.add(new SharedClass(o.get("day").getAsInt(), o.get("start").getAsInt(), o.get("end").getAsInt(),
                    o.get("title").getAsString(), o.get("room").getAsString()));
        }
        return list;
    }

    @Test
    public void decodeKeepsOnlyTimetableFields_andEncodesTheSameShape() {
        JsonObject fx = fixture();
        SharedTimetable t = SharedTimetable.normalize(fx.get("payload"));
        assertNotNull(t);
        assertEquals(fx.get("normalized"), t.toJson());
        assertEquals(t, SharedTimetable.decode(t.encode()));

        JsonObject sanitize = fx.getAsJsonObject("sanitize");
        SharedTimetable cleaned = SharedTimetable.normalize(sanitize.get("payload"));
        assertNotNull(cleaned);
        assertEquals(sanitize.get("normalized"), cleaned.toJson());
    }

    @Test
    public void rejectsInvalidPayloads() {
        JsonArray invalid = fixture().getAsJsonArray("invalid");
        assertTrue(invalid.size() > 0);
        for (JsonElement e : invalid) {
            JsonObject item = e.getAsJsonObject();
            assertNull(item.get("why").getAsString(), SharedTimetable.normalize(item.get("payload")));
        }
    }

    @Test
    public void overlapMatchesFixture() {
        JsonObject fx = fixture();
        List<SharedClass> friend = SharedTimetable.normalize(fx.get("normalized")).classes;
        JsonObject overlap = fx.getAsJsonObject("overlap");
        List<SharedClass> mine = classes(overlap.get("mine"));
        JsonObject cells = overlap.getAsJsonObject("cells");

        List<Integer> expectedDays = new ArrayList<>();
        for (JsonElement d : overlap.getAsJsonArray("days")) expectedDays.add(d.getAsInt());
        assertEquals(expectedDays, TimetableGrid.days(friend, mine));
        List<int[]> rows = TimetableGrid.rows(friend, mine);
        List<Integer> rowStarts = new ArrayList<>();
        for (int[] r : rows) rowStarts.add(r[0]);
        List<Integer> expectedRows = new ArrayList<>();
        for (JsonElement r : overlap.getAsJsonArray("rows")) expectedRows.add(r.getAsInt());
        assertEquals(expectedRows, rowStarts);

        String other = overlap.get("otherCells").getAsString();
        for (int day : TimetableGrid.days(friend, mine)) {
            for (int[] r : rows) {
                String key = day + ":" + r[0];
                String expected = cells.has(key) ? cells.get(key).getAsString() : other;
                assertEquals(key, expected, TimetableGrid.overlap(friend, mine, day, r).key());
            }
        }
    }

    @Test
    public void freeSlotsAreMerged() {
        List<int[]> shown = java.util.Arrays.asList(new int[]{1, 2}, new int[]{3, 4}, new int[]{5, 6}, new int[]{7, 8});
        List<int[]> merged = TimetableGrid.freeRanges(
                java.util.Arrays.asList(new int[]{1, 2}, new int[]{3, 4}, new int[]{7, 8}), shown);
        assertEquals(2, merged.size());
        assertEquals(java.util.Arrays.asList(1, 4), java.util.Arrays.asList(merged.get(0)[0], merged.get(0)[1]));
        assertEquals(java.util.Arrays.asList(7, 8), java.util.Arrays.asList(merged.get(1)[0], merged.get(1)[1]));
        // 表の全部の行が空いているときは範囲ではなく null (画面では「終日」)。
        assertNull(TimetableGrid.freeRanges(shown, shown));
    }

    @Test
    public void weeklyTimetableFromCalendar() {
        JsonObject derive = fixture().getAsJsonObject("derive");
        List<CalendarEvent> events = new ArrayList<>();
        for (JsonElement e : derive.getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            events.add(new CalendarEvent(o.get("summary").getAsString(), o.get("location").getAsString(),
                    Date.from(OffsetDateTime.parse(o.get("start").getAsString()).toInstant()),
                    Date.from(OffsetDateTime.parse(o.get("end").getAsString()).toInstant())));
        }
        long now = OffsetDateTime.parse(derive.get("now").getAsString()).toInstant().toEpochMilli();
        assertEquals(classes(derive.get("expected")), TimetableDerivation.weeklyClasses(events, now));
    }

    /** 1 件で 2〜4 限にかかる予定は 2〜4 限、2 限だけの予定は 2 限だけ (iOS testMultiPeriodEventCoversEveryPeriod と同じ)。 */
    @Test
    public void multiPeriodEventCoversEveryPeriod() {
        List<CalendarEvent> events = new ArrayList<>();
        for (String day : new String[]{"2026-10-20", "2026-10-27"}) {
            events.add(new CalendarEvent("実験", "M1", date(day + "T09:50:00+09:00"), date(day + "T12:30:00+09:00")));
            events.add(new CalendarEvent("ゼミ", "M1", date(day + "T13:20:00+09:00"), date(day + "T14:10:00+09:00")));
            events.add(new CalendarEvent("ゼミ", "M2", date(day + "T14:10:00+09:00"), date(day + "T15:00:00+09:00")));
        }
        List<SharedClass> expected = new ArrayList<>();
        expected.add(new SharedClass(2, 2, 4, "実験", "M1"));
        expected.add(new SharedClass(2, 5, 5, "ゼミ", "M1"));
        expected.add(new SharedClass(2, 6, 6, "ゼミ", "M2"));
        long now = OffsetDateTime.parse("2026-10-21T12:00:00+09:00").toInstant().toEpochMilli();
        assertEquals(expected, TimetableDerivation.weeklyClasses(events, now));
    }

    /** ある週だけ途中の時限で教室が変わっても、授業は消えない (iOS testRoomChangeInOneWeekKeepsTheClass と同じ)。 */
    @Test
    public void roomChangeInOneWeekKeepsTheClass() {
        List<CalendarEvent> events = new ArrayList<>();
        events.add(new CalendarEvent("実験", "M1", date("2026-10-20T09:50:00+09:00"), date("2026-10-20T12:30:00+09:00")));
        events.add(new CalendarEvent("実験", "M1", date("2026-10-27T09:50:00+09:00"), date("2026-10-27T11:40:00+09:00")));
        events.add(new CalendarEvent("実験", "M2", date("2026-10-27T11:40:00+09:00"), date("2026-10-27T12:30:00+09:00")));
        List<SharedClass> expected = new ArrayList<>();
        expected.add(new SharedClass(2, 2, 4, "実験", "M1"));
        long now = OffsetDateTime.parse("2026-10-21T12:00:00+09:00").toInstant().toEpochMilli();
        assertEquals(expected, TimetableDerivation.weeklyClasses(events, now));
    }

    /** 同じ授業が続く時限は行をまたいで 1 箱。空きは行ごと (iOS testColumnSegments と同じ)。 */
    @Test
    public void columnSegments() {
        List<SharedClass> classes = new ArrayList<>();
        classes.add(new SharedClass(2, 2, 4, "実験", "M1"));
        classes.add(new SharedClass(2, 5, 5, "ゼミ", "M1"));
        classes.add(new SharedClass(2, 6, 6, "ゼミ", "M2"));
        classes.add(new SharedClass(1, 1, 2, "A", ""));
        List<SharedClass> none = new ArrayList<>();
        List<int[]> rows = TimetableGrid.rows(classes, none);
        assertEquals("1-1 2-4 5-5 6-6 7-8 ", ranges(TimetableGrid.segments(classes, 2, rows)));
        assertEquals("1-2 3-4 5-6 7-8 ", ranges(TimetableGrid.segments(classes, 1, rows)));
        List<SharedClass> late = new ArrayList<>();
        late.add(new SharedClass(3, 8, 8, "B", ""));
        late.add(new SharedClass(3, 11, 11, "B", ""));
        assertEquals("1-2 3-4 5-6 7-7 8-8 11-11 ", ranges(TimetableGrid.segments(late, 3, TimetableGrid.rows(late, none))));
    }

    private static String ranges(List<int[]> list) {
        StringBuilder b = new StringBuilder();
        for (int[] r : list) b.append(r[0]).append('-').append(r[1]).append(' ');
        return b.toString();
    }

    private static Date date(String iso) {
        return Date.from(OffsetDateTime.parse(iso).toInstant());
    }

    @Test
    public void shareLinks() {
        JsonObject links = fixture().getAsJsonObject("links");
        for (Map.Entry<String, JsonElement> e : links.getAsJsonObject("valid").entrySet()) {
            assertEquals(e.getKey(), e.getValue().getAsString(), TimetableShareLink.shareId(e.getKey()));
        }
        for (JsonElement e : links.getAsJsonArray("invalid")) {
            assertNull(e.getAsString(), TimetableShareLink.shareId(e.getAsString()));
        }
        String id = "0123456789abcdef0123456789abcdef";
        assertEquals(id, TimetableShareLink.shareId(TimetableShareLink.appUrl(id)));
        assertEquals(id, TimetableShareLink.shareId(TimetableShareLink.webUrl(id)));
    }

    @Test
    public void outgoingShareExpiry() {
        OutgoingShare share = new OutgoingShare("x", 1000, "", new ArrayList<>());
        assertFalse(share.isExpired(999));
        assertTrue(share.isExpired(1000));
    }

    /** 同じ名前で届いても上書きしない (返事には認証が無い)。付けた名前と授業はそのまま残る。 */
    @Test
    public void savingNeverOverwritesBySameName() {
        List<SharedClass> one = new ArrayList<>();
        one.add(new SharedClass(1, 1, 2, "A", ""));
        List<SharedClass> two = new ArrayList<>();
        two.add(new SharedClass(2, 3, 4, "B", ""));
        List<TimetableFriend> friends = new ArrayList<>();
        TimetableFriend first = TimetableShareStore.add(friends, SharedTimetable.create("太郎", 0, one), 1, "1");
        friends.set(friends.indexOf(first), new TimetableFriend(first.id, "たろう", first.sentName, first.receivedAt, first.timetable));
        TimetableShareStore.add(friends, SharedTimetable.create("太郎", 0, two), 2, "2");
        TimetableShareStore.add(friends, SharedTimetable.create("", 0, one), 3, "3");
        assertEquals(3, friends.size());
        TimetableFriend kept = null;
        for (TimetableFriend f : friends) if (f.id.equals("1")) kept = f;
        assertNotNull(kept);
        assertEquals("たろう", kept.name);
        assertEquals(one, kept.timetable.classes);
        assertEquals(TimetableShareStore.unnamed(), friends.get(0).name);
    }

    /** 名前か授業が変わったら、出している共有を使い回さない。作った日時は見ない。 */
    @Test
    public void contentKeyChangesWithNameAndClasses() {
        List<SharedClass> classes = new ArrayList<>();
        classes.add(new SharedClass(1, 1, 2, "A", "M1"));
        List<SharedClass> doubled = new ArrayList<>(classes);
        doubled.addAll(classes);
        String a = TimetableShareStore.contentKey(SharedTimetable.create("A1", 1_800_000_000_000L, classes));
        assertEquals(a, TimetableShareStore.contentKey(SharedTimetable.create("A1", 1_800_000_060_000L, classes)));
        assertFalse(a.equals(TimetableShareStore.contentKey(SharedTimetable.create("A2", 1_800_000_000_000L, classes))));
        assertFalse(a.equals(TimetableShareStore.contentKey(SharedTimetable.create("A1", 1_800_000_000_000L, doubled))));
    }

    @Test
    public void titlesMatchAcrossUnicodeSpaces() {
        assertEquals(TimetableGrid.comparableTitle("線形代数 II"), TimetableGrid.comparableTitle("線形代数\u3000II"));
        assertEquals("a b", TimetableGrid.comparableTitle(" A\u00A0 B "));
    }

    @Test
    public void sanitizeDropsLoneSurrogates() {
        assertEquals("ab", SharedTimetable.sanitize("a\uD800b", 10));
        assertEquals("a\uD83D\uDE00", SharedTimetable.sanitize("a\uD83D\uDE00", 10));
    }

    /** 「同じ授業」は時限ごとに比べる。範囲に同じ授業名があるだけでは「同じ」にしない (iOS と同じ)。 */
    @Test
    public void overlapComparesEachPeriod() {
        List<SharedClass> friend = Arrays.asList(new SharedClass(1, 2, 4, "A", ""),
                new SharedClass(2, 1, 1, "B", ""), new SharedClass(3, 1, 2, "C", ""));
        List<SharedClass> mine = Arrays.asList(new SharedClass(1, 2, 2, "A", ""),
                new SharedClass(2, 2, 2, "B", ""), new SharedClass(3, 1, 2, "c", ""));
        assertEquals(TimetableGrid.Overlap.BOTH_BUSY, TimetableGrid.overlap(friend, mine, 1, new int[]{2, 4}));
        assertEquals(TimetableGrid.Overlap.SAME, TimetableGrid.overlap(friend, mine, 1, new int[]{2, 2}));
        assertEquals(TimetableGrid.Overlap.BOTH_BUSY, TimetableGrid.overlap(friend, mine, 2, new int[]{1, 2}));
        assertEquals(TimetableGrid.Overlap.SAME, TimetableGrid.overlap(friend, mine, 3, new int[]{1, 2}));
        // 友達が同じ時限に A と B を持ち、自分は A だけなら「同じ」にしない。
        List<SharedClass> doubled = Arrays.asList(new SharedClass(4, 1, 1, "A", ""), new SharedClass(4, 1, 1, "B", ""));
        List<SharedClass> onlyA = Collections.singletonList(new SharedClass(4, 1, 1, "A", ""));
        assertEquals(TimetableGrid.Overlap.BOTH_BUSY, TimetableGrid.overlap(doubled, onlyA, 4, new int[]{1, 1}));
    }

    /** 比べるときは、友達が空いている時限を自分の授業でも区切り、友達の授業も重なりが変わるところで区切る (iOS と同じ)。 */
    @Test
    public void comparedSegmentsSplitFreePeriodsByMine() {
        List<SharedClass> friend = Collections.singletonList(new SharedClass(1, 3, 4, "A", ""));
        List<SharedClass> mine = Arrays.asList(new SharedClass(1, 1, 1, "X", ""), new SharedClass(1, 3, 3, "A", ""));
        List<int[]> rows = TimetableGrid.rows(friend, mine);
        assertEquals("1-1 2-2 3-3 4-4 5-6 7-8 ", ranges(TimetableGrid.segments(friend, mine, 1, rows)));
        assertEquals("1-2 3-4 5-6 7-8 ", ranges(TimetableGrid.segments(friend, 1, rows)));
    }

    /** 友達 2〜4 限・自分 1〜2 限は、2 限と 3〜4 限に分ける。自分の授業名が変わっても重なりが同じなら割らない。 */
    @Test
    public void comparedSegmentsSplitAFriendClassWhereTheOverlapChanges() {
        List<SharedClass> friend = Collections.singletonList(new SharedClass(2, 2, 4, "Lab", ""));
        List<SharedClass> mine = Collections.singletonList(new SharedClass(2, 1, 2, "ML", ""));
        List<int[]> rows = TimetableGrid.rows(friend, mine);
        assertEquals("1-1 2-2 3-4 5-6 7-8 ", ranges(TimetableGrid.segments(friend, mine, 2, rows)));
        assertEquals(TimetableGrid.Overlap.FRIEND_ONLY, TimetableGrid.overlap(friend, mine, 2, new int[]{3, 4}));
        List<SharedClass> mineTwo = Arrays.asList(new SharedClass(2, 2, 2, "X", ""), new SharedClass(2, 3, 4, "Y", ""));
        assertEquals("1-1 2-4 5-6 7-8 ", ranges(TimetableGrid.segments(friend, mineTwo, 2, rows)));
    }

    /** 表の上の数と下のまとめは、表と同じ箱で数える。片方だけに授業がある時限があれば「同じ」にしない (iOS と同じ)。 */
    @Test
    public void comparedBoxesCountLikeTheGrid() {
        List<SharedClass> friend = Arrays.asList(new SharedClass(1, 2, 2, "A", ""), new SharedClass(2, 1, 1, "S", ""));
        List<SharedClass> mine = Arrays.asList(new SharedClass(1, 2, 2, "B", ""),
                new SharedClass(2, 1, 1, "S", ""), new SharedClass(2, 2, 2, "X", ""));
        List<int[]> rows = TimetableGrid.rows(friend, mine);
        List<TimetableGrid.Box> monday = TimetableGrid.comparedBoxes(friend, mine, 1, rows);
        assertArrayEquals(new int[]{1, 1}, monday.get(0).range);
        assertEquals(TimetableGrid.Overlap.BOTH_FREE, monday.get(0).overlap);
        assertEquals(TimetableGrid.Overlap.BOTH_BUSY, TimetableGrid.overlap(friend, mine, 2, new int[]{1, 2}));
        List<TimetableGrid.Box> tuesday = TimetableGrid.comparedBoxes(friend, mine, 2, rows);
        assertArrayEquals(new int[]{1, 1}, tuesday.get(0).range);
        assertEquals(TimetableGrid.Overlap.SAME, tuesday.get(0).overlap);
        assertArrayEquals(new int[]{2, 2}, tuesday.get(1).range);
        assertEquals(TimetableGrid.Overlap.MINE_ONLY, tuesday.get(1).overlap);
    }
}
