package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    private static final String PREF_NAME = "uoa_app_prefs";

    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_NOTIFY_ASSIGNMENTS = "notify_assignments";
    private static final String KEY_NOTIFY_GRADES = "notify_grades";
    private static final String KEY_NOTIFY_LUNCH = "notify_lunch";
    private static final String KEY_MOODLE_TOKEN = "moodle_token";
    private static final String KEY_ICS_URL = "ics_url";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_AUTO_ATTENDANCE = "auto_attendance_enabled";

    private static PreferenceManager instance;
    private final SharedPreferences prefs;

    private PreferenceManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized PreferenceManager getInstance(Context context) {
        if (instance == null) {
            instance = new PreferenceManager(context);
        }
        return instance;
    }

    // Credentials
    public String getUsername() { return prefs.getString(KEY_USERNAME, ""); }
    public void setUsername(String username) { prefs.edit().putString(KEY_USERNAME, username).apply(); }

    public String getPassword() { return prefs.getString(KEY_PASSWORD, ""); }
    public void setPassword(String password) { prefs.edit().putString(KEY_PASSWORD, password).apply(); }

    public boolean hasCredentials() {
        return !getUsername().isEmpty() && !getPassword().isEmpty();
    }

    // Onboarding
    public boolean isOnboardingDone() { return prefs.getBoolean(KEY_ONBOARDING_DONE, false); }
    public void setOnboardingDone(boolean done) { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply(); }

    // Notifications
    public boolean isAssignmentNotifyEnabled() { return prefs.getBoolean(KEY_NOTIFY_ASSIGNMENTS, true); }
    public void setAssignmentNotifyEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_NOTIFY_ASSIGNMENTS, enabled).apply(); }

    public boolean isGradeNotifyEnabled() { return prefs.getBoolean(KEY_NOTIFY_GRADES, true); }
    public void setGradeNotifyEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_NOTIFY_GRADES, enabled).apply(); }

    public boolean isLunchNotifyEnabled() { return prefs.getBoolean(KEY_NOTIFY_LUNCH, false); }
    public void setLunchNotifyEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_NOTIFY_LUNCH, enabled).apply(); }

    // Moodle
    public String getMoodleToken() { return prefs.getString(KEY_MOODLE_TOKEN, ""); }
    public void setMoodleToken(String token) { prefs.edit().putString(KEY_MOODLE_TOKEN, token).apply(); }

    // ICS
    public String getIcsUrl() { return prefs.getString(KEY_ICS_URL, ""); }
    public void setIcsUrl(String url) { prefs.edit().putString(KEY_ICS_URL, url).apply(); }

    // Sync
    public long getLastSync() { return prefs.getLong(KEY_LAST_SYNC, 0); }
    public void setLastSync(long timestamp) { prefs.edit().putLong(KEY_LAST_SYNC, timestamp).apply(); }

    // Device ID for push notifications
    public String getDeviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    // Auto attendance
    public boolean isAutoAttendanceEnabled() { return prefs.getBoolean(KEY_AUTO_ATTENDANCE, false); }
    public void setAutoAttendanceEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_AUTO_ATTENDANCE, enabled).apply(); }

    // Reset
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
