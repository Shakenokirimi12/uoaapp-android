package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import okhttp3.Cookie;

/**
 * IdP (slink.secioss.com) 自身の SSO セッション cookie を保持する。iOS の SeciossSSOStore の移植。
 *
 * これが生きていれば、入口 URL を叩いた時点で IdP がログインフォームではなく SAMLResponse の
 * 自動 submit を返すため、パスワードも OTP も要求されずに SP へ戻れる。持たずに毎回パスワードから
 * やり直すと、同期のたびに大学のメールサーバーへ OTP メールが飛ぶ。
 *
 * 保持の仕方はブラウザと同じにする (2026-09-16 の iOS 実機ログでの切り分け):
 * <ul>
 *   <li>メモリ上 (同一プロセス内) は IdP が出した cookie を属性込み (path / expires) で全部持つ。
 *       CampusSquare→Moodle の OTP 二重回避はこれで足りる。</li>
 *   <li>永続化 (EncryptedSharedPreferences) には Expires/Max-Age 付きで未失効のものだけ書く。
 *       セッション cookie (Expires なし。SimpleSAMLSessionID がそう) はブラウザを閉じれば消えるものなので、
 *       日を跨いで再生してはいけない。旧版はこれを名前=値だけで無期限に保存していたため、IdP 側で失効した
 *       samsessid / auth_tkt (mod_auth_tkt のチケットで IP に紐付く) を送り続け、「ログイン済み」と
 *       「SAML セッション無し」の判定が噛み合わず IdP 内で 307→302→302 が延々ピンポンして
 *       too many redirects になっていた。</li>
 *   <li>読み出し時に期限切れを捨てる。旧形式 (名前=値、期限情報なし) は期限を判定できないので
 *       読まずに破棄する (= 汚染端末の自己修復)。</li>
 *   <li>セッション cookie はメモリ上でも受信から {@link #SESSION_COOKIE_LIFETIME_MS} で捨てる。
 *       アプリのプロセスは日を跨いで生き続けることがあり、ブラウザの「閉じたら消える」に相当する区切りが
 *       無いため。1 回の同期 (CampusSquare→Moodle) は数分で終わるので SSO の再利用は保てる。</li>
 * </ul>
 *
 * slink.secioss.com の応答で受け取った cookie のうち、slink.secioss.com に送られうるものだけを入れる。
 * SP (csweb/elms) の cookie を混ぜると、次回 IdP へ無関係なセッションを送ることになる。
 * ただし SSO チケット auth_tkt はパスワード POST (tenantlogin.cgi) の応答で {@code Domain=secioss.com} 付きで
 * 出てくる。以前は domain が slink.secioss.com と完全一致するものだけを入れていたので auth_tkt を落とし、
 * 2 つ目の SP (Moodle の後の CampusSquare) へのログインで毎回 OTP を求められていた (iOS で判明)。
 * 親ドメイン宛ての cookie は、その Domain のまま持って再生する。
 *
 * SSO セッションは持ち主 (uid) を記録する。別アカウントのチケットを載せると、IdP はパスワードを見ずに
 * 前のアカウントとして SP へ通してしまう。{@link #claimFor(String)} が持ち主の違うものを捨てる。
 * アカウント切り替え ({@link PreferenceManager#setUsername}) は {@link #clear()} でメモリ上の分も消す。
 */
public final class SeciossSsoStore {
    static final String IDP_HOST = "slink.secioss.com";

    /** 保存先。テストではメモリ上の実装に差し替える。 */
    interface Persistence {
        @Nullable String loadCookies();
        void saveCookies(@Nullable String serialized);
        @Nullable String loadOwner();
        void saveOwner(@Nullable String owner);
    }

    /** EncryptedSharedPreferences (PreferenceManager) に置く。 */
    private static final class PreferencePersistence implements Persistence {
        @Override public String loadCookies() { return PreferenceManager.getInstance().getIdpSsoCookieHeader(); }
        @Override public void saveCookies(String serialized) { PreferenceManager.getInstance().setIdpSsoCookieHeader(serialized); }
        @Override public String loadOwner() { return PreferenceManager.getInstance().getIdpSsoOwner(); }
        @Override public void saveOwner(String owner) { PreferenceManager.getInstance().setIdpSsoOwner(owner); }
    }
    static final long SESSION_COOKIE_LIFETIME_MS = 60L * 60 * 1000;
    /**
     * 永続化形式: 1 行 1 cookie、name TAB value TAB path TAB expiresAtMillis TAB domain。cookie 値にタブは現れない。
     * domain は親ドメイン宛て (Domain=secioss.com) のときだけ入り、host-only (slink) なら空。
     * domain の欄が無い 4 欄の行 (この形式の前の版) は host-only として読む。
     */
    private static final String FIELD_SEP = "\t";
    private static final String LINE_SEP = "\n";

