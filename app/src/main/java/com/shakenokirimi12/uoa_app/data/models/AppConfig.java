package com.shakenokirimi12.uoa_app.data.models;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;

/** /api/app-config の応答。iOS の AppConfigResponse と同じ形。 */
public class AppConfig {
    public boolean success;
    @Nullable public String minRequiredVersion;
    @Nullable public String forceUpdateMessage;
    @Nullable public String forceUpdateURL;
    @Nullable public Map<String, String> flags;

    public Map<String, String> flagsOrEmpty() {
        return flags != null ? flags : Collections.emptyMap();
    }
}
