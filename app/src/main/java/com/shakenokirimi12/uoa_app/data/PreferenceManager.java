package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    private static final String PREF_NAME = "uoa_app_prefs";

    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_CREDENTIALS_INVALID = "credentials_invalid";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String KEY_REVIEW_GUIDELINES_ACCEPTED = "review_guidelines_accepted";
    private static final String KEY_SERVER_HOLDS_REMINDERS = "server_holds_assignment_reminders";
    private static final String KEY_NOTIFY_ASSIGNMENTS = "notify_assignments";
    private static final String KEY_NOTIFY_GRADES = "notify_grades";
    private static final String KEY_NOTIFY_LUNCH = "notify_lunch";
    private static final String KEY_NOTIFY_CLASS_CHANGES = "notify_class_changes";
    private static final String KEY_DEVICE_CALENDAR_SYNC = "device_calendar_sync";
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
    private static final String KEY_MAIN_TABS = "main_tabs";
    private static final String KEY_OTHER_TABS = "other_tabs";
    private static final String KEY_REVIEW_CONSENT = "review_consent_given";
    private static final String KEY_REVIEW_USER_ID = "review_user_id";
    private static final String KEY_HAS_SEEN_IDP_TUTORIAL = "has_seen_idp_tutorial";
    private static final String KEY_OTP_AUTO_FETCH_ENABLED = "otp_auto_fetch_enabled";
    private static final String KEY_CS_IDP_SESSION_COOKIE_HEADER = "cs_idp_session_cookie_header";
    private static final String KEY_IDP_SSO_COOKIE_HEADER = "idp_sso_cookie_header";
    private static final String KEY_MOODLE_SESSION_COOKIE_HEADER = "moodle_session_cookie_header";

    private static PreferenceManager instance;
    private final SharedPreferences prefs;
    // 資格情報とセッション cookie。名前はバックアップ除外の設定 (res/xml) と揃える。
    static final String SECURE_PREF_NAME = "uoa_app_secure_store";
    private static final String SECURE_KEY_ALIAS = "uoa_app_secure_store_key";
    private final SecureStore secure;

    private PreferenceManager(Context context) {
        Context appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        secure = new SecureStore(appContext, SECURE_PREF_NAME, SECURE_KEY_ALIAS);

        LegacyEncryptedPrefsMigration.migrateIfNeeded(appContext, secure);
        migrateLegacyCredentials();
    }

    private void migrateLegacyCredentials() {
        if (prefs.contains(KEY_PASSWORD)) {
            String legacyUsername = prefs.getString(KEY_USERNAME, "");
            String legacyPassword = prefs.getString(KEY_PASSWORD, "");
            String legacyMoodleToken = prefs.getString(KEY_MOODLE_TOKEN, "");

            if (!legacyUsername.isEmpty()) {
                secure.putString(KEY_USERNAME, legacyUsername);
            }
            if (!legacyPassword.isEmpty()) {
                secure.putString(KEY_PASSWORD, legacyPassword);
            }
            if (!legacyMoodleToken.isEmpty()) {
                secure.putString(KEY_MOODLE_TOKEN, legacyMoodleToken);
            }

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

    /**
     * Context を持たないサービス層 (CampusSquareService / MoodleService) から使う。
     * UoaApplication.onCreate で必ず初期化されるので、通常は null にならない。
     */
    public static synchronized PreferenceManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("PreferenceManager is not initialized; call getInstance(Context) first");
        }
        return instance;
    }

    // Credentials
    public String getUsername() { return secure.getString(KEY_USERNAME, ""); }
    public void setUsername(String username) {
        secure.putString(KEY_USERNAME, username);
        // 別のアカウントに切り替えたとき、前のユーザーの IdP セッションを引き継がない。
        setCsIdpSessionCookieHeader(null);
        setIdpSsoCookieHeader(null);
        setMoodleSessionCookieHeader(null);
    }

    public String getPassword() { return secure.getString(KEY_PASSWORD, ""); }
    public void setPassword(String password) {
        secure.putString(KEY_PASSWORD, password);
        // 入力し直したら「資格情報が不正」の判定を解除して同期を再開できるようにする。
        setCredentialsInvalid(false);
    }

    /**
     * ID/PW 誤りが確定しているか。立っている間は自動同期でログインを試みない。
     * 誤ったパスワードのまま定期同期がリトライを繰り返すと、大学側でアカウントが
     * ロックされうる。パスワードの再入力でだけ解除される。
     */
    public boolean isCredentialsInvalid() { return prefs.getBoolean(KEY_CREDENTIALS_INVALID, false); }

    public boolean isReviewGuidelinesAccepted() { return prefs.getBoolean(KEY_REVIEW_GUIDELINES_ACCEPTED, false); }
    public void setReviewGuidelinesAccepted(boolean accepted) { prefs.edit().putBoolean(KEY_REVIEW_GUIDELINES_ACCEPTED, accepted).apply(); }

    public String getFcmToken() { return prefs.getString(KEY_FCM_TOKEN, ""); }
    public void setFcmToken(String token) { prefs.edit().putString(KEY_FCM_TOKEN, token).apply(); }

    /**
     * サーバー側が期限通知の予約を持っているか。同期が成功したときだけ更新する。
     * 「サーバーは空」と言い切れるのは clearAll のときだけ (iOS の serverHoldsReminders と同じ)。
     */
    public boolean isServerHoldingReminders() { return prefs.getBoolean(KEY_SERVER_HOLDS_REMINDERS, false); }
    public void setServerHoldingReminders(boolean holds) { prefs.edit().putBoolean(KEY_SERVER_HOLDS_REMINDERS, holds).apply(); }
    public void setCredentialsInvalid(boolean invalid) { prefs.edit().putBoolean(KEY_CREDENTIALS_INVALID, invalid).apply(); }

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

    /** 休講・教室変更・補講を時間割の差分から見つけて通知するか。 */
    public boolean isClassChangeNotifyEnabled() { return prefs.getBoolean(KEY_NOTIFY_CLASS_CHANGES, true); }
    public void setClassChangeNotifyEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_NOTIFY_CLASS_CHANGES, enabled).apply(); }

    /** 時間割を端末のカレンダー (CalendarContract) に書き出すか。カレンダー権限が要るのでオプトイン。 */
    public boolean isDeviceCalendarSyncEnabled() { return prefs.getBoolean(KEY_DEVICE_CALENDAR_SYNC, false); }
    public void setDeviceCalendarSyncEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_DEVICE_CALENDAR_SYNC, enabled).apply(); }

    // Moodle
    public String getMoodleToken() { return secure.getString(KEY_MOODLE_TOKEN, ""); }
    public void setMoodleToken(String token) { secure.putString(KEY_MOODLE_TOKEN, token); }

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

    // Tab ordering (comma-separated tab names)
    public String getMainTabs() { return prefs.getString(KEY_MAIN_TABS, "home,calendar,courses,gakushoku"); }
    public void setMainTabs(String tabs) { prefs.edit().putString(KEY_MAIN_TABS, tabs).apply(); }

    public String getOtherTabs() { return prefs.getString(KEY_OTHER_TABS, "facilities,grades"); }
    public void setOtherTabs(String tabs) { prefs.edit().putString(KEY_OTHER_TABS, tabs).apply(); }

    // Review consent
    public boolean isReviewConsentGiven() { return prefs.getBoolean(KEY_REVIEW_CONSENT, false); }
    public void setReviewConsentGiven(boolean given) { prefs.edit().putBoolean(KEY_REVIEW_CONSENT, given).apply(); }

    // Review internal user ID (stored securely)
    public String getReviewUserId() { return secure.getString(KEY_REVIEW_USER_ID, ""); }
    public void setReviewUserId(String id) { secure.putString(KEY_REVIEW_USER_ID, id); }

    // IdP (SECIOSS) login。iOS の SettingsStore.hasSeenIdPTutorial / otpAutoFetchEnabled と同じ既定値。
    public boolean hasSeenIdPTutorial() { return prefs.getBoolean(KEY_HAS_SEEN_IDP_TUTORIAL, false); }
    public void setHasSeenIdPTutorial(boolean seen) { prefs.edit().putBoolean(KEY_HAS_SEEN_IDP_TUTORIAL, seen).apply(); }

    /**
     * IdP ログインでワンタイムパスワードが必要なとき、メール (IMAP) から自動取得してよいか。
     * 既定は true (2026-09-15 の判断: 同期のたびに手入力を求めると実用にならない)。
     * IdP チュートリアルで手動入力に切り替えられる。
     */
    public boolean isOtpAutoFetchEnabled() { return prefs.getBoolean(KEY_OTP_AUTO_FETCH_ENABLED, true); }
    public void setOtpAutoFetchEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_OTP_AUTO_FETCH_ENABLED, enabled).apply(); }

    /**
     * IdP 経由で得た CampusSquare のセッション Cookie ヘッダ。iOS は Keychain に置いている。
     * セッション自体が資格情報に相当するので暗号化側に入れる。空文字なら無し。
     */
    public String getCsIdpSessionCookieHeader() { return secure.getString(KEY_CS_IDP_SESSION_COOKIE_HEADER, ""); }
    public void setCsIdpSessionCookieHeader(String header) {
        if (header == null || header.isEmpty()) {
            secure.remove(KEY_CS_IDP_SESSION_COOKIE_HEADER);
        } else {
            secure.putString(KEY_CS_IDP_SESSION_COOKIE_HEADER, header);
        }
    }
    public boolean hasCsIdpSession() { return !getCsIdpSessionCookieHeader().isEmpty(); }

    /**
     * IdP (slink.secioss.com) 自体の SSO セッション cookie ヘッダ。SP のセッションとは別物で、
     * これが生きている間は入口を叩くだけでパスワードも OTP も要求されずに SP へ戻れる。
     * SP のセッションと同じく資格情報に相当するので暗号化側へ入れる。
     */
    /**
     * Moodle (elms) のセッション cookie ヘッダ。起動のたびに IdP からログインし直す (約 5 秒) のを
     * 避けるために持ち越す。CampusSquare 側の cs_idp_session_cookie_header と同じ扱い。
     */
    public String getMoodleSessionCookieHeader() { return secure.getString(KEY_MOODLE_SESSION_COOKIE_HEADER, ""); }
    public void setMoodleSessionCookieHeader(String header) {
        if (header == null || header.isEmpty()) {
            secure.remove(KEY_MOODLE_SESSION_COOKIE_HEADER);
        } else {
            secure.putString(KEY_MOODLE_SESSION_COOKIE_HEADER, header);
        }
    }

    public String getIdpSsoCookieHeader() { return secure.getString(KEY_IDP_SSO_COOKIE_HEADER, ""); }
    public void setIdpSsoCookieHeader(String header) {
        if (header == null || header.isEmpty()) {
            secure.remove(KEY_IDP_SSO_COOKIE_HEADER);
        } else {
            secure.putString(KEY_IDP_SSO_COOKIE_HEADER, header);
        }
    }

    // Reset
    public void clearAll() {
        prefs.edit().clear().apply();
        secure.clear();
    }
}
