package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppConfigServiceTest {

    @Test
    public void compareVersions_numericNotLexical() {
        // 文字列比較だと "1.10" < "1.9" になる。強制更新の判定に使うので数値で比べる。
        assertTrue(AppConfigService.compareVersions("1.10", "1.9") > 0);
        assertTrue(AppConfigService.compareVersions("1.9", "1.10") < 0);
    }

    @Test
    public void compareVersions_missingSegmentsAreZero() {
        assertEquals(0, AppConfigService.compareVersions("3.2", "3.2.0"));
        assertTrue(AppConfigService.compareVersions("3.2", "3.2.1") < 0);
    }

    @Test
    public void compareVersions_toleratesSuffix() {
        // versionName に "-debug" のような接尾辞が付いていても落ちない
        assertEquals(0, AppConfigService.compareVersions("3.2-debug", "3.2"));
    }
}
