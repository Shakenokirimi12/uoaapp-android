package com.shakenokirimi12.uoa_app.ui.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.TimeZone;

public class FetchStatusTest {
    private static final TimeZone JST = TimeZone.getTimeZone("Asia/Tokyo");
    /** 2026-10-16 12:34 JST */
    private static final long FETCHED = 1792121640000L;

    @Test
    public void sameDay_showsTimeOnly() {
        assertEquals("取得できませんでした（最終更新 12:34）",
                FetchStatus.failureText(FETCHED, FETCHED + 3 * 60 * 60_000L, JST));
    }

    @Test
    public void earlierDay_showsDate() {
        assertEquals("取得できませんでした（最終更新 10/16 12:34）",
                FetchStatus.failureText(FETCHED, FETCHED + 24 * 60 * 60_000L, JST));
    }

    @Test
    public void neverFetched_saysSo() {
        assertEquals("取得できませんでした（まだ一度も取得できていません）", FetchStatus.failureText(0, FETCHED, JST));
    }
}
