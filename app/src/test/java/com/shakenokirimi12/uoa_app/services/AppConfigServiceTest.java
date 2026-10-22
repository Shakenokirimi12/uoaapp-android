package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.shakenokirimi12.uoa_app.data.models.AppConfig;

import org.junit.Test;

public class AppConfigServiceTest {
    private final Gson gson = new Gson();

    @Test
    public void isUsable_onlyWhenSuccessIsTrue() {
        // エラー応答 ({"error": ...}) や {} を反映すると、強制更新の最低バージョンが消えて全面表示が外れる。
        assertFalse(AppConfigService.isUsable(null));
        assertFalse(AppConfigService.isUsable(gson.fromJson("{}", AppConfig.class)));
        assertFalse(AppConfigService.isUsable(gson.fromJson("{\"error\":\"boom\"}", AppConfig.class)));
        assertFalse(AppConfigService.isUsable(gson.fromJson("{\"success\":false,\"minRequiredVersion\":null}", AppConfig.class)));
        assertTrue(AppConfigService.isUsable(gson.fromJson(
                "{\"success\":true,\"minRequiredVersion\":\"1.0.1\",\"forceUpdateMessage\":null,"
                        + "\"forceUpdateURL\":\"https://play.google.com/store/apps/details?id=x\",\"flags\":{\"a\":\"true\"}}",
                AppConfig.class)));
    }

    @Test
    public void retryKeepsGoingAtTheLongestIntervalInsteadOfStopping() {
        // 以前は 3 回で打ち切り、そのプロセスでは二度と取り直さなかった。
        assertEquals(5_000L, AppConfigService.retryDelayMs(0));
        assertEquals(300_000L, AppConfigService.retryDelayMs(4));
        assertEquals(300_000L, AppConfigService.retryDelayMs(50));
    }
}
