package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

/**
 * SP (Moodle / CampusSquare) の確立済みセッションの永続化形式。iOS の SessionStore の .service スコープに相当。
 *
 * 以前は "name=value; ..." だけを保存していたので、Domain / host-only / Path / 期限 / Secure が失われ、
 * 復元時には全部「そのホストの host-only、Path=/、期限なし」に作り直していた。期限の切れた cookie を
 * 送り続け、Path の違う同名 cookie を区別できなかった。ここでは属性込みで保存し、読むときに失効分を捨てる。
 *
 * 保持の規則 (CLAUDE.md の「SP session」):
 * - Expires の無いセッション cookie (MoodleSession / JSESSIONID) も保存し、時間で切らない。
 *   取り直すと OTP メールが 1 通飛ぶ一方、使う前に必ず probe するので、死んだ cookie の害は probe 1 回の空振りだけ。
 *   IdP の SSO ストア (SeciossSsoStore) の 1 時間の上限をここへ持ち込むと 2026-09-25 の OTP メール多発が再発する。
 * - SAML の使い捨て状態 cookie (_opensaml_req_ss:*, _redirection_state) は持たない。
 * - 復元時は、渡されたホストへ送られうる (ドメインが一致する) ものだけを戻す。
 * - 持ち主 (AINS のユーザー名) と一緒に保存し、今のユーザー名と違うものは戻さない。設定でアカウントを切り替えている
 *   間に、前のアカウントの自動の取得がセッションを作り直しても、新しいアカウントがそれを使わないようにする。
 *   持ち主の無いもの (旧形式 "name=value; ..."、持ち主を記録する前の新形式) は、誰のものか分からないので
 *   よそのものとして扱う。更新後の最初の 1 回だけ、SP へのログインをやり直す (IdP の SSO が生きていれば OTP は要らない)。
 */
public final class SpSessionCookies {
    private SpSessionCookies() {}

    static final int FORMAT_VERSION = 2;
    private static final Gson GSON = new Gson();

    /** 保存形式。null の expiresAt はセッション cookie。 */
    private static final class Stored {
        int v;
        /** 持ち主 (AINS のユーザー名、前後の空白を落としたもの)。 */
        String owner;
        List<Entry> cookies;
    }

    private static final class Entry {
        String name;
        String value;
        String domain;
        boolean hostOnly;
        String path;
        Long expiresAt;
        boolean secure;
        boolean httpOnly;
    }

    /** 旧形式 ("name=value; ...") か。新形式は JSON オブジェクトなので '{' で始まる (cookie 名に '{' は使えない)。 */
    private static boolean isLegacy(@Nullable String stored) {
        return stored != null && !stored.isEmpty() && !stored.trim().startsWith("{");
    }

    /**
     * stored が owner のセッションか。持ち主の無いもの (旧形式・持ち主を記録する前の新形式)、壊れたもの、空は false。
     * 呼び出し側は false なら保存分を消す (よそのセッションを持ち続けない)。
     */
    public static boolean ownedBy(@Nullable String stored, @Nullable String owner) {
        if (stored == null || stored.trim().isEmpty() || owner == null || isLegacy(stored)) return false;
        String o = owner.trim();
        if (o.isEmpty()) return false;
        try {
            Stored s = GSON.fromJson(stored, Stored.class);
            return s != null && o.equals(s.owner);
        } catch (JsonParseException e) {
            return false;
        }
    }

    /** 保存する文字列 (owner のセッションとして)。持つべき cookie が無ければ null (保存領域を空にする)。 */
    @Nullable
    public static String serialize(@NonNull List<Cookie> cookies, long now, @NonNull String owner) {
        List<Entry> entries = new ArrayList<>();
        for (Cookie c : retainable(cookies, now)) {
            Entry e = new Entry();
            e.name = c.name();
            e.value = c.value();
            e.domain = c.domain();
            e.hostOnly = c.hostOnly();
            e.path = c.path();
            e.expiresAt = c.persistent() ? c.expiresAt() : null;
            e.secure = c.secure();
            e.httpOnly = c.httpOnly();
            entries.add(e);
        }
        if (entries.isEmpty()) return null;
        Stored s = new Stored();
        s.v = FORMAT_VERSION;
        s.owner = owner.trim();
        s.cookies = entries;
        return GSON.toJson(s);
    }

    /**
     * 保存分を owner の host のセッションとして戻す。owner のものでなければ (ownedBy) 何も戻さない。
     * 失効分・host へ送られないもの・壊れた行は落ちる。
     */
    @NonNull
    public static List<Cookie> restore(@Nullable String stored, @NonNull String owner, @NonNull String host, long now) {
        List<Cookie> out = new ArrayList<>();
        if (!ownedBy(stored, owner)) return out;
        String h = host.toLowerCase(Locale.ROOT);
        Stored s;
        try {
            s = GSON.fromJson(stored, Stored.class);
        } catch (JsonParseException e) {
            return out;
        }
        if (s == null || s.cookies == null) return out;
        for (Entry e : s.cookies) {
            Cookie c = toCookie(e);
            if (c != null && HostScopedCookieJar.domainMatches(c, h)) out.add(c);
        }
        return retainable(out, now);
    }

    /** cookie 群から url に送るものを "a=b; c=d" で。Path / Secure / 期限を見る。無ければ空文字。 */
    @NonNull
    public static String headerFor(@NonNull List<Cookie> cookies, @NonNull HttpUrl url, long now) {
        StringBuilder sb = new StringBuilder();
        for (Cookie c : cookies) {
            if (!c.matches(url) || isExpired(c, now)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name()).append('=').append(c.value());
        }
        return sb.toString();
    }

    @NonNull
    static List<Cookie> retainable(@NonNull List<Cookie> cookies, long now) {
        List<Cookie> out = new ArrayList<>();
        for (Cookie c : cookies) {
            if (isExpired(c, now)) continue;
            if (c.value().isEmpty()) continue;
            if (c.name().startsWith("_opensaml_req_ss:") || c.name().equals("_redirection_state")) continue;
            out.add(c);
        }
        return out;
    }

    private static boolean isExpired(@NonNull Cookie c, long now) {
        return c.persistent() && c.expiresAt() <= now;
    }

    @Nullable
    private static Cookie toCookie(@NonNull Entry e) {
        if (e.name == null || e.value == null || e.domain == null) return null;
        try {
            Cookie.Builder b = new Cookie.Builder().name(e.name).value(e.value)
                    .path(e.path != null && e.path.startsWith("/") ? e.path : "/");
            if (e.hostOnly) b.hostOnlyDomain(e.domain); else b.domain(e.domain);
            if (e.expiresAt != null) b.expiresAt(e.expiresAt);
            if (e.secure) b.secure();
            if (e.httpOnly) b.httpOnly();
            return b.build();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