    /** 参照時に初めて作る。static 初期化で PreferenceManager に触ると純粋な parse まで Android 依存になる。 */
    private static final class Holder {
        static final SeciossSsoStore INSTANCE = new SeciossSsoStore(System::currentTimeMillis, new PreferencePersistence());
    }

    public static SeciossSsoStore getInstance() { return Holder.INSTANCE; }

    /** 属性込みの 1 cookie。expiresAt < 0 はセッション cookie (プロセス終了で消える)。 */
    static final class StoredCookie {
        final String name;
        final String value;
        final String path;
        final long expiresAt;
        final long receivedAt;
        /** 親ドメイン宛て (例 "secioss.com") のときだけ。null は slink.secioss.com の host-only。 */
        @Nullable final String parentDomain;

        StoredCookie(@NonNull String name, @NonNull String value, @NonNull String path, long expiresAt, long receivedAt) {
            this(name, value, path, expiresAt, receivedAt, null);
        }

        StoredCookie(@NonNull String name, @NonNull String value, @NonNull String path, long expiresAt, long receivedAt,
                     @Nullable String parentDomain) {
            this.name = name;
            this.value = value;
            this.path = path;
            this.expiresAt = expiresAt;
            this.receivedAt = receivedAt;
            this.parentDomain = parentDomain;
        }

        boolean sameKey(@NonNull String otherName, @NonNull String otherPath, @Nullable String otherDomain) {
            return name.equals(otherName) && path.equals(otherPath) && java.util.Objects.equals(parentDomain, otherDomain);
        }

        boolean isPersistent() { return expiresAt >= 0; }
    }

    private final Object lock = new Object();
    private final LongSupplier clock;
    private final Persistence persistence;
    /** null = まだ永続化領域から読んでいない。 */
    @Nullable
    private List<StoredCookie> memory;

    SeciossSsoStore(@NonNull LongSupplier clock, @NonNull Persistence persistence) {
        this.clock = clock;
        this.persistence = persistence;
    }

    /**
     * これから uid として IdP に入る。持ち主が記録されていて uid と違えば、手持ちの SSO を捨てる。
     * そのうえで、ここから先に受け取る分は uid のものとして記録する。
     * 持ち主が記録されていない (この版より前に保存された) SSO は、無効とは限らないので受け入れる
     * (iOS の SessionStore.cookieHeader(owner:) と同じ。捨てると更新直後の全員に OTP メールが飛ぶ)。
     */
    public void claimFor(@NonNull String uid) {
        synchronized (lock) {
            String owner = persistence.loadOwner();
            if (owner != null && !owner.isEmpty() && !owner.equals(uid)) {
                memory = new ArrayList<>();
                persistence.saveCookies(null);
            }
            persistence.saveOwner(uid);
        }
    }

    /**
     * uid の (または持ち主が記録されていない) 失効していない SSO cookie を持っているか。バックグラウンドの
     * メール方式で、2 本目のサービスが 1 本目の SSO で入れるかの見込みに使う (BackgroundLoginPolicy)。
     */
    public boolean holdsFor(@NonNull String uid) {
        synchronized (lock) {
            String owner = persistence.loadOwner();
            if (owner != null && !owner.isEmpty() && !owner.equals(uid)) return false;
        }
        return !current().isEmpty();
    }

    /** IdP へ seed する cookie (発行元と同じ host-only 表現)。失効分はここで落ちる。 */
    @NonNull
    public List<Cookie> current() {
        synchronized (lock) {
            List<StoredCookie> live = dropExpired(loadLocked(), clock.getAsLong());
            memory = live;
            return toCookies(live);
        }
    }

    /**
     * 同名 (name, path) は置き換え。削除指示 (期限が現在以前) は消す。
     * SAML が 1 リクエストごとに使い捨てる状態 cookie (_opensaml_req_ss:mem:<毎回別のID>、
     * _redirection_state) は同一プロセス内でも再生してはいけない (次の SAMLRequest とは無関係な古い状態を
     * IdP へ送ることになる) ので、メモリにも入れない。
     */
    public void merge(@NonNull String fromHost, @NonNull List<Cookie> received) {
        // IdP の応答で受け取ったものだけ。jar 全体や他ホストの応答から拾わない。
        if (received.isEmpty() || !IDP_HOST.equalsIgnoreCase(fromHost)) return;
        synchronized (lock) {
            long now = clock.getAsLong();
            List<StoredCookie> cookies = new ArrayList<>(loadLocked());
            for (Cookie c : received) {
                if (!sentToIdp(c)) continue;
                String parent = parentDomainOf(c);
                cookies.removeIf(s -> s.sameKey(c.name(), c.path(), parent));
                // 削除指示 (期限が現在以前) は消すだけ。
                if (SeciossIdPClient.isExpired(c, now)) continue;
                cookies.add(new StoredCookie(c.name(), c.value(), c.path(),
                        c.persistent() ? c.expiresAt() : -1, now, parent));
            }
            cookies = persistable(cookies, now);
            memory = cookies;
            persistence.saveCookies(serializePersistent(cookies));
        }
    }

