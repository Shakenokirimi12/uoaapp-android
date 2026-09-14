package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class MarkerListTest {
    private static final String[] DEFAULTS = {"ログアウト", "Logout"};

    @Test
    public void parse_fallsBackToDefaultsWhenEmpty() {
        assertEquals(Arrays.asList(DEFAULTS), MarkerList.parse(null, DEFAULTS));
        assertEquals(Arrays.asList(DEFAULTS), MarkerList.parse(" , ", DEFAULTS));
        assertEquals(Arrays.asList("a", "b"), MarkerList.parse(" a ,b,", DEFAULTS));
    }

    @Test
    public void containsAny_caseInsensitive() {
        List<String> markers = MarkerList.parse(null, DEFAULTS);
        assertTrue(MarkerList.containsAnyIgnoreCase("<a href=\"/x\">LOGOUT</a>", markers));
        assertFalse(MarkerList.containsAnyIgnoreCase("<a href=\"/x\">Login</a>", markers));
    }
}
