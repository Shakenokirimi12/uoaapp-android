package com.shakenokirimi12.uoa_app.services.calendar;

import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;
import android.provider.CalendarContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

/** 端末の CalendarProvider に時間割が書けて、オプトアウトで消えることを確かめる。 */
@RunWith(AndroidJUnit4.class)
public class DeviceCalendarSyncInstrumentedTest {
    private Context ctx;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pkg = ctx.getPackageName();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(pkg, Manifest.permission.READ_CALENDAR);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(pkg, Manifest.permission.WRITE_CALENDAR);
    }

    @After
    public void tearDown() {
        PreferenceManager.getInstance(ctx).setDeviceCalendarSyncEnabled(false);
        DeviceCalendarSync.removeAsync(ctx);
        waitForEventCount(0);
    }

    @Test
    public void writesEvents_replacesOnResync_andRemovesOnOptOut() {
        PreferenceManager.getInstance(ctx).setDeviceCalendarSyncEnabled(true);
        long t = System.currentTimeMillis() + 86_400_000L;
        CalendarEvent a = new CalendarEvent("線形代数", "M1", new Date(t), new Date(t + 6_000_000L));
        CalendarEvent b = new CalendarEvent("英語", "S3", new Date(t + 7_200_000L), new Date(t + 13_200_000L));

        DeviceCalendarSync.syncAsync(ctx, Arrays.asList(a, b));
        assertEquals(2, waitForEventCount(2));

        // 取り直しで 1 件に減ったら、端末側も 1 件になる (追記ではなく置き換え)
        DeviceCalendarSync.syncAsync(ctx, Collections.singletonList(b));
        assertEquals(1, waitForEventCount(1));

        DeviceCalendarSync.removeAsync(ctx);
        assertEquals(0, waitForEventCount(0));
    }

    @Test
    public void doesNothingUntilOptedIn() {
        PreferenceManager.getInstance(ctx).setDeviceCalendarSyncEnabled(false);
        long t = System.currentTimeMillis() + 86_400_000L;
        DeviceCalendarSync.syncAsync(ctx, Collections.singletonList(
                new CalendarEvent("線形代数", "M1", new Date(t), new Date(t + 6_000_000L))));
        SystemClock.sleep(1500);
        assertEquals(0, countEvents());
    }

    private int waitForEventCount(int expected) {
        int n = -1;
        for (int i = 0; i < 50; i++) {
            n = countEvents();
            if (n == expected) return n;
            SystemClock.sleep(100);
        }
        return n;
    }

    private int countEvents() {
        try (Cursor c = ctx.getContentResolver().query(CalendarContract.Events.CONTENT_URI,
                new String[] { CalendarContract.Events._ID },
                CalendarContract.Events.ACCOUNT_NAME + "=? AND " + CalendarContract.Events.ACCOUNT_TYPE + "=? AND "
                        + CalendarContract.Events.DELETED + "=0",
                new String[] { "UoA非公式アプリ", DeviceCalendarSync.ACCOUNT_TYPE }, null)) {
            return c == null ? 0 : c.getCount();
        }
    }
}