    /** slink.secioss.com に送られる cookie か (host-only の slink、または slink をドメイン一致で含む Domain)。 */
    static boolean sentToIdp(@NonNull Cookie c) {
        String domain = c.domain().toLowerCase(java.util.Locale.ROOT);
        if (domain.equals(IDP_HOST)) return true;
        return !c.hostOnly() && IDP_HOST.endsWith("." + domain);
    }

    /** 親ドメイン宛てならその Domain、slink 自身宛て (host-only / Domain=slink.secioss.com) なら null。 */
    @Nullable
    static String parentDomainOf(@NonNull Cookie c) {
        String domain = c.domain().toLowerCase(java.util.Locale.ROOT);
        return c.hostOnly() || domain.equals(IDP_HOST) ? null : domain;
    }

    /** ログアウト時・資格情報を入れ直したとき・アカウント切り替え時。 */
    public void clear() {
        synchronized (lock) {
            memory = new ArrayList<>();
            persistence.saveCookies(null);
            persistence.saveOwner(null);
        }
    }

    @NonNull
    private List<StoredCookie> loadLocked() {
        if (memory == null) {
            memory = load(persistence.loadCookies(), clock.getAsLong());
        }
        return memory;
    }

    /** 永続化文字列から読む。失効分と旧形式 (タブ区切りでない) は落ちる。 */
    @NonNull
    static List<StoredCookie> load(@Nullable String stored, long now) {
        List<StoredCookie> result = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return result;
        for (String line : stored.split(LINE_SEP)) {
            String[] f = line.split(FIELD_SEP, -1);
            // 旧形式 "name=value; ..." → 期限が分からないので持ち越さない
            if (f.length != 4 && f.length != 5) return new ArrayList<>();
            long expiresAt;
            try {
                expiresAt = Long.parseLong(f[3]);
            } catch (NumberFormatException e) {
                return new ArrayList<>();
            }
            // 永続化されるのは期限付きだけなので receivedAt は判定に使わないが、形式は揃えておく。
            String parent = f.length == 5 && !f[4].isEmpty() ? f[4] : null;
            result.add(new StoredCookie(f[0], f[1], f[2], expiresAt, now, parent));
        }
        return persistable(result, now);
    }

    /** 永続化してよいのは期限付きだけ。無ければ null (保存領域を空にする)。 */
    @Nullable
    static String serializePersistent(@NonNull List<StoredCookie> cookies) {
        StringBuilder sb = new StringBuilder();
        for (StoredCookie c : cookies) {
            if (!c.isPersistent()) continue;
            if (sb.length() > 0) sb.append(LINE_SEP);
            sb.append(c.name).append(FIELD_SEP).append(c.value).append(FIELD_SEP).append(c.path)
                    .append(FIELD_SEP).append(c.expiresAt)
                    .append(FIELD_SEP).append(c.parentDomain != null ? c.parentDomain : "");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 発行元と同じ表現で作り直す。slink.secioss.com の cookie はほとんどが Domain 属性なし = host-only
     * (2026-09-07 の実機 HAR)、auth_tkt は Domain=secioss.com。表現を変えると mergeCookies の
     * (name, domain, path) 一致が崩れ、再ログイン時に古い seed が消えず同名 cookie を二重送信して
     * ALB スティッキーをまた壊す。
     */
    @NonNull
    static List<Cookie> toCookies(@NonNull List<StoredCookie> stored) {
        List<Cookie> out = new ArrayList<>();
        for (StoredCookie c : stored) {
            Cookie.Builder b = new Cookie.Builder().name(c.name).value(c.value).path(c.path);
            if (c.parentDomain != null) b.domain(c.parentDomain); else b.hostOnlyDomain(IDP_HOST);
            if (c.isPersistent()) b.expiresAt(c.expiresAt);
            out.add(b.build());
        }
        return out;
    }

    /** 持ってよい cookie だけ残す: 使い捨て状態 cookie でなく、値が空 (削除済み) でなく、未失効。 */
    @NonNull
    static List<StoredCookie> persistable(@NonNull List<StoredCookie> cookies, long now) {
        List<StoredCookie> result = new ArrayList<>();
        for (StoredCookie c : dropExpired(cookies, now)) {
            if (c.value.isEmpty()) continue;
            if (c.name.startsWith("_opensaml_req_ss:")) continue;
            if (c.name.equals("_redirection_state")) continue;
            result.add(c);
        }
        return result;
    }

    @NonNull
    private static List<StoredCookie> dropExpired(@NonNull List<StoredCookie> cookies, long now) {
        List<StoredCookie> result = new ArrayList<>();
        for (StoredCookie c : cookies) {
            if (c.isPersistent() ? c.expiresAt <= now : c.receivedAt + SESSION_COOKIE_LIFETIME_MS <= now) continue;
            result.add(c);
        }
        return result;
    }
}
