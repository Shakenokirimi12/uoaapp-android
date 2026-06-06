package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.io.IOException;
import java.security.GeneralSecurityException;

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
    private static final String KEY_SYNC_INTERVAL = "sync_interval";
    private static final String KEY_SELECTED_NOTIFY_TIMES = "selected_notify_times";
    private static final String KEY_CUSTOM_NOTIFY_TIMES = "custom_notify_times";
    private static final String KEY_BG_NOTIFY_CHANGE = "bg_notify_change";
    private static final String KEY_BG_NOTIFY_SUCCESS = "bg_notify_success";
    private static final String KEY_BG_NOTIFY_FAILURE = "bg_notify_failure";
    private static final String KEY_BG_NOTIFY_NOCHANGE = "bg_notify_nochange";
    private static final String KEY_LUNCH_NOTIFY_TIME = "lunch_notify_time";
    private static final String KEY_DEBUG_MODE = "debug_mode";
    private static final String KEY_MAIN_TABS = "main_tabs";
    private static final String KEY_OTHER_TABS = "other_tabs";
    private static final String KEY_REVIEW_CONSENT = "review_consent_given";
    private static final String KEY_REVIEW_USER_ID = "review_user_id";

    private static PreferenceManager instance;
    private final SharedPreferences prefs;
    private SharedPreferences encryptedPrefs;

    private PreferenceManager(Context context) {
        Context appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            encryptedPrefs = EncryptedSharedPreferences.create(
                    "uoa_app_secure_prefs",
                    masterKeyAlias,
                    appContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            encryptedPrefs = prefs; // Fallback
        }

        migrateLegacyCredentials();
    }

    private void migrateLegacyCredentials() {
        if (prefs.contains(KEY_PASSWORD)) {
            String legacyUsername = prefs.getString(KEY_USERNAME, "");
            String legacyPassword = prefs.getString(KEY_PASSWORD, "");
            String legacyMoodleToken = prefs.getString(KEY_MOODLE_TOKEN, "");

            SharedPreferences.Editor secEdit = encryptedPrefs.edit();
            if (!legacyUsername.isEmpty()) {
                secEdit.putString(KEY_USERNAME, legacyUsername);
            }
            if (!legacyPassword.isEmpty()) {
                secEdit.putString(KEY_PASSWORD, legacyPassword);
            }
            if (!legacyMoodleToken.isEmpty()) {
                secEdit.putString(KEY_MOODLE_TOKEN, legacyMoodleToken);
            }
            secEdit.apply();

            // Delete plain text legacy credentials
            prefs.edit()
                    .remove(KEY_USERNAME)
                    .remove(KEY_PASSWORD)
                    .remove(KEY_MOODLE_TOKEN)
                    .apply();
        }
    }

    public static synchronized PreferenceManager getInstance(Context context) {
        if (instance == null) {
            instance = new PreferenceManager(context);
        }
        return instance;
    }

    // Credentials
    public String getUsername() { return encryptedPrefs.getString(KEY_USERNAME, ""); }
    public void setUsername(String username) { encryptedPrefs.edit().putString(KEY_USERNAME, username).apply(); }

    public String getPassword() { return encryptedPrefs.getString(KEY_PASSWORD, ""); }
    public void setPassword(String password) { encryptedPrefs.edit().putString(KEY_PASSWORD, password).apply(); }

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
    public String getMoodleToken() { return encryptedPrefs.getString(KEY_MOODLE_TOKEN, ""); }
    public void setMoodleToken(String token) { encryptedPrefs.edit().putString(KEY_MOODLE_TOKEN, token).apply(); }

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

    // Sync interval (minutes)
    public int getSyncInterval() { return prefs.getInt(KEY_SYNC_INTERVAL, 60); }
    public void setSyncInterval(int minutes) { prefs.edit().putInt(KEY_SYNC_INTERVAL, minutes).apply(); }

    // Notification timings (comma-separated keys like "prev_20,prev_10")
    public String getSelectedNotifyTimes() { return prefs.getString(KEY_SELECTED_NOTIFY_TIMES, "prev_20"); }
    public void setSelectedNotifyTimes(String times) { prefs.edit().putString(KEY_SELECTED_NOTIFY_TIMES, times).apply(); }

    // Custom notify times (JSON array)
    public String getCustomNotifyTimes() { return prefs.getString(KEY_CUSTOM_NOTIFY_TIMES, "[]"); }
    public void setCustomNotifyTimes(String json) { prefs.edit().putString(KEY_CUSTOM_NOTIFY_TIMES, json).apply(); }

    // Background sync result notifications
    public boolean isBgNotifyChange() { return prefs.getBoolean(KEY_BG_NOTIFY_CHANGE, true); }
    public void setBgNotifyChange(boolean v) { prefs.edit().putBoolean(KEY_BG_NOTIFY_CHANGE, v).apply(); }

    public boolean isBgNotifySuccess() { return prefs.getBoolean(KEY_BG_NOTIFY_SUCCESS, true); }
    public void setBgNotifySuccess(boolean v) { prefs.edit().putBoolean(KEY_BG_NOTIFY_SUCCESS, v).apply(); }

    public boolean isBgNotifyFailure() { return prefs.getBoolean(KEY_BG_NOTIFY_FAILURE, false); }
    public void setBgNotifyFailure(boolean v) { prefs.edit().putBoolean(KEY_BG_NOTIFY_FAILURE, v).apply(); }

    public boolean isBgNotifyNoChange() { return prefs.getBoolean(KEY_BG_NOTIFY_NOCHANGE, false); }
    public void setBgNotifyNoChange(boolean v) { prefs.edit().putBoolean(KEY_BG_NOTIFY_NOCHANGE, v).apply(); }

    // Lunch notify time (HH:mm)
    public String getLunchNotifyTime() { return prefs.getString(KEY_LUNCH_NOTIFY_TIME, "11:30"); }
    public void setLunchNotifyTime(String time) { prefs.edit().putString(KEY_LUNCH_NOTIFY_TIME, time).apply(); }

    // Debug mode
    public boolean isDebugMode() { return prefs.getBoolean(KEY_DEBUG_MODE, false); }
    public void setDebugMode(boolean enabled) { prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply(); }

    // Tab ordering (comma-separated tab names)
    public String getMainTabs() { return prefs.getString(KEY_MAIN_TABS, "home,calendar,courses,gakushoku"); }
    public void setMainTabs(String tabs) { prefs.edit().putString(KEY_MAIN_TABS, tabs).apply(); }

    public String getOtherTabs() { return prefs.getString(KEY_OTHER_TABS, "facilities,grades"); }
    public void setOtherTabs(String tabs) { prefs.edit().putString(KEY_OTHER_TABS, tabs).apply(); }

    // Review consent
    public boolean isReviewConsentGiven() { return prefs.getBoolean(KEY_REVIEW_CONSENT, false); }
    public void setReviewConsentGiven(boolean given) { prefs.edit().putBoolean(KEY_REVIEW_CONSENT, given).apply(); }

    // Review internal user ID (stored securely)
    public String getReviewUserId() { return encryptedPrefs.getString(KEY_REVIEW_USER_ID, ""); }
    public void setReviewUserId(String id) { encryptedPrefs.edit().putString(KEY_REVIEW_USER_ID, id).apply(); }

    // Reset
    public void clearAll() {
        prefs.edit().clear().apply();
        encryptedPrefs.edit().clear().apply();
    }
}
