package com.shakenokirimi12.uoa_app.services.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.shakenokirimi12.uoa_app.services.AuthErrors;

import org.junit.Test;

public class LoginRequiredNoticeTest {

    @Test
    public void atMostOncePerTokyoDay() {
        // 2026-10-16 23:30 JST と 2026-10-17 00:10 JST は別の日 (UTC ではどちらも 10/16)。
        String late = LoginRequiredNotice.dayKey(1792161000000L);
        String nextMorning = LoginRequiredNotice.dayKey(1792163400000L);
        assertEquals("2026-10-16", late);
        assertEquals("2026-10-17", nextMorning);

        assertTrue(LoginRequiredNotice.shouldPost("", late));
        assertFalse(LoginRequiredNotice.shouldPost(late, late));
        assertTrue(LoginRequiredNotice.shouldPost(late, nextMorning));
    }

    @Test
    public void interactiveLoginRequired_isRecognizedFromBothServices() {
        // CampusSquare は例外の文言そのまま、Moodle は「接続エラー: 」を前に付けて渡してくる。
        assertTrue(AuthErrors.isInteractiveLoginRequired("INTERACTIVE_LOGIN_REQUIRED"));
        assertTrue(AuthErrors.isInteractiveLoginRequired("接続エラー: INTERACTIVE_LOGIN_REQUIRED"));
        assertFalse(AuthErrors.isInteractiveLoginRequired("接続エラー: timeout"));
        assertFalse(AuthErrors.isInteractiveLoginRequired(null));
    }
}
