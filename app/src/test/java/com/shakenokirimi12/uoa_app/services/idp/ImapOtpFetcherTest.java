package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
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
}
