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
 * slink.secioss.com が発行する cookie だけを入れる。SP (csweb/elms) の cookie を混ぜると、次回 IdP へ
 * 無関係なセッションを送ることになる。アカウント切り替え ({@link PreferenceManager#setUsername}) は
 * {@link #clear()} を呼んでメモリ上のセッション cookie も消す。
 */
public final class SeciossSsoStore {
    static final String IDP_HOST = "slink.secioss.com";
    static final long SESSION_COOKIE_LIFETIME_MS = 60L * 60 * 1000;
    /** 永続化形式: 1 行 1 cookie、name TAB value TAB path TAB expiresAtMillis。cookie 値にタブは現れない。 */
    private static final String FIELD_SEP = "\t";
    private static final String LINE_SEP = "\n";

    /** 参照時に初めて作る。static 初期化で PreferenceManager に触ると純粋な parse まで Android 依存になる。 */
    private static final class Holder {
        static final SeciossSsoStore INSTANCE = new SeciossSsoStore(System::currentTimeMillis);
    }

    public static SeciossSsoStore getInstance() { return Holder.INSTANCE; }

    /** 属性込みの 1 cookie。expiresAt < 0 はセッション cookie (プロセス終了で消える)。 */
    static final class StoredCookie {
        final String name;
        final String value;
        final String path;
        final long expiresAt;
        final long receivedAt;

        StoredCookie(@NonNull String name, @NonNull String value, @NonNull String path, long expiresAt, long receivedAt) {
            this.name = name;
            this.value = value;
            this.path = path;
            this.expiresAt = expiresAt;
            this.receivedAt = receivedAt;
        }

        boolean isPersistent() { return expiresAt >= 0; }
    }

    private final Object lock = new Object();
    private final LongSupplier clock;
    /** null = まだ永続化領域から読んでいない。 */
    @Nullable
    private List<StoredCookie> memory;

    SeciossSsoStore(@NonNull LongSupplier clock) { this.clock = clock; }

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
    public void merge(@NonNull List<Cookie> received) {
        if (received.isEmpty()) return;
        synchronized (lock) {
            long now = clock.getAsLong();
            List<StoredCookie> cookies = new ArrayList<>(loadLocked());
            for (Cookie c : received) {
                // ストアは slink 限定 (current() が host-only slink として再生するため)。
                if (!c.domain().equalsIgnoreCase(IDP_HOST)) continue;
                cookies.removeIf(s -> s.name.equals(c.name()) && s.path.equals(c.path()));
                if (SeciossIdPClient.isExpired(c, now)) continue;
                cookies.add(new StoredCookie(c.name(), c.value(), c.path(), c.persistent() ? c.expiresAt() : -1, now));
            }
            cookies = persistable(cookies, now);
            memory = cookies;
            PreferenceManager.getInstance().setIdpSsoCookieHeader(serializePersistent(cookies));
        }
    }

    /** ログアウト時・資格情報を入れ直したとき・アカウント切り替え時。 */
    public void clear() {
        synchronized (lock) {
            memory = new ArrayList<>();
            PreferenceManager.getInstance().setIdpSsoCookieHeader(null);
        }
    }

    @NonNull
    private List<StoredCookie> loadLocked() {
        if (memory == null) {
            memory = load(PreferenceManager.getInstance().getIdpSsoCookieHeader(), clock.getAsLong());
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
            if (f.length != 4) return new ArrayList<>();  // 旧形式 "name=value; ..." → 期限が分からないので持ち越さない
            long expiresAt;
            try {
                expiresAt = Long.parseLong(f[3]);
            } catch (NumberFormatException e) {
                return new ArrayList<>();
            }
            // 永続化されるのは期限付きだけなので receivedAt は判定に使わないが、形式は揃えておく。
            result.add(new StoredCookie(f[0], f[1], f[2], expiresAt, now));
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
                    .append(FIELD_SEP).append(c.expiresAt);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * slink.secioss.com が出す cookie は Domain 属性なし = host-only (2026-09-07 の実機 HAR で 15 種すべて確認)。
     * 発行元と同じ host-only 表現で作らないと mergeCookies の (name, domain, path) 一致が崩れ、再ログイン時に
     * 古い seed が消えず同名 cookie を二重送信して ALB スティッキーをまた壊す。
     */
    @NonNull
    static List<Cookie> toCookies(@NonNull List<StoredCookie> stored) {
        List<Cookie> out = new ArrayList<>();
        for (StoredCookie c : stored) {
            Cookie.Builder b = new Cookie.Builder().name(c.name).value(c.value)
                    .hostOnlyDomain(IDP_HOST).path(c.path);
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
