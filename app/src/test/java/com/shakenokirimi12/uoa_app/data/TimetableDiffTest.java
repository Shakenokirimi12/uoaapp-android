package com.shakenokirimi12.uoa_app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class TimetableDiffTest {
    private static final long NOW = 1_790_000_000_000L;
    private static final long HOUR = 60 * 60_000L;
    private static final long DAY = 24 * HOUR;

    private static CalendarEvent ev(String title, String room, long startOffset) {
        return new CalendarEvent(title, room, new Date(NOW + startOffset), new Date(NOW + startOffset + 100 * 60_000L));
    }

    @Test
    public void noChange_isEmpty() {
        List<CalendarEvent> a = Arrays.asList(ev("線形代数", "M1", DAY), ev("英語", "S3", 2 * DAY));
        List<CalendarEvent> b = Arrays.asList(ev("線形代数", "M1", DAY), ev("英語", "S3", 2 * DAY));
        assertTrue(TimetableDiff.diff(a, b, NOW).isEmpty());
    }

    @Test
    public void firstFetchOrEmptyResult_isIgnored() {
        List<CalendarEvent> a = Collections.singletonList(ev("線形代数", "M1", DAY));
        assertTrue(TimetableDiff.diff(new ArrayList<>(), a, NOW).isEmpty());
        assertTrue(TimetableDiff.diff(null, a, NOW).isEmpty());
        assertTrue(TimetableDiff.diff(a, new ArrayList<>(), NOW).isEmpty());
    }

    @Test
    public void removedClass_isCancelled() {
        List<CalendarEvent> a = Arrays.asList(ev("線形代数", "M1", DAY), ev("英語", "S3", 2 * DAY));
        List<CalendarEvent> b = Collections.singletonList(ev("英語", "S3", 2 * DAY));
        List<TimetableDiff.Change> c = TimetableDiff.diff(a, b, NOW);
        assertEquals(1, c.size());
        assertEquals(TimetableDiff.Kind.CANCELLED, c.get(0).kind);
        assertEquals("線形代数", c.get(0).event.getSummary());
    }

    @Test
    public void titleRewrittenAsCancelled_isOneCancellation() {
        List<CalendarEvent> a = Arrays.asList(ev("線形代数", "M1", DAY), ev("英語", "S3", 2 * DAY));
        List<CalendarEvent> b = Arrays.asList(ev("【休講】線形代数", "M1", DAY), ev("英語", "S3", 2 * DAY));
        List<TimetableDiff.Change> c = TimetableDiff.diff(a, b, NOW);
        assertEquals(1, c.size());
        assertEquals(TimetableDiff.Kind.CANCELLED, c.get(0).kind);
        // 次の同期で同じ内容なら二度は知らせない
        assertTrue(TimetableDiff.diff(b, b, NOW).isEmpty());
    }

    @Test
    public void roomChange_isDetected() {
        List<CalendarEvent> a = Arrays.asList(ev("線形代数", "M1", DAY), ev("英語", "S3", 2 * DAY));
        List<CalendarEvent> b = Arrays.asList(ev("線形代数", "M5", DAY), ev("英語", "S3", 2 * DAY));
        List<TimetableDiff.Change> c = TimetableDiff.diff(a, b, NOW);
        assertEquals(1, c.size());
        assertEquals(TimetableDiff.Kind.ROOM_CHANGED, c.get(0).kind);
        assertEquals("M1", c.get(0).previousLocation);
        assertEquals("M5", c.get(0).event.getLocation());
    }

    @Test
    public void addedClass_isAdded() {
        List<CalendarEvent> a = Collections.singletonList(ev("英語", "S3", 2 * DAY));
        List<CalendarEvent> b = Arrays.asList(ev("英語", "S3", 2 * DAY), ev("線形代数 補講", "M1", 3 * DAY));
        List<TimetableDiff.Change> c = TimetableDiff.diff(a, b, NOW);
        assertEquals(1, c.size());
        assertEquals(TimetableDiff.Kind.ADDED, c.get(0).kind);
    }

    @Test
    public void pastAndFarFutureChanges_areIgnored() {
        List<CalendarEvent> a = Arrays.asList(ev("過去", "M1", -DAY), ev("英語", "S3", 2 * DAY), ev("来月", "M2", 30 * DAY));
        List<CalendarEvent> b = Collections.singletonList(ev("英語", "S3", 2 * DAY));
        assertTrue(TimetableDiff.diff(a, b, NOW).isEmpty());
    }

    @Test
    public void changes_areSortedByStart() {
        List<CalendarEvent> a = Arrays.asList(ev("A", "M1", 3 * DAY), ev("B", "M1", DAY), ev("C", "M1", 2 * DAY));
        List<CalendarEvent> b = Collections.singletonList(ev("C", "M1", 2 * DAY));
        List<TimetableDiff.Change> c = TimetableDiff.diff(a, b, NOW);
        assertEquals("B", c.get(0).event.getSummary());
        assertEquals("A", c.get(1).event.getSummary());
    }
}
