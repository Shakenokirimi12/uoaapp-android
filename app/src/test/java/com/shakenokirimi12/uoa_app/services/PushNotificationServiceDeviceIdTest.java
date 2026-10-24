package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PushNotificationServiceDeviceIdTest {

    @Test
    public void usesTheIdStoredNowNotTheOneSeenFirst() {
        // ログアウト (prefs.clearAll) で ID が作り直されたあとも、古い ID で登録し続けないこと。
        String[] stored = {"before-logout"};
        PushNotificationService push = new PushNotificationService(() -> stored[0]);
        assertEquals("before-logout", push.getDeviceId());

        stored[0] = "after-logout";

        assertEquals("after-logout", push.getDeviceId());
    }
}
