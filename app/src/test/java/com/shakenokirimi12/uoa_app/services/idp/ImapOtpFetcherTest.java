package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ImapOtpFetcherTest {

    @Test
    public void extractCode_standaloneDigitsLineOnly() {
        String body = "Your one-time password is below.\r\n\r\n  12345678  \r\n\r\nValid for 5 minutes (2026-09-14).";
        assertEquals("12345678", ImapOtpFetcher.extractCode(body));
    }

    @Test
    public void extractCode_ignoresInlineNumbersAndShortCodes() {
        assertNull(ImapOtpFetcher.extractCode("code: 123456 inline"));
        assertNull(ImapOtpFetcher.extractCode("12345\r\n"));
    }

    @Test
    public void lastUid_picksLargest() {
        assertEquals("56", ImapOtpFetcher.lastUid(Arrays.asList("* OK ready", "* SEARCH 12 56 34")));
        assertNull(ImapOtpFetcher.lastUid(Collections.singletonList("* SEARCH")));
        assertNull(ImapOtpFetcher.lastUid(Collections.emptyList()));
    }

    @Test
    public void quote_escapesBackslashAndQuote() {
        assertEquals("\"a\\\\b\\\"c\"", ImapOtpFetcher.quote("a\\b\"c"));
    }

    @Test
    public void parsesInternalDate() {
        Long at = ImapOtpFetcher.parseInternalDate(java.util.Arrays.asList(
                "* 5 FETCH (UID 123 INTERNALDATE \"15-Sep-2026 14:23:45 +0900\")"));
        assertNotNull(at);
        // 2026-09-15 14:23:45 +0900 = 2026-09-15 05:23:45 UTC
        assertEquals(1789449825000L, (long) at);
    }

    @Test
    public void returnsNullForMissingOrMalformedInternalDate() {
        assertNull(ImapOtpFetcher.parseInternalDate(java.util.Arrays.asList("* 5 FETCH (UID 123)")));
        assertNull(ImapOtpFetcher.parseInternalDate(java.util.Arrays.asList(
                "* 5 FETCH (INTERNALDATE \"not a date\")")));
    }
}
