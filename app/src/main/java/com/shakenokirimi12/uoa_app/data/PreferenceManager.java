package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import android.util.Log;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class PreferenceManager {
    private static final String PREF_NAME = "uoa_app_prefs";
    /** 暗号化側。鍵は端末の Keystore にあり移せないので、バックアップ対象から外している (xml/backup_rules)。 */
    private static final String SECURE_PREF_NAME = "uoa_app_secure_prefs";

    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_CREDENTIALS_INVALID = "credentials_invalid";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String KEY_REVIEW_GUIDELINES_ACCEPTED = "review_guidelines_accepted";
    private static final String KEY_SERVER_HOLDS_REMINDERS = "server_holds_assignment_reminders";
    private static final String KEY_NOTIFY_ASSIGNMENTS = "notify_assignments";
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
    private static final String KEY_LOGIN_REQUIRED_NOTIFIED_DAY = "login_required_notified_day";
    private static final String KEY_MAIN_TABS = "main_tabs";
    private static final String KEY_OTHER_TABS = "other_tabs";
    private static final String KEY_REVIEW_CONSENT = "review_consent_given";
    private static final String KEY_REVIEW_USER_ID = "review_user_id";
    private static final String KEY_HAS_SEEN_IDP_TUTORIAL = "has_seen_idp_tutorial";
    private static final String KEY_OTP_AUTO_FETCH_ENABLED = "otp_auto_fetch_enabled";
    private static final String KEY_OTP_METHOD = "otp_method";
    private static final String KEY_CS_IDP_SESSION_COOKIE_HEADER = "cs_idp_session_cookie_header";
    private static final String KEY_IDP_SSO_COOKIE_HEADER = "idp_sso_cookie_header";
    private static final String KEY_IDP_SSO_OWNER = "idp_sso_owner";
    private static final String KEY_OTP_RELAY_PAIRINGS = "otp_relay_pairings";
    private static final String KEY_OTP_RELAY_LAST_RESULT = "otp_relay_last_result";
    private static final String KEY_MOODLE_SESSION_COOKIE_HEADER = "moodle_session_cookie_header";

    private static final String TAG = "PreferenceManager";

    /**
     * 暗号化側にしか置かない値。暗号化ストアが使えない端末では保存を拒否する。
     * 以前は作成に失敗すると平文の prefs に同じキーで書いていたので、そこに残った写しは起動時に消す。
     */
    private static final String[] SECRET_KEYS = {
            KEY_USERNAME, KEY_PASSWORD, KEY_MOODLE_TOKEN, KEY_REVIEW_USER_ID,
            KEY_CS_IDP_SESSION_COOKIE_HEADER, KEY_IDP_SSO_COOKIE_HEADER, KEY_MOODLE_SESSION_COOKIE_HEADER,
    };

    private static PreferenceManager instance;
    private final SharedPreferences prefs;
    /** 作れなかった端末では null。資格情報も cookie も保存しない (平文には落とさない)。 */
    private final SharedPreferences encryptedPrefs;

    private PreferenceManager(Context context) {
        Context appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        encryptedPrefs = openSecurePrefsOrNull(appContext);

        migrateLegacySecrets();
        // 成績変更の通知は実装されないまま設定だけがあった (iOS にも無い)。キーごと消した。
        if (prefs.contains("notify_grades")) prefs.edit().remove("notify_grades").apply();
    }

    /**
     * 開けなければ null。平文へのフォールバックはしない (資格情報と cookie を平文で書くことになるため)。
     * null の端末ではログイン情報を保存できない (isSecureStorageAvailable が false) 扱いにし、画面側で
     * 保存を断ってエラーを出す。
     *
     * 1. まず開く。Keystore の一時的な失敗もあるので、失敗したらそのままもう 1 回だけ開く。
     * 2. それでも駄目で、原因が「保存済みの鍵束を復号できない」(別端末のバックアップから復元した
     *    ファイルを、この端末の Keystore の鍵では開けない) なら、中身はもう読めないので作り直す。
     *    鍵束 (Tink の keyset) は同じファイル (SECURE_PREF_NAME) に入っている。
     *    それ以外の原因では消さない (次回の起動で開けるかもしれない資格情報を失わないため)。
     */
    private static SharedPreferences openSecurePrefsOrNull(Context appContext) {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return openSecurePrefs(appContext);
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                last = e;
            }
        }
        if (isUndecryptable(last)) {
            Log.w(TAG, "secure prefs cannot be decrypted on this device; recreating", last);
            appContext.deleteSharedPreferences(SECURE_PREF_NAME);
            try {
                return openSecurePrefs(appContext);
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                last = e;
            }
        }
        Log.e(TAG, "EncryptedSharedPreferences unavailable; secrets will not be stored", last);
        return null;
    }

    /** 鍵束の復号失敗 (タグ不一致・壊れた protobuf) か。 */
    private static boolean isUndecryptable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.crypto.AEADBadTagException || t instanceof javax.crypto.BadPaddingException) return true;
            if (t.getClass().getName().endsWith("InvalidProtocolBufferException")) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static SharedPreferences openSecurePrefs(Context appContext) throws GeneralSecurityException, IOException {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        return EncryptedSharedPreferences.create(
                SECURE_PREF_NAME,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    /**
     * 平文の prefs にある秘密情報を暗号化側へ移し、平文側からは必ず消す。
     * 暗号化ストアが使えない端末では移さずに消すだけ (平文で持ち続けるよりはログインし直しの方がよい)。
     */
    private void migrateLegacySecrets() {
        SharedPreferences.Editor plainEdit = null;
        SharedPreferences.Editor secEdit = encryptedPrefs != null ? encryptedPrefs.edit() : null;
        for (String key : SECRET_KEYS) {
            if (!prefs.contains(key)) continue;
            String value = prefs.getString(key, "");
            if (secEdit != null && value != null && !value.isEmpty() && !encryptedPrefs.contains(key)) {
                secEdit.putString(key, value);
            }
            if (plainEdit == null) plainEdit = prefs.edit();
            plainEdit.remove(key);
        }
        if (plainEdit == null) return;
        // 暗号化側への書き込みを先に確定させてから平文を消す (途中で落ちても値を失わない順)。
        if (secEdit != null) secEdit.commit();
        plainEdit.apply();
    }

    /** 資格情報・セッション cookie を保存できるか。false の端末ではログイン情報を保存しない。 */
    public boolean isSecureStorageAvailable() { return encryptedPrefs != null; }

    private String getSecret(String key) {
        return encryptedPrefs != null ? encryptedPrefs.getString(key, "") : "";
    }

    /** 暗号化ストアが無ければ書かない。null / 空文字は削除。 */
    private void putSecret(String key, String value) {
        if (encryptedPrefs == null) {
            Log.w(TAG, "secure storage unavailable; not storing " + key);
            return;
        }
        if (value == null || value.isEmpty()) {
            encryptedPrefs.edit().remove(key).apply();
        } else {
            encryptedPrefs.edit().putString(key, value).apply();
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
    public String getUsername() { return getSecret(KEY_USERNAME); }
    public void setUsername(String username) {
        putSecret(KEY_USERNAME, username);
        // 別のアカウントに切り替えたとき、前のユーザーの IdP セッションを引き継がない。
        setCsIdpSessionCookieHeader(null);
        // メモリ上のセッション cookie も含めて消す (ストアはプロセス内に写しを持つ)。
        com.shakenokirimi12.uoa_app.services.idp.SeciossSsoStore.getInstance().clear();
        setMoodleSessionCookieHeader(null);
    }

    public String getPassword() { return getSecret(KEY_PASSWORD); }
    public void setPassword(String password) {
        putSecret(KEY_PASSWORD, password);
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

    public boolean isLunchNotifyEnabled() { return prefs.getBoolean(KEY_NOTIFY_LUNCH, false); }
    public void setLunchNotifyEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_NOTIFY_LUNCH, enabled).apply(); }

    // Moodle
    public String getMoodleToken() { return getSecret(KEY_MOODLE_TOKEN); }
    public void setMoodleToken(String token) { putSecret(KEY_MOODLE_TOKEN, token); }

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

    /** 「ログインが必要です」を最後に通知した日 (yyyy-MM-dd, 日本時間)。1 日 1 回に抑えるため。 */
    public String getLoginRequiredNotifiedDay() { return prefs.getString(KEY_LOGIN_REQUIRED_NOTIFIED_DAY, ""); }
    public void setLoginRequiredNotifiedDay(String day) { prefs.edit().putString(KEY_LOGIN_REQUIRED_NOTIFIED_DAY, day).apply(); }

    // Tab ordering (comma-separated tab names)
    public String getMainTabs() { return prefs.getString(KEY_MAIN_TABS, "home,calendar,courses,gakushoku"); }
    public void setMainTabs(String tabs) { prefs.edit().putString(KEY_MAIN_TABS, tabs).apply(); }

    public String getOtherTabs() { return prefs.getString(KEY_OTHER_TABS, "facilities,grades"); }
    public void setOtherTabs(String tabs) { prefs.edit().putString(KEY_OTHER_TABS, tabs).apply(); }

    // Review consent
    public boolean isReviewConsentGiven() { return prefs.getBoolean(KEY_REVIEW_CONSENT, false); }
    public void setReviewConsentGiven(boolean given) { prefs.edit().putBoolean(KEY_REVIEW_CONSENT, given).apply(); }

    // Review internal user ID (stored securely)
    public String getReviewUserId() { return getSecret(KEY_REVIEW_USER_ID); }
    public void setReviewUserId(String id) { putSecret(KEY_REVIEW_USER_ID, id); }

    // IdP (SECIOSS) login。iOS の SettingsStore.hasSeenIdPTutorial / otpAutoFetchEnabled と同じ既定値。
    public boolean hasSeenIdPTutorial() { return prefs.getBoolean(KEY_HAS_SEEN_IDP_TUTORIAL, false); }
    public void setHasSeenIdPTutorial(boolean seen) { prefs.edit().putBoolean(KEY_HAS_SEEN_IDP_TUTORIAL, seen).apply(); }

    /**
     * IdP ログインでワンタイムパスワードが必要なとき、メール (IMAP) から自動取得してよいか。
     * 既定は false (同意してから)。大学のメールボックスに入る操作なので、iOS (SettingsStore の
     * otp_auto_fetch_enabled、既定 false) と同じく本人が選んだときだけ有効にする。
     * 有効にする経路: IdP チュートリアルの選択、OTP 入力ダイアログの「今後はメールから自動で取得する」。
     * 以前は既定 true だったが、未設定の端末では読まれる値が変わるだけで、保存済みの選択は変えない。
     */
    public boolean isOtpAutoFetchEnabled() { return prefs.getBoolean(KEY_OTP_AUTO_FETCH_ENABLED, false); }
    public void setOtpAutoFetchEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_OTP_AUTO_FETCH_ENABLED, enabled).apply(); }

    /**
     * OTP をどこから受け取るか ("mail" / "authenticator")。既定はメール (大学が既定で設定する方式)。
     * 認証アプリ方式を選んだ場合、アプリはシークレットを保持しないので毎回手入力になる。
     * そのぶんセッションの永続化が効いていることが前提になる。iOS の SettingsStore.otpMethod と同じ値。
     */
    public String getOtpMethod() { return prefs.getString(KEY_OTP_METHOD, "mail"); }
    public void setOtpMethod(String method) { prefs.edit().putString(KEY_OTP_METHOD, method).apply(); }

    /**
     * IdP 経由で得た CampusSquare のセッション Cookie ヘッダ。iOS は Keychain に置いている。
     * セッション自体が資格情報に相当するので暗号化側に入れる。空文字なら無し。
     */
    public String getCsIdpSessionCookieHeader() { return getSecret(KEY_CS_IDP_SESSION_COOKIE_HEADER); }
    public void setCsIdpSessionCookieHeader(String header) { putSecret(KEY_CS_IDP_SESSION_COOKIE_HEADER, header); }
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
    public String getMoodleSessionCookieHeader() { return getSecret(KEY_MOODLE_SESSION_COOKIE_HEADER); }
    public void setMoodleSessionCookieHeader(String header) { putSecret(KEY_MOODLE_SESSION_COOKIE_HEADER, header); }

    public String getIdpSsoCookieHeader() { return getSecret(KEY_IDP_SSO_COOKIE_HEADER); }
    public void setIdpSsoCookieHeader(String header) { putSecret(KEY_IDP_SSO_COOKIE_HEADER, header); }

    /** IdP の SSO セッションの持ち主 (uid)。別アカウントのチケットを再生しないため。 */
    public String getIdpSsoOwner() { return getSecret(KEY_IDP_SSO_OWNER); }
    public void setIdpSsoOwner(String owner) { putSecret(KEY_IDP_SSO_OWNER, owner); }

    /**
     * PC 中継の連携一覧 (JSON)。K (暗号鍵の元) を含むので暗号化側にだけ置く。
     * 暗号化ストアが使えない端末では保存されない (連携は断る)。
     */
    public String getOtpRelayPairingsJson() { return getSecret(KEY_OTP_RELAY_PAIRINGS); }
    public void setOtpRelayPairingsJson(String json) { putSecret(KEY_OTP_RELAY_PAIRINGS, json); }

    /** PC 中継の直近の自動応答の結果 (JSON)。IMAP の応答行を含みうるので暗号化側に置く。 */
    public String getOtpRelayLastResultJson() { return getSecret(KEY_OTP_RELAY_LAST_RESULT); }
    public void setOtpRelayLastResultJson(String json) { putSecret(KEY_OTP_RELAY_LAST_RESULT, json); }

    // Reset
    public void clearAll() {
        prefs.edit().clear().apply();
        if (encryptedPrefs != null) encryptedPrefs.edit().clear().apply();
    }
}
