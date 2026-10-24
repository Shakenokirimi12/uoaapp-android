package com.shakenokirimi12.uoa_app.data.timetable;

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
        assertEquals("1〜4限、7〜8限", TimetableGrid.freeSummary(
                java.util.Arrays.asList(new int[]{1, 2}, new int[]{3, 4}, new int[]{7, 8}), shown));
        assertEquals("終日", TimetableGrid.freeSummary(shown, shown));
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
        assertEquals(TimetableShareStore.UNNAMED, friends.get(0).name);
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
}
