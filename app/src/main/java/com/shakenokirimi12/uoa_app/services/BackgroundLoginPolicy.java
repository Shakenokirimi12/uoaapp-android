package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import com.shakenokirimi12.uoa_app.services.idp.OtpProviders;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;
import com.shakenokirimi12.uoa_app.services.idp.SeciossSsoStore;
import com.shakenokirimi12.uoa_app.services.notification.AuthNotices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ログインの規則のうち、資格情報とバックグラウンド (SyncWorker) の再ログインに関わるもの
 * (SPEC.md「6. バックグラウンドログイン」「7.1 ID とパスワードの誤り」。iOS の BackgroundLoginPolicy と同じ)。
 *
 * 資格情報の誤り (前面・バックグラウンド共通):
 * - ID / パスワードの誤りと分かったら、その資格情報の指紋に印を付ける。ID かパスワードが変わるまで、
 *   どの文脈でもログインしない (アカウントのロックを避ける)。通知は同じ指紋で 1 回だけ。
 *
 * バックグラウンドの新規ログインを始めてよいのは、次の全部を満たすときだけ (保存済みセッションの probe が
 * ログアウトしていたあと。SessionCoordinator):
 * - 止めた印 (前のバックグラウンドのログインの失敗) が立っていない
 * - 資格情報の誤りの印が、今の資格情報に立っていない
 * - 控えの方式が分かっている (OtpProviders.unattendedMethod)
 * - 認証アプリ方式: 止めていない鍵をこのアプリが持ち、ID / パスワードが最後に成功したときと同じで、
 *   同じサービスで直前の試行から MIN_INTERVAL_MS 以上たっている
 * - メール方式: 同じサービスで直前の試行から MIN_INTERVAL_MS 以上たち、端末全体の 6 時間の枠
 *   (MAIL_DISPATCH_INTERVAL_MS) が空いているか、IdP の SSO を持っている (1 本目の SSO で 2 本目が入る)。
 *   OTP メールの送信を頼む直前に、もう一度 6 時間の枠を取る (claimMailDispatch)。取れなければ送らない
 *
 * バックグラウンドのログインが失敗したら、どの失敗でも止めた印を立てる。外すのは前面でのログイン成功と、
 * 認証アプリの登録 (し直しを含む) の成功だけ。アプリ内の事情 (INTERACTIVE_LOGIN_REQUIRED、使える TOTP の窓が
 * 来なかった) は失敗に数えない。
 */
public final class BackgroundLoginPolicy {
    /**
     * 同じサービスのバックグラウンドの新規ログインの最短間隔。WorkManager は最短 15 分おきに起こす。30 分にすると、
     * セッションが毎回切れても大学側へのログインは 1 サービスあたり 1 時間に 2 回 (1 日 48 回) までに収まる。
     * 失敗したら止めた印で止まるので、これは成功し続ける場合の上限。iOS と同じ値。
     */
    public static final long MIN_INTERVAL_MS = 30 * 60 * 1000L;

    /** メール方式のバックグラウンドのログインで、OTP メールの送信を頼む間隔の下限 (端末全体)。 */
    public static final long MAIL_DISPATCH_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    /** 保存先。テストで差し替える。 */
    interface Store {
        @NonNull String verifiedFingerprint();
        void setVerifiedFingerprint(@NonNull String fingerprint);
        boolean isBlocked();
        void setBlocked(boolean blocked);
        long lastAttempt(@NonNull String scope);
        void setLastAttempt(@NonNull String scope, long at);
        long lastMailDispatch();
        /** 送信を頼む直前に書く。書き終えてから返す (送ってから落ちても数え漏らさない)。 */
        void setLastMailDispatch(long at);
        boolean isCredentialsInvalid(@NonNull String uid, @NonNull String pass);
        /** 誤りの印を付ける。この指紋をまだ知らせていなければ true。 */
        boolean markCredentialsInvalid(@NonNull String uid, @NonNull String pass);
        void markCredentialsInvalidNotified(@NonNull String uid, @NonNull String pass);
    }

    /** アカウントの今の状態。テストで差し替える。 */
    interface Accounts {
        /** バックグラウンドで使う方式 (OtpProviders.unattendedMethod)。人の操作なしに用意できなければ null。 */
        @Nullable OtpMethod unattendedMethod(@NonNull String uid);
        /** uid の IdP の SSO を持っているか。 */
        boolean holdsIdpSso(@NonNull String uid);
    }

    /** 通知。テストで差し替える。 */
    interface Notices {
        void mailDispatch();
        void credentialsInvalid();
    }

    /** 時計。テストで差し替える。 */
    interface Clock { long now(); }

    private static BackgroundLoginPolicy shared;

    private final Store store;
    private final Accounts accounts;
    private final Notices notices;
    private final Clock clock;

    BackgroundLoginPolicy(@NonNull Store store, @NonNull Accounts accounts, @NonNull Notices notices,
                          @NonNull Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.notices = notices;
        this.clock = clock;
    }

