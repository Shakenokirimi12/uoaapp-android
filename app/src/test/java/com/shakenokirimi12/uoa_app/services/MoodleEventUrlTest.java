package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MoodleEventUrlTest {
    private static final String MODULE = "https://elms.u-aizu.ac.jp/mod/assign/view.php?id=4321";
    private static final String DAY = "https://elms.u-aizu.ac.jp/calendar/view.php?view=day&time=1760000000#event_98765";

    @Test
    public void prefersTheActivityUrlOverTheCalendarDay() {
        assertEquals(MODULE, MoodleEventUrl.pick(MODULE, DAY));
    }

    @Test
    public void fallsBackToTheCalendarDayWhenTheActivityUrlIsMissing() {
        assertEquals(DAY, MoodleEventUrl.pick(null, DAY));
        assertEquals(DAY, MoodleEventUrl.pick("", DAY));
    }

    @Test
    public void rejectsNonHttpValues() {
        assertNull(MoodleEventUrl.pick("javascript:alert(1)", null));
        assertNull(MoodleEventUrl.pick(null, null));
    }
}
