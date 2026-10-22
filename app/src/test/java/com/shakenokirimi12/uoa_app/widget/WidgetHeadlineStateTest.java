package com.shakenokirimi12.uoa_app.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WidgetHeadlineStateTest {
    private static final long NOW = 1_760_000_000_000L;
    private static final long HOUR = 60 * 60_000L;

    @Test
    public void neverFetchedIsNotNoClasses() {
        assertEquals(WidgetHeadlineState.NOT_FETCHED, WidgetHeadlineState.of(true, 0, NOW, 0));
    }

    @Test
    public void loggedOutWinsOverEverything() {
        assertEquals(WidgetHeadlineState.NOT_LOGGED_IN, WidgetHeadlineState.of(false, NOW - HOUR, NOW, 0));
    }

    @Test
    public void staleDataIsNotNoClasses() {
        assertEquals(WidgetHeadlineState.STALE, WidgetHeadlineState.of(true, NOW - 24 * HOUR, NOW, 0));
        assertEquals(WidgetHeadlineState.NONE, WidgetHeadlineState.of(true, NOW - 23 * HOUR, NOW, 0));
    }

    @Test
    public void classesDoneForTheDayAreNotNoClasses() {
        assertEquals(WidgetHeadlineState.FINISHED, WidgetHeadlineState.of(true, NOW - HOUR, NOW, 3));
    }
}
