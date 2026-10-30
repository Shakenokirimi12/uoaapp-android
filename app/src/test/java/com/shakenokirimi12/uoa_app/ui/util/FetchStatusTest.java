package com.shakenokirimi12.uoa_app.ui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.TimeZone;

public class FetchStatusTest {
    private static final TimeZone JST = TimeZone.getTimeZone("Asia/Tokyo");
    /** 2026-10-16 12:34 JST */
    private static final long FETCHED = 1792121640000L;

    @Test
    public void sameDay_showsTimeOnly() {
        assertEquals("12:34", FetchStatus.lastUpdatedLabel(FETCHED, FETCHED + 3 * 60 * 60_000L, JST));
    }

    @Test
    public void earlierDay_showsDate() {
        assertEquals("10/16 12:34", FetchStatus.lastUpdatedLabel(FETCHED, FETCHED + 24 * 60 * 60_000L, JST));
    }

    @Test
    public void neverFetched_saysSo() {
        assertNull(FetchStatus.lastUpdatedLabel(0, FETCHED, JST));
    }

    @Test
    public void emptyAndFresh_isARealEmptyList() {
        // キャッシュが新しくて取得を飛ばした (SyncWorker が空の一覧を保存した直後に画面を開いた) 場合。
        assertEquals(FetchStatus.Empty.NONE, FetchStatus.emptyKind(true, false, FETCHED));
        // 取得に失敗しても、一度取れた空の一覧はそのまま「ありません」(帯で最終更新を添える)。
        assertEquals(FetchStatus.Empty.NONE, FetchStatus.emptyKind(true, true, FETCHED));
    }

    @Test
    public void neverFetched_isLoadingOrUnavailable_neverNone() {
        assertEquals(FetchStatus.Empty.LOADING, FetchStatus.emptyKind(true, false, 0));
        assertEquals(FetchStatus.Empty.UNAVAILABLE, FetchStatus.emptyKind(true, true, 0));
    }

    @Test
    public void loggedOut_winsOverEverything() {
        assertEquals(FetchStatus.Empty.NOT_LOGGED_IN, FetchStatus.emptyKind(false, true, 0));
        assertEquals(FetchStatus.Empty.NOT_LOGGED_IN, FetchStatus.emptyKind(false, false, FETCHED));
    }
}
