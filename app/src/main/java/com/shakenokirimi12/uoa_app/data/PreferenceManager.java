package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import android.util.Log;

import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class PreferenceManager {
    public static final String PREF_NAME = "uoa_app_prefs";
    /** 暗号化側。鍵は端末の Keystore にあり移せないので、バックアップ対象から外している (xml/backup_rules)。 */
    private static final String SECURE_PREF_NAME = "uoa_app_secure_prefs";

    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    /** 時間割共有で、自分が出している共有の返事を読むトークン (data/timetable/TimetableShareStore)。 */
    private static final String KEY_TIMETABLE_SHARE_OWNER_TOKEN = "timetable_share_owner_token";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_ONBOARDING_SEEN_STEPS = "onboarding_seen_steps";
    /** 以前の「ID/PW 誤り」の印 (真偽値)。指紋 (KEY_CREDENTIALS_INVALID_FP) に移して消す。 */
    private static final String KEY_CREDENTIALS_INVALID_LEGACY = "credentials_invalid";
    /** ID/PW 誤りと分かった資格情報の指紋 (BackgroundLoginPolicy.fingerprint)。資格情報が変わるまでログインしない (D-8)。 */
    private static final String KEY_CREDENTIALS_INVALID_FP = "credentials_invalid_fingerprint";
    /** 誤りを通知で知らせた指紋。同じ指紋では 2 回知らせない。 */
    private static final String KEY_CREDENTIALS_INVALID_NOTIFIED_FP = "credentials_invalid_notified_fingerprint";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String KEY_SERVER_HOLDS_REMINDERS = "server_holds_assignment_reminders";
    private static final String KEY_NOTIFY_ASSIGNMENTS = "notify_assignments";
    private static final String KEY_NOTIFY_LUNCH = "notify_lunch";
    private static final String KEY_CLASS_NOTIFICATION = "class_notification_enabled";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_DEVICE_ID = "device_id";
    /** 問い合わせ ID (SupportId)。device_id と同じく、この端末 (このインストール) だけのもの。 */
    private static final String KEY_SUPPORT_ID = "support_id";
    private static final String KEY_AUTO_ATTENDANCE = "auto_attendance_enabled";
    /** 「利用状況と診断情報を送る」(OTP の診断の記録 OtpDiagnostics もこれに従う)。BackupService.SETTINGS_KEYS と同じ文字列 (設定として持ち運ぶ)。 */
    public static final String KEY_USAGE_STATS_ENABLED = "usage_stats_enabled";
    /** 最後に送った OTP 画面の形と、そのときの日付と版 (OtpDiagnostics.claimOtpPageShape)。 */
    private static final String KEY_OTP_PAGE_SHAPE_SENT = "otp_page_shape_sent";
    private static final String KEY_SELECTED_NOTIFY_TIMES = "selected_notify_times";
    private static final String KEY_CUSTOM_NOTIFY_TIMES = "custom_notify_times";
    private static final String KEY_LUNCH_NOTIFY_TIME = "lunch_notify_time";
    private static final String KEY_MAIN_TABS = "main_tabs";
    private static final String KEY_OTHER_TABS = "other_tabs";
    private static final String KEY_HAS_SEEN_IDP_TUTORIAL = "has_seen_idp_tutorial";
    private static final String KEY_OTP_METHOD = "otp_method";
    private static final String KEY_CS_IDP_SESSION_COOKIE_HEADER = "cs_idp_session_cookie_header";
    private static final String KEY_IDP_SSO_COOKIE_HEADER = "idp_sso_cookie_header";
    private static final String KEY_IDP_SSO_OWNER = "idp_sso_owner";
    private static final String KEY_OTP_RELAY_PAIRINGS = "otp_relay_pairings";
    private static final String KEY_OTP_RELAY_LAST_RESULT = "otp_relay_last_result";
    private static final String KEY_OTP_RELAY_PENDING_UNPAIRS = "otp_relay_pending_unpairs";
    /** 認証アプリ方式で IdP に登録した otpauth URL (シークレットを含む) と、その持ち主のユーザー名。 */
    private static final String KEY_TOTP_OTPAUTH = "totp_otpauth_url";
    private static final String KEY_TOTP_OWNER = "totp_owner";
    private static final String KEY_MOODLE_SESSION_COOKIE_HEADER = "moodle_session_cookie_header";
    /** 最後にログインに成功した ID / パスワードの指紋 (BackgroundLoginPolicy)。パスワードそのものは置かない。 */
    private static final String KEY_BG_LOGIN_VERIFIED = "background_login_verified";
    /** バックグラウンドのログインが失敗して、前面で成功するまで止めている。 */
    private static final String KEY_BG_LOGIN_BLOCKED = "background_login_blocked";
    private static final String KEY_BG_LOGIN_LAST_ATTEMPT_PREFIX = "background_login_last_attempt.";
    /** メール方式のバックグラウンドのログインで、最後に OTP メールの送信を頼んだ時刻 (端末全体で 6 時間に 1 回)。 */
    private static final String KEY_BG_LOGIN_LAST_MAIL_DISPATCH = "background_login_last_mail_dispatch";
    /** 鍵で作ったコードが IdP に 2 回続けて弾かれ、自動での使用を止めている (D-5)。鍵と同じ暗号化側に置き、鍵と一緒に消える。 */
    private static final String KEY_TOTP_SUSPENDED = "totp_suspended";
    /** 最後に IdP に渡した (渡す予定の) TOTP の窓の番号 (TotpStepLedger)。番号だけなので平文の prefs に置く。 */
    private static final String KEY_TOTP_LAST_USED_STEP = "totp_last_used_step";

    private static final String TAG = "PreferenceManager";

    /**
     * この端末 (このインストール) だけのもの。バックアップから別端末へ持ち込んではいけない:
     * device_id をそのまま使うと 2 台が同じ id でサーバーに登録され、プッシュの宛先と課題リマインダーの予約を
     * 取り合う。fcm_token は持ち込んだ先では別端末のトークン。server_holds_assignment_reminders は
     * 「この device_id の予約がサーバーにあるか」なので、id と一緒に意味を失う。
     * otp_method (OTP の受け取り方) は大学側 (IdP) に登録されている方式と揃っていないとログインできない。
     * 復元しても IdP の方式は変わらないので、バックアップの時点の方式を戻すと食い違うことがある
     * (iOS の BackupService も預けも戻しもしない)。書き換えは IdP への登録が済んだときだけ
     * (applyRegisteredOtpMethod)。
     * SAF のバックアップ (BackupService) は書き出さず、OS の自動バックアップ・端末間転送は
     * UoaBackupAgent が復元直後に消す。消えた端末は getDeviceId() が新しい id を作る。
     * support_id (問い合わせ ID) も持ち込むと 2 台が同じ ID になり、管理画面の検索で見分けられない。
     */
    public static final String[] DEVICE_LOCAL_KEYS = {
            KEY_DEVICE_ID, KEY_FCM_TOKEN, KEY_SERVER_HOLDS_REMINDERS, KEY_OTP_METHOD, KEY_SUPPORT_ID,
    };

    /**
     * 暗号化側にしか置かない値。暗号化ストアが使えない端末では保存を拒否する。
     * 以前は作成に失敗すると平文の prefs に同じキーで書いていたので、そこに残った写しは起動時に消す。
     */
    private static final String[] SECRET_KEYS = {
            KEY_USERNAME, KEY_PASSWORD,
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
        // OTP をメールから自動で取得するかの選択。メール方式ではいつも自動で取得するようにしたので
        // (2026-10-21)、キーごと消した。
        if (prefs.contains("otp_auto_fetch_enabled")) prefs.edit().remove("otp_auto_fetch_enabled").apply();
        // 鍵の無い人へ起動時に登録し直しを案内したかの印。案内はログインでコードが要ったときの選択に移した
        // (2026-10-31、AuthenticatorKeyChoice) ので、キーごと消した。
        if (prefs.contains("totp_reregistration_prompted")) prefs.edit().remove("totp_reregistration_prompted").apply();
        // 授業評価は機能ごと消した (2026-10-22)。端末に残った同意と、サーバーのアカウント ID を消す。
        dropRemovedKeys(new String[] {"review_consent_given", "review_guidelines_accepted", "review_user_id"},
                new String[] {"review_user_id", "review_token"});
        // 同期の間隔 (既定の 1 時間に固定) と同期結果の通知 4 つは設定から消した (2026-10-22)。
        dropRemovedKeys(new String[] {"sync_interval", "bg_notify_change", "bg_notify_success",
                "bg_notify_failure", "bg_notify_nochange"}, new String[0]);
        // 誰も読み書きしていなかった値 (ICS の URL、Moodle のトークン。以前の平文の写しも)。
        dropRemovedKeys(new String[] {"ics_url", "moodle_token"}, new String[] {"moodle_token"});
        // 「ログインが必要です」の通知は出さなくなった (2026-10-30, D-10)。最後に出した日も要らない。
        dropRemovedKeys(new String[] {"login_required_notified_day"}, new String[0]);
        migrateLegacyCredentialsInvalid();
    }

    /** 以前の真偽値の「ID/PW 誤り」を、今の資格情報の指紋の印に移す (同じ資格情報のままなら止めたまま)。 */
    private void migrateLegacyCredentialsInvalid() {
        if (!prefs.contains(KEY_CREDENTIALS_INVALID_LEGACY)) return;
        boolean invalid = prefs.getBoolean(KEY_CREDENTIALS_INVALID_LEGACY, false);
        if (invalid && hasCredentials()) {
            String fp = currentCredentialFingerprint();
            // 前の版で既に知らせているので、もう一度は知らせない。
            if (encryptedPrefs == null || !encryptedPrefs.edit()
                    .putString(KEY_CREDENTIALS_INVALID_FP, fp)
                    .putString(KEY_CREDENTIALS_INVALID_NOTIFIED_FP, fp)
                    .commit()) {
                return;
            }
        }
        prefs.edit().remove(KEY_CREDENTIALS_INVALID_LEGACY).apply();
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
        // 書き込めなかった (容量不足など) ときは平文を残し、次の起動でやり直す。消すと資格情報を失う。
        if (secEdit != null && !secEdit.commit()) {
            Log.w(TAG, "could not move legacy secrets into secure prefs; will retry on next launch");
            return;
        }
        plainEdit.apply();
    }

    /** 機能ごと消した設定を、平文側と暗号化側の両方から消す。無ければ何もしない。 */
    private void dropRemovedKeys(String[] plainKeys, String[] secretKeys) {
        SharedPreferences.Editor plain = null;
        for (String key : plainKeys) {
            if (!prefs.contains(key)) continue;
            if (plain == null) plain = prefs.edit();
            plain.remove(key);
        }
        if (plain != null) plain.apply();
        if (encryptedPrefs == null) return;
        SharedPreferences.Editor secure = null;
        for (String key : secretKeys) {
            if (!encryptedPrefs.contains(key)) continue;
            if (secure == null) secure = encryptedPrefs.edit();
            secure.remove(key);
        }
        if (secure != null) secure.apply();
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
    /** username が、保存しているのと別のアカウントか (初めて保存するとき・同じ名前では false)。 */
    public boolean isOtherAccount(String username) {
        String previous = getUsername();
        return !previous.isEmpty() && !previous.equals(username != null ? username : "");
    }

    /**
     * 確認のログインで通ったユーザー名を保存する。別のアカウントに変えたかを返す (初めて保存するとき・同じ名前の
     * 保存では false)。前のアカウントの方式の控え・SSO・SP のセッションは、確認のログインの前に
     * discardAccountStateForSwitch で捨ててある。ここで捨てると、確認のログインで得た新しいアカウントの
     * SSO・セッション・方式の控えまで消え、次の同期でもう一度ログインする (メール方式なら OTP メールがもう 1 通)。
     */
    public boolean setUsername(String username) {
        boolean otherAccount = isOtherAccount(username);
        putSecret(KEY_USERNAME, username);
        if (!otherAccount) return false;
        // 認証アプリのシークレットもアカウントごと。別のアカウントの鍵でコードを作らない。持ち主付きなので、
        // 確認のログインが失敗して前のアカウントに留まるときのために、保存が決まってから消す。
        clearTotp();
        return true;
    }

    /**
     * 別のアカウントへ切り替えるときに、確認のログインの「前」に呼ぶ (AccountSwitch)。前のアカウントの OTP の
     * 受け取り方の控え、IdP の SSO、SP のセッションを捨てる。
     *
     * 前のアカウントの SSO を残したまま確認のログインをすると、IdP がその SSO だけで通し (パスワードを送らない)、
     * 新しいユーザー名とパスワードを確かめないまま保存してしまう (持ち主が記録されていない古い SSO は
     * SeciossSsoStore.claimFor でも捨てられない)。確認のログインが失敗しても、前のアカウントは次のログインで
     * 取り直すだけで、鍵 (持ち主付き) は残る。
     */
    public void discardAccountStateForSwitch() {
        // OTP の受け取り方 (IdP の方式の控え) はアカウントごと。確認のログインで新しいアカウントの方式を読む (OtpProviders)。
        prefs.edit().remove(KEY_OTP_METHOD).apply();
        setCsIdpSessionCookies(null);
        // メモリ上のセッション cookie も含めて消す (ストアはプロセス内に写しを持つ)。
        com.shakenokirimi12.uoa_app.services.idp.SeciossSsoStore.getInstance().clear();
        setMoodleSessionCookies(null);
    }

    /** 時間割共有の返事を読むトークン。暗号化 prefs にだけ置く (使えない端末では保存されず、空)。 */
    public String getTimetableShareOwnerToken() { return getSecret(KEY_TIMETABLE_SHARE_OWNER_TOKEN); }
    /** 保存できたか (暗号化 prefs が使えない端末・書き込みの失敗では false)。null / 空文字は削除。 */
    public boolean setTimetableShareOwnerToken(String token) {
        if (encryptedPrefs == null) return token == null || token.isEmpty();
        SharedPreferences.Editor e = encryptedPrefs.edit();
        if (token == null || token.isEmpty()) e.remove(KEY_TIMETABLE_SHARE_OWNER_TOKEN);
        else e.putString(KEY_TIMETABLE_SHARE_OWNER_TOKEN, token);
        return e.commit();
    }

    public String getPassword() { return getSecret(KEY_PASSWORD); }
    public void setPassword(String password) {
        // 「ID/PW 誤り」の印は資格情報の指紋に結び付いている。同じ値を入れ直しても外れず、変えたら外れる (D-8)。
        putSecret(KEY_PASSWORD, password);
    }

    /**
     * 保存している ID/PW が誤りと分かっているか。分かっている間は前面でもバックグラウンドでもログインしない。
     * 誤ったパスワードのまま試し続けると、大学側でアカウントがロックされうる。ID かパスワードが変わるまで外れない。
     */
    public boolean isCredentialsInvalid() {
        return hasCredentials() && isCredentialsInvalid(getUsername(), getPassword());
    }

    /** uid / pass (前後の空白は落とす) が誤りと分かっているか。 */
    public boolean isCredentialsInvalid(@NonNull String uid, @NonNull String pass) {
        String marked = getSecret(KEY_CREDENTIALS_INVALID_FP);
        return !marked.isEmpty() && marked.equals(credentialFingerprint(uid, pass));
    }

    /**
     * uid / pass が誤りと分かったと記録する。この指紋をまだ通知で知らせていなければ true (呼び出し側が 1 回知らせて
     * markCredentialsInvalidNotified を呼ぶ)。
     */
    public synchronized boolean markCredentialsInvalid(@NonNull String uid, @NonNull String pass) {
        if (encryptedPrefs == null) return false;
        String fp = credentialFingerprint(uid, pass);
        encryptedPrefs.edit().putString(KEY_CREDENTIALS_INVALID_FP, fp).commit();
        return !fp.equals(getSecret(KEY_CREDENTIALS_INVALID_NOTIFIED_FP));
    }

    public void markCredentialsInvalidNotified(@NonNull String uid, @NonNull String pass) {
        putSecret(KEY_CREDENTIALS_INVALID_NOTIFIED_FP, credentialFingerprint(uid, pass));
    }

    @NonNull
    private static String credentialFingerprint(@NonNull String uid, @NonNull String pass) {
        return com.shakenokirimi12.uoa_app.services.BackgroundLoginPolicy.fingerprint(uid.trim(), pass.trim());
    }

    @NonNull
    private String currentCredentialFingerprint() { return credentialFingerprint(getUsername(), getPassword()); }

    public String getFcmToken() { return prefs.getString(KEY_FCM_TOKEN, ""); }
    public void setFcmToken(String token) { prefs.edit().putString(KEY_FCM_TOKEN, token).apply(); }

    /**
     * サーバー側が期限通知の予約を持っているか。同期が成功したときだけ更新する。
     * 「サーバーは空」と言い切れるのは clearAll のときだけ (iOS の serverHoldsReminders と同じ)。
     */
    public boolean isServerHoldingReminders() { return prefs.getBoolean(KEY_SERVER_HOLDS_REMINDERS, false); }
    public void setServerHoldingReminders(boolean holds) { prefs.edit().putBoolean(KEY_SERVER_HOLDS_REMINDERS, holds).apply(); }

    public boolean hasCredentials() {
        return !getUsername().isEmpty() && !getPassword().isEmpty();
    }

    // Onboarding
    public boolean isOnboardingDone() { return prefs.getBoolean(KEY_ONBOARDING_DONE, false); }
    public void setOnboardingDone(boolean done) { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply(); }
    /** 初期設定で見た段 (OnboardingProgress の ID)。記録を始める前に終えた端末では null。 */
    @Nullable
    public java.util.Set<String> getOnboardingSeenSteps() {
        java.util.Set<String> saved = prefs.getStringSet(KEY_ONBOARDING_SEEN_STEPS, null);
        // 返された Set は書き換えてはいけない (SharedPreferences の決まり)。
        return saved != null ? new java.util.HashSet<>(saved) : null;
    }
    public void setOnboardingSeenSteps(@NonNull java.util.Set<String> steps) {
        prefs.edit().putStringSet(KEY_ONBOARDING_SEEN_STEPS, new java.util.HashSet<>(steps)).apply();
    }

    // Notifications
    public boolean isAssignmentNotifyEnabled() { return prefs.getBoolean(KEY_NOTIFY_ASSIGNMENTS, true); }
    public void setAssignmentNotifyEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_NOTIFY_ASSIGNMENTS, enabled).apply(); }

    /** 授業中・次の授業の常駐通知 (iOS の Live Activity の設定に相当)。既定は今までどおり出す。 */
    public boolean isClassNotificationEnabled() { return prefs.getBoolean(KEY_CLASS_NOTIFICATION, true); }
    public void setClassNotificationEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_CLASS_NOTIFICATION, enabled).apply(); }

    public boolean isLunchNotifyEnabled() { return prefs.getBoolean(KEY_NOTIFY_LUNCH, false); }
    public void setLunchNotifyEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_NOTIFY_LUNCH, enabled).apply(); }

    // Sync
    public long getLastSync() { return prefs.getLong(KEY_LAST_SYNC, 0); }
    public void setLastSync(long timestamp) { prefs.edit().putLong(KEY_LAST_SYNC, timestamp).apply(); }

    // Device ID for push notifications
    public String getDeviceId() { return deviceIdIn(prefs); }

    /**
     * 通知サーバーの端末 ID。無ければ作って保存する。
     *
     * 平文の prefs に置く (暗号化側は Keystore の不調で作り直すことがあり、そのたびに ID が変わるため)。
     * ロックを取るのは、初回起動で 2 つのスレッド (登録と app-config) が同時に呼ぶと別々の ID を作り、
     * 片方の ID でサーバーに行ができたあとで、もう片方が保存されて残るため。
     * 作った ID は commit で書き切る。apply のままプロセスが落ちると、その ID で登録した後に保存されず、
     * 次の起動で別の ID になる。
     */
    static String deviceIdIn(@NonNull SharedPreferences prefs) {
        synchronized (DEVICE_ID_LOCK) {
            String id = prefs.getString(KEY_DEVICE_ID, "");
            if (id == null || id.isEmpty()) {
                id = java.util.UUID.randomUUID().toString();
                if (!prefs.edit().putString(KEY_DEVICE_ID, id).commit()) Log.w(TAG, "could not save device_id");
            }
            return id;
        }
    }

    /** getInstance と同じクラスのロックを使わない (書き込みの間、他のスレッドの getInstance を待たせない)。 */
    private static final Object DEVICE_ID_LOCK = new Object();

    /**
     * 問い合わせ ID (SupportId)。無ければ (形が合わなければ) 作って保存する。設定の画面に出し、
     * 通知の登録・エラー報告・OTP 中継の連携で送る。起動直後は複数のスレッドから呼ばれるので、2 つ作らないように同期する。
     */
    public synchronized String getSupportId() {
        String id = prefs.getString(KEY_SUPPORT_ID, "");
        if (!SupportId.isValid(id)) {
            id = SupportId.generate();
            prefs.edit().putString(KEY_SUPPORT_ID, id).apply();
        }
        return id;
    }

    // 利用状況 (services/usage/UsageStats)。既定は ON。回数と ID は別のファイル (usage_stats) に置き、ここは設定だけ。
    public boolean isUsageStatsEnabled() { return prefs.getBoolean(KEY_USAGE_STATS_ENABLED, true); }
    public void setUsageStatsEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_USAGE_STATS_ENABLED, enabled).apply(); }
    @Nullable public String getOtpPageShapeSent() { return prefs.getString(KEY_OTP_PAGE_SHAPE_SENT, null); }
    public void setOtpPageShapeSent(@NonNull String mark) { prefs.edit().putString(KEY_OTP_PAGE_SHAPE_SENT, mark).apply(); }

    // Auto attendance
    public boolean isAutoAttendanceEnabled() { return prefs.getBoolean(KEY_AUTO_ATTENDANCE, false); }
    public void setAutoAttendanceEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_AUTO_ATTENDANCE, enabled).apply(); }

    // Notification timings (comma-separated keys like "prev_20,prev_10")
    public String getSelectedNotifyTimes() { return prefs.getString(KEY_SELECTED_NOTIFY_TIMES, "prev_20"); }
    public void setSelectedNotifyTimes(String times) { prefs.edit().putString(KEY_SELECTED_NOTIFY_TIMES, times).apply(); }

    // Custom notify times (JSON array)
    public String getCustomNotifyTimes() { return prefs.getString(KEY_CUSTOM_NOTIFY_TIMES, "[]"); }
    public void setCustomNotifyTimes(String json) { prefs.edit().putString(KEY_CUSTOM_NOTIFY_TIMES, json).apply(); }

    // Lunch notify time (HH:mm)
    public String getLunchNotifyTime() { return prefs.getString(KEY_LUNCH_NOTIFY_TIME, "11:30"); }
    public void setLunchNotifyTime(String time) { prefs.edit().putString(KEY_LUNCH_NOTIFY_TIME, time).apply(); }

    // Tab ordering (comma-separated tab names)
    public String getMainTabs() { return prefs.getString(KEY_MAIN_TABS, "home,calendar,courses,gakushoku"); }
    public void setMainTabs(String tabs) { prefs.edit().putString(KEY_MAIN_TABS, tabs).apply(); }

    public String getOtherTabs() { return prefs.getString(KEY_OTHER_TABS, "facilities,grades"); }
    public void setOtherTabs(String tabs) { prefs.edit().putString(KEY_OTHER_TABS, tabs).apply(); }

    // IdP (SECIOSS) login。iOS の SettingsStore.hasSeenIdPTutorial と同じ既定値。
    public boolean hasSeenIdPTutorial() { return prefs.getBoolean(KEY_HAS_SEEN_IDP_TUTORIAL, false); }
    public void setHasSeenIdPTutorial(boolean seen) { prefs.edit().putBoolean(KEY_HAS_SEEN_IDP_TUTORIAL, seen).apply(); }

    /**
     * OTP をどこから受け取るか ("mail" / "authenticator")。正は IdP 側の登録で、これはその控え (D-2)。
     * ログインのたびに OTP 入力画面の otp_type から書き直す (OtpProviders)。読めなかったときだけこの値を使う。
     * 認証アプリ方式では、登録のときにこのアプリにもシークレットを保存し、アプリがコードを作る (saveTotp)。
     * 未決定のときの既定はメール (大学が既定で設定する方式)。iOS の SettingsStore.otpMethod と同じ値。
     */
    public String getOtpMethod() { return prefs.getString(KEY_OTP_METHOD, "mail"); }

    /**
     * 受け取り方が決まっているか。新しく入れた端末、復元した端末 (DEVICE_LOCAL_KEYS)、アカウントを
     * 変えた端末では決まっておらず、次のログインで IdP の OTP 入力画面から読む (OtpProviders)。
     */
    public boolean isOtpMethodKnown() { return prefs.contains(KEY_OTP_METHOD); }

    /**
     * ログインで着いた OTP 入力画面の otp_type から分かった、IdP が実際に使っている方式を控えに書く (D-2)。
     * IdP 側の登録が正なので、控えと違えば書き換える (別の場所で登録し直した場合も次のログインで追従する)。
     * 書き換えたら true。
     */
    public synchronized boolean recordOtpMethodFromIdp(@NonNull OtpMethod method) {
        if (isOtpMethodKnown() && method.value.equals(getOtpMethod())) return false;
        prefs.edit().putString(KEY_OTP_METHOD, method.value).apply();
        return true;
    }
    /**
     * IdP への登録が完了した方式に揃える。呼ぶのは OtpRegistrationCommit (登録が済んだと分かったとき) と、
     * 認証アプリにある鍵を取り込んだとき (OtpKeyImport。鍵を保存できたあと) だけ。
     * 大学側 (IdP) に登録されている方式と揃っていないとログインできないので、設定画面の切り替えも復元も、
     * 値を直接書かない。ほかに書くのは、ログインで IdP の方式を読み取ったときの recordOtpMethodFromIdp だけ。
     */
    public synchronized void applyRegisteredOtpMethod(@NonNull OtpMethod method) {
        prefs.edit().putString(KEY_OTP_METHOD, method.value).apply();
    }

    /**
     * IdP 経由で得た CampusSquare のセッション cookie (SpSessionCookies の形式、属性込み)。iOS は Keychain に
     * 置いている。セッション自体が資格情報に相当するので暗号化側に入れる。空文字なら無し。
     * キー名は旧形式 ("name=value; ..." のヘッダ) の頃のまま。旧形式は SpSessionCookies が読み替える。
     */
    public String getCsIdpSessionCookies() { return getSecret(KEY_CS_IDP_SESSION_COOKIE_HEADER); }
    public void setCsIdpSessionCookies(String serialized) { putSecret(KEY_CS_IDP_SESSION_COOKIE_HEADER, serialized); }

    /**
     * IdP (slink.secioss.com) 自体の SSO セッション cookie ヘッダ。SP のセッションとは別物で、
     * これが生きている間は入口を叩くだけでパスワードも OTP も要求されずに SP へ戻れる。
     * SP のセッションと同じく資格情報に相当するので暗号化側へ入れる。
     */
    /**
     * Moodle (elms) のセッション cookie (SpSessionCookies の形式、属性込み)。起動のたびに IdP からログインし直す
     * (約 5 秒・OTP メール) のを避けるために持ち越す。CampusSquare 側と同じ扱い。キー名は旧形式の頃のまま。
     */
    public String getMoodleSessionCookies() { return getSecret(KEY_MOODLE_SESSION_COOKIE_HEADER); }
    public void setMoodleSessionCookies(String serialized) { putSecret(KEY_MOODLE_SESSION_COOKIE_HEADER, serialized); }

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

    /** 書き込みが確定したかを返す版。保存できなかったら連携をサーバ側から巻き戻すため。 */
    public boolean commitOtpRelayPairingsJson(String json) {
        if (encryptedPrefs == null) return false;
        SharedPreferences.Editor e = encryptedPrefs.edit();
        if (json == null || json.isEmpty()) e.remove(KEY_OTP_RELAY_PAIRINGS);
        else e.putString(KEY_OTP_RELAY_PAIRINGS, json);
        return e.commit();
    }

    /** PC 中継の直近の自動応答の結果 (JSON)。IMAP の応答行を含みうるので暗号化側に置く。 */
    public String getOtpRelayLastResultJson() { return getSecret(KEY_OTP_RELAY_LAST_RESULT); }
    public void setOtpRelayLastResultJson(String json) { putSecret(KEY_OTP_RELAY_LAST_RESULT, json); }

    /**
     * ログアウトで中継サーバからの解除を待っている連携 (JSON、K を含む)。解除が済むまで
     * clearAll でも消さない (OtpRelayUnpairWorker)。
     */
    public String getOtpRelayPendingUnpairsJson() { return getSecret(KEY_OTP_RELAY_PENDING_UNPAIRS); }

    public boolean commitOtpRelayPendingUnpairsJson(String json) {
        if (encryptedPrefs == null) return false;
        SharedPreferences.Editor e = encryptedPrefs.edit();
        if (json == null || json.isEmpty()) e.remove(KEY_OTP_RELAY_PENDING_UNPAIRS);
        else e.putString(KEY_OTP_RELAY_PENDING_UNPAIRS, json);
        return e.commit();
    }

    /**
     * 認証アプリ方式の otpauth URL を保存する (暗号化側だけ。バックアップ規則で暗号化側は除外している)。
     * IdP への登録が済んだときにだけ呼ぶ。保存できたかを返す。
     */
    public boolean saveTotp(@NonNull String otpauthUrl, @NonNull String owner) {
        if (encryptedPrefs == null) return false;
        // 使った窓の番号は周期の単位なので、周期が変わる鍵 (取り込んだ 60 秒の鍵など) では直す (iOS の TOTPSecretStore.save と同じ)。
        // 直さないと、新しい番号が前の番号よりずっと小さく、使用済みとみなされて自動でコードを作れない。
        com.shakenokirimi12.uoa_app.services.idp.Totp.Params before =
                com.shakenokirimi12.uoa_app.services.idp.Totp.parse(encryptedPrefs.getString(KEY_TOTP_OTPAUTH, null));
        com.shakenokirimi12.uoa_app.services.idp.Totp.Params after =
                com.shakenokirimi12.uoa_app.services.idp.Totp.parse(otpauthUrl);
        // 新しく保存した鍵は止めていない (登録し直しの成功で止めた状態を外す、D-5)。
        boolean saved = encryptedPrefs.edit()
                .putString(KEY_TOTP_OTPAUTH, otpauthUrl)
                .putString(KEY_TOTP_OWNER, owner.trim())
                .remove(KEY_TOTP_SUSPENDED)
                .commit();
        if (saved && after != null) {
            // 鍵が無ければ、otpauth の既定の周期。
            int previousPeriod = before != null ? before.periodSeconds() : 30;
            com.shakenokirimi12.uoa_app.services.idp.TotpStepLedger.shared().rescale(previousPeriod, after.periodSeconds());
        }
        return saved;
    }

    public long getTotpLastUsedStep() { return prefs.getLong(KEY_TOTP_LAST_USED_STEP, -1L); }
    /** commit で書く。中継とログインが続けて読むので、書き終えてから返す。 */
    public void setTotpLastUsedStep(long step) { prefs.edit().putLong(KEY_TOTP_LAST_USED_STEP, step).commit(); }

    // ---- バックグラウンドの再ログイン (BackgroundLoginPolicy) ----

    @NonNull public String getBackgroundLoginVerified() { return getSecret(KEY_BG_LOGIN_VERIFIED); }
    public void setBackgroundLoginVerified(@androidx.annotation.Nullable String fingerprint) {
        putSecret(KEY_BG_LOGIN_VERIFIED, fingerprint);
    }
    public boolean isBackgroundLoginBlocked() { return prefs.getBoolean(KEY_BG_LOGIN_BLOCKED, false); }
    /** commit で書く。止めたことはプロセスが終わっても残す必要がある (次の定期実行は別プロセスのことが多い)。 */
    public void setBackgroundLoginBlocked(boolean blocked) {
        prefs.edit().putBoolean(KEY_BG_LOGIN_BLOCKED, blocked).commit();
    }
    public long getBackgroundLoginLastMailDispatch() { return prefs.getLong(KEY_BG_LOGIN_LAST_MAIL_DISPATCH, 0L); }
    /** commit で書く。送信を頼む直前に書くので、送ったあとでプロセスが落ちても数え漏らさない。 */
    public void setBackgroundLoginLastMailDispatch(long at) {
        prefs.edit().putLong(KEY_BG_LOGIN_LAST_MAIL_DISPATCH, at).commit();
    }
    public long getBackgroundLoginLastAttempt(@NonNull String scope) {
        return prefs.getLong(KEY_BG_LOGIN_LAST_ATTEMPT_PREFIX + scope, 0L);
    }
    public void setBackgroundLoginLastAttempt(@NonNull String scope, long at) {
        prefs.edit().putLong(KEY_BG_LOGIN_LAST_ATTEMPT_PREFIX + scope, at).commit();
    }

    /** owner の otpauth URL。持っていない、または別のアカウントのものなら null。 */
    @androidx.annotation.Nullable
    public String totpOtpauthFor(@NonNull String owner) {
        if (encryptedPrefs == null) return null;
        String url = encryptedPrefs.getString(KEY_TOTP_OTPAUTH, "");
        String stored = encryptedPrefs.getString(KEY_TOTP_OWNER, "");
        if (url == null || url.isEmpty() || stored == null || !stored.equals(owner.trim())) return null;
        return url;
    }

    /**
     * owner の、自動で使ってよい otpauth URL。持っていない・別のアカウントのもの・止めているもの (D-5) なら null。
     * 前面の自動入力・バックグラウンドのログイン・PC への中継はこれを使う (止めた鍵は自動では使わない)。
     */
    @androidx.annotation.Nullable
    public String usableTotpOtpauthFor(@NonNull String owner) {
        String url = totpOtpauthFor(owner);
        return url != null && !isTotpSuspended(owner) ? url : null;
    }

    /** owner の鍵を持っていて、それを止めているか。 */
    public boolean isTotpSuspended(@NonNull String owner) {
        if (encryptedPrefs == null || totpOtpauthFor(owner) == null) return false;
        return encryptedPrefs.getBoolean(KEY_TOTP_SUSPENDED, false);
    }

    /**
     * 鍵で作ったコードが IdP に 2 回続けて弾かれた。鍵は消さずに、自動での使用を止める (D-5)。登録し直しの成功
     * (saveTotp) で外れる。owner の鍵でなければ何もしない。登録し直しの案内をもう一度出せるようにする。
     */
    public void suspendTotp(@NonNull String owner) {
        if (encryptedPrefs == null || totpOtpauthFor(owner) == null) return;
        encryptedPrefs.edit().putBoolean(KEY_TOTP_SUSPENDED, true).commit();
    }

    public void clearTotp() {
        if (encryptedPrefs == null) return;
        encryptedPrefs.edit().remove(KEY_TOTP_OTPAUTH).remove(KEY_TOTP_OWNER).remove(KEY_TOTP_SUSPENDED).apply();
    }

    // Reset
    public void clearAll() {
        // 「利用状況を送らない」はログアウトしても戻さない。消すと既定の ON に戻り、断った人から黙って送り始める。
        boolean usageStatsEnabled = isUsageStatsEnabled();
        SharedPreferences.Editor plain = prefs.edit().clear();
        if (!usageStatsEnabled) plain.putBoolean(KEY_USAGE_STATS_ENABLED, false);
        plain.apply();
        if (encryptedPrefs == null) return;
        // 解除待ちの連携だけは残す。消すと、中継サーバに残った連携を二度と解除できなくなる。
        String pendingUnpairs = encryptedPrefs.getString(KEY_OTP_RELAY_PENDING_UNPAIRS, "");
        SharedPreferences.Editor e = encryptedPrefs.edit().clear();
        if (pendingUnpairs != null && !pendingUnpairs.isEmpty()) e.putString(KEY_OTP_RELAY_PENDING_UNPAIRS, pendingUnpairs);
        e.apply();
    }
}
