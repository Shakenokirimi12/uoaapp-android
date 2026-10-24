package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.OtpProviders;
import com.shakenokirimi12.uoa_app.services.idp.SeciossError;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * バックグラウンド (SyncWorker) の再ログインの規則 (iOS の BackgroundLoginPolicy と同じ)。
 *
 * 始めてよいのは次の全部を満たすときだけ:
 * - OTP をバックグラウンドの文脈で用意できる (OtpProvider.canRunUnattended)。メール方式は常に不可
 *   (ログイン 1 回 = OTP メール 1 通、2026-09-25 の大量発生)。認証アプリ方式はこのアプリが鍵を持つときだけ可。
 * - ID / パスワードが、最後にログインに成功したときと同じ (変えたばかりのパスワードを裏で試さない)。
 * - 前のバックグラウンドのログインで、ID / パスワードの誤り・作ったコードを弾かれた・想定外の IdP の画面が
 *   起きていない。起きたら、前面でログインに成功するまで止める (保存する)。それ以外の失敗は間隔の 1 回として数えるだけ。
 * - 同じサービスで直前の試行から MIN_INTERVAL_MS 以上たっている (保存する)。
 */
public final class BackgroundLoginPolicy {
    /**
     * 同じサービスのバックグラウンドの新規ログインの最短間隔。WorkManager は最短 15 分おきに起こす。30 分にすると、
     * セッションが毎回切れても大学側へのログインは 1 サービスあたり 1 時間に 2 回 (1 日 48 回) までに収まる。
     * 失敗したら即座に止まるので、これは成功し続ける場合の上限。iOS と同じ値。
     */
    public static final long MIN_INTERVAL_MS = 30 * 60 * 1000L;

    /** 保存先。テストで差し替える。 */
    interface Store {
        @NonNull String verifiedFingerprint();
        void setVerifiedFingerprint(@NonNull String fingerprint);
        boolean isBlocked();
        void setBlocked(boolean blocked);
        long lastAttempt(@NonNull String scope);
        void setLastAttempt(@NonNull String scope, long at);
    }

    /** バックグラウンドの文脈で、人もメールも使わずにコードを用意できるか。 */
    interface Capability { boolean canRunUnattended(@NonNull String uid, @NonNull String pass); }

    /** 時計。テストで差し替える。 */
    interface Clock { long now(); }

    private static BackgroundLoginPolicy shared;

    private final Store store;
    private final Capability capability;
    private final Clock clock;

    BackgroundLoginPolicy(@NonNull Store store, @NonNull Capability capability, @NonNull Clock clock) {
        this.store = store;
        this.capability = capability;
        this.clock = clock;
    }

    @NonNull
    public static synchronized BackgroundLoginPolicy getInstance() {
        if (shared == null) {
            shared = new BackgroundLoginPolicy(new PreferenceStore(), OtpProviders::canRunUnattended,
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
            @Override public boolean permits() { return BackgroundLoginPolicy.this.permits(u, p); }
            @Override public boolean claimSlot() { return BackgroundLoginPolicy.this.claimSlot(scope); }
            @Override public void succeeded() { BackgroundLoginPolicy.this.succeeded(u, p); }
            @Override public void failed(@NonNull Exception e) { BackgroundLoginPolicy.this.unattendedFailed(e); }
        };
    }

    /** 間隔の制限は含まない。 */
    boolean permits(@NonNull String uid, @NonNull String pass) {
        if (uid.isEmpty() || pass.isEmpty()) return false;
        synchronized (this) {
            if (store.isBlocked()) return false;
            if (!store.verifiedFingerprint().equals(fingerprint(uid, pass))) return false;
        }
        return capability.canRunUnattended(uid, pass);
    }

    /** 始めてよければ試行として記録して true。 */
    synchronized boolean claimSlot(@NonNull String scope) {
        if (store.isBlocked()) return false;
        long now = clock.now();
        long last = store.lastAttempt(scope);
        long elapsed = now - last;
        // 時計が戻ったとき (elapsed < 0) も、記録した時刻から MIN_INTERVAL_MS たつまでは待つ。
        if (last > 0 && elapsed < MIN_INTERVAL_MS && elapsed > -MIN_INTERVAL_MS) return false;
        store.setLastAttempt(scope, now);
        return true;
    }

    /** ログインが成功した (文脈を問わない)。前面の成功で、止めていたバックグラウンドの再ログインを戻す。 */
    synchronized void succeeded(@NonNull String uid, @NonNull String pass) {
        if (!uid.isEmpty() && !pass.isEmpty()) store.setVerifiedFingerprint(fingerprint(uid, pass));
        store.setBlocked(false);
    }

    /**
     * バックグラウンドのログインが失敗した。前面のログインが通るまで止めるのは、アカウントのロックにつながるか、
     * キーやパスワードが悪いと分かる失敗だけ (stopsUnattendedLogins)。メンテナンス・サーバーの 5xx・途中での
     * セッション切れ・通信の失敗は、30 分の間隔の 1 回として数えるだけにする (夜間のメンテナンスで止まったまま、
     * 利用者が開くまで戻らない、を避ける)。
     */
    synchronized void unattendedFailed(@NonNull Exception e) {
        if (stopsUnattendedLogins(e)) store.setBlocked(true);
    }

    /**
     * ID / パスワードの誤り、作ったコードを IdP に弾かれた、想定外の IdP の画面 (読めない・項目が無い・4xx)。
     * サービスは SeciossError を原因に包んで投げる (asLoginException) ので、原因までたどる。
     */
    static boolean stopsUnattendedLogins(@NonNull Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (AuthErrors.isInvalidCredentials(t.getMessage())) return true;
            if (!(t instanceof SeciossError)) continue;
            SeciossError s = (SeciossError) t;
            switch (s.kind) {
                case INVALID_CREDENTIALS:
                case INVALID_OTP:
                case UNRECOGNIZED_STATE:
                case MISSING_FIELD:
                    return true;
                case UNEXPECTED_STATUS:
                    return s.status >= 400 && s.status < 500;
                default:
                    return false;
            }
        }
        return false;
    }

    @NonNull
    static String fingerprint(@NonNull String uid, @NonNull String pass) {
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
    }
}
