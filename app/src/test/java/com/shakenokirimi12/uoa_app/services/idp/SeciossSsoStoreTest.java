package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/** Cookie ヘッダの分解だけを見る (永続化は PreferenceManager 側で Android 依存)。 */
public class SeciossSsoStoreTest {
    @Test
    public void parsesCookieHeader() {
        Map<String, String> cookies = SeciossSsoStore.parse("AWSALB=abc; SLINKSESSID=xyz");
        assertEquals(2, cookies.size());
        assertEquals("abc", cookies.get("AWSALB"));
        assertEquals("xyz", cookies.get("SLINKSESSID"));
    }

    @Test
    public void keepsEqualsSignsInsideValues() {
        // Base64 の値は "=" で終わる。最初の "=" だけで区切らないと値が壊れる。
        assertEquals("YWJj==", SeciossSsoStore.parse("s=YWJj==").get("s"));
    }

    @Test
    public void ignoresEmptyAndMalformedEntries() {
        assertTrue(SeciossSsoStore.parse("").isEmpty());
        assertTrue(SeciossSsoStore.parse(null).isEmpty());
        assertTrue(SeciossSsoStore.parse("novalue; =noname").isEmpty());
    }
}