    @NonNull
    public static synchronized BackgroundLoginPolicy getInstance() {
        if (shared == null) {
            shared = new BackgroundLoginPolicy(new PreferenceStore(),
                    new Accounts() {
                        @Nullable @Override public OtpMethod unattendedMethod(@NonNull String uid) {
                            return OtpProviders.unattendedMethod(uid);
                        }
                        @Override public boolean holdsIdpSso(@NonNull String uid) {
                            return SeciossSsoStore.getInstance().holdsFor(uid);
                        }
                    },
                    new Notices() {
                        @Override public void mailDispatch() { AuthNotices.postMailDispatch(); }
                        @Override public void credentialsInvalid() { AuthNotices.postCredentialsInvalid(); }
                    },
                    System::currentTimeMillis);
        }
        return shared;
    }

    /** 前のバックグラウンドのログインが失敗して、止めている。 */
    public boolean isBlocked() { return store.isBlocked(); }

    /**
     * scope (サービス) の、この ID / パスワードでのログインの判定。ログインに使うのと同じ値を渡す
     * (SessionCoordinator はこの値でログインする login に、この判定を組にして渡される)。
     */
    @NonNull
    SessionCoordinator.Unattended forAccount(@NonNull String scope, @Nullable String uid, @Nullable String pass) {
        // ログインは前後の空白を落として送る (CampusSquareService / MoodleService)。呼び出し元によって落としてから
        // 渡すことがあるので、ここでも落として揃える。
        String u = uid != null ? uid.trim() : "";
        String p = pass != null ? pass.trim() : "";
        return new SessionCoordinator.Unattended() {
            @Override public boolean credentialsInvalid() { return BackgroundLoginPolicy.this.credentialsInvalid(u, p); }
            @Override public boolean permits() { return BackgroundLoginPolicy.this.permits(u, p); }
            @Override public boolean claimSlot() { return BackgroundLoginPolicy.this.claimSlot(scope, u); }
            @Override public void succeeded() { BackgroundLoginPolicy.this.succeeded(u, p); }
            @Override public void failed(@NonNull Exception e, boolean background) {
                BackgroundLoginPolicy.this.failed(u, p, e, background);
            }
        };
    }

    /** uid / pass が誤りと分かっている (指紋が変わるまでログインしない)。 */
    boolean credentialsInvalid(@NonNull String uid, @NonNull String pass) {
        return !uid.isEmpty() && !pass.isEmpty() && store.isCredentialsInvalid(uid, pass);
    }

    /** 間隔の制限は含まない。 */
    boolean permits(@NonNull String uid, @NonNull String pass) {
        if (uid.isEmpty() || pass.isEmpty()) return false;
        OtpMethod method = accounts.unattendedMethod(uid);
        if (method == null) return false;
        synchronized (this) {
            if (store.isBlocked()) return false;
            if (store.isCredentialsInvalid(uid, pass)) return false;
            // 認証アプリ方式は、変えたばかりのパスワードを裏で試さない (前面で一度成功してから)。
            if (method == OtpMethod.AUTHENTICATOR && !store.verifiedFingerprint().equals(fingerprint(uid, pass))) {
                return false;
            }
        }
        return true;
    }

    /** 始めてよければ試行として記録して true。 */
    synchronized boolean claimSlot(@NonNull String scope, @NonNull String uid) {
        if (store.isBlocked()) return false;
        long now = clock.now();
        if (!elapsed(store.lastAttempt(scope), now, MIN_INTERVAL_MS)) return false;
        if (accounts.unattendedMethod(uid) == OtpMethod.MAIL
                && !elapsed(store.lastMailDispatch(), now, MAIL_DISPATCH_INTERVAL_MS)
                && !accounts.holdsIdpSso(uid)) {
            // OTP メールを送れない間は、IdP にパスワードを送ることもしない。
            return false;
        }
        store.setLastAttempt(scope, now);
        return true;
    }

    /**
     * バックグラウンドのメール方式で、OTP メールの送信を頼む直前に呼ぶ (OtpProvider.beforeMailDispatch)。
     * 6 時間の枠が空いていれば、時刻を書いてから通知を 1 回出して返す。空いていなければ送らずに
     * INTERACTIVE_LOGIN_REQUIRED (アプリ内の事情なので止めた印は立てない)。
     */
    public void claimMailDispatch() throws SeciossError {
        synchronized (this) {
            if (store.isBlocked()) {
                throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, "background login blocked");
            }
            long now = clock.now();
            if (!elapsed(store.lastMailDispatch(), now, MAIL_DISPATCH_INTERVAL_MS)) {
                throw new SeciossError(SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED, "mail dispatch window in use");
            }
            store.setLastMailDispatch(now);
        }
        notices.mailDispatch();
    }

    /** last から interval たったか。時計が戻ったとき (差が負) も、記録した時刻から interval たつまでは待つ。 */
    private static boolean elapsed(long last, long now, long interval) {
        long d = now - last;
        return last <= 0 || d >= interval || d <= -interval;
    }

    /** ログインが成功した (文脈を問わない)。前面の成功で、止めていたバックグラウンドの再ログインを戻す。 */
    synchronized void succeeded(@NonNull String uid, @NonNull String pass) {
        if (!uid.isEmpty() && !pass.isEmpty()) store.setVerifiedFingerprint(fingerprint(uid, pass));
        store.setBlocked(false);
    }

    /** 認証アプリの登録 (し直しを含む) が成功した。止めた印を外す (SPEC.md「6. バックグラウンドログイン」)。 */
    public synchronized void registrationSucceeded() {
        store.setBlocked(false);
    }

    /**
     * ログインが失敗した。ID / パスワードの誤りなら、どの文脈でも誤りの印を付ける。通知はバックグラウンドで分かった
     * ときだけ、同じ指紋で 1 回 (前面では画面に出ているので、知らせたものとして数える)。
     * バックグラウンドなら、アプリ内の事情を除いて止めた印を立てる。
     */
    void failed(@NonNull String uid, @NonNull String pass, @NonNull Exception e, boolean background) {
        boolean notify = false;
        synchronized (this) {
            if (isInvalidCredentials(e) && !uid.isEmpty() && !pass.isEmpty()) {
                if (store.markCredentialsInvalid(uid, pass)) {
                    store.markCredentialsInvalidNotified(uid, pass);
                    notify = background;
                }
            }
            if (background && !isAppInternal(e)) store.setBlocked(true);
        }
        if (notify) notices.credentialsInvalid();
    }

    /**
     * 画面 (onError) で ID / パスワードの誤りを受け取ったときの印 (前面なので通知は出さない)。
     * 普通は SessionCoordinator が先に付けている。
     */
    public void markInvalid(@NonNull String uid, @NonNull String pass) {
        String u = uid.trim();
        String p = pass.trim();
        if (u.isEmpty() || p.isEmpty()) return;
        synchronized (this) {
            if (store.markCredentialsInvalid(u, p)) store.markCredentialsInvalidNotified(u, p);
        }
    }

    static boolean isInvalidCredentials(@NonNull Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (AuthErrors.isInvalidCredentials(t.getMessage())) return true;
            if (t instanceof SeciossError && ((SeciossError) t).kind == SeciossError.Kind.INVALID_CREDENTIALS) return true;
        }
        return false;
    }

    /**
     * アプリ内の事情で続けなかった (失敗ではない)。サービスは SeciossError を原因に包んで投げる (asLoginException) ので、
     * 原因までたどる。
     */
    static boolean isAppInternal(@NonNull Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (!(t instanceof SeciossError)) continue;
            SeciossError.Kind kind = ((SeciossError) t).kind;
            return kind == SeciossError.Kind.INTERACTIVE_LOGIN_REQUIRED || kind == SeciossError.Kind.TOTP_STEP_UNAVAILABLE;
        }
        return false;
    }

    /** 資格情報の指紋。生の値は残さない。PreferenceManager の誤りの印も同じ値を使う。 */
    @NonNull
    public static String fingerprint(@NonNull String uid, @NonNull String pass) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(("uoa-app.background-login.v1\n" + uid + "\n" + pass).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 指紋は暗号化側、止めているかと試行の時刻は通常の側 (秘密ではない) に置く。 */
    private static final class PreferenceStore implements Store {
        private PreferenceManager prefs() { return PreferenceManager.getInstance(); }
        @NonNull @Override public String verifiedFingerprint() { return prefs().getBackgroundLoginVerified(); }
        @Override public void setVerifiedFingerprint(@NonNull String fingerprint) { prefs().setBackgroundLoginVerified(fingerprint); }
        @Override public boolean isBlocked() { return prefs().isBackgroundLoginBlocked(); }
        @Override public void setBlocked(boolean blocked) { prefs().setBackgroundLoginBlocked(blocked); }
        @Override public long lastAttempt(@NonNull String scope) { return prefs().getBackgroundLoginLastAttempt(scope); }
        @Override public void setLastAttempt(@NonNull String scope, long at) { prefs().setBackgroundLoginLastAttempt(scope, at); }
        @Override public long lastMailDispatch() { return prefs().getBackgroundLoginLastMailDispatch(); }
        @Override public void setLastMailDispatch(long at) { prefs().setBackgroundLoginLastMailDispatch(at); }
        @Override public boolean isCredentialsInvalid(@NonNull String uid, @NonNull String pass) {
            return prefs().isCredentialsInvalid(uid, pass);
        }
        @Override public boolean markCredentialsInvalid(@NonNull String uid, @NonNull String pass) {
            return prefs().markCredentialsInvalid(uid, pass);
        }
        @Override public void markCredentialsInvalidNotified(@NonNull String uid, @NonNull String pass) {
            prefs().markCredentialsInvalidNotified(uid, pass);
        }
    }
}
