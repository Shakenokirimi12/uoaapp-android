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
 *
 * 旧形式 ("name=value; ...") は捨てずに、そのホストの host-only・Path=/・Secure・セッション cookie として読む。
 * 以前の復元と同じ扱いで、送る先はそのホストだけなので他ホストへ漏れない。捨てると、更新した全員が次の起動で
 * IdP ログイン (OTP メール) をやり直すことになる。読み直したら呼び出し側が新形式で保存し直す。
 */
public final class SpSessionCookies {
    private SpSessionCookies() {}

    static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new Gson();

    /** 保存形式。null の expiresAt はセッション cookie。 */
    private static final class Stored {
        int v;
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
    public static boolean isLegacy(@Nullable String stored) {
        return stored != null && !stored.isEmpty() && !stored.trim().startsWith("{");
    }

    /** 保存する文字列。持つべき cookie が無ければ null (保存領域を空にする)。 */
    @Nullable
    public static String serialize(@NonNull List<Cookie> cookies, long now) {
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
        s.cookies = entries;
        return GSON.toJson(s);
    }

    /**
     * 保存分を host のセッションとして戻す。失効分・host へ送られないもの・壊れた行は落ちる。
     *
     * @param secure 旧形式を読むときに付ける Secure 属性 (SP が https なら true)。新形式は保存された値を使う。
     */
    @NonNull
    public static List<Cookie> restore(@Nullable String stored, @NonNull String host, boolean secure, long now) {
        List<Cookie> out = new ArrayList<>();
        if (stored == null || stored.trim().isEmpty()) return out;
        String h = host.toLowerCase(Locale.ROOT);
        if (isLegacy(stored)) {
            for (Cookie c : fromLegacyHeader(stored, h, secure)) out.add(c);
            return retainable(out, now);
        }
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

    @NonNull
    private static List<Cookie> fromLegacyHeader(@NonNull String header, @NonNull String host, boolean secure) {
        List<Cookie> out = new ArrayList<>();
        for (String pair : header.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            try {
                Cookie.Builder b = new Cookie.Builder()
                        .name(trimmed.substring(0, eq))
                        .value(trimmed.substring(eq + 1))
                        .hostOnlyDomain(host)
                        .path("/");
                if (secure) b.secure();
                out.add(b.build());
            } catch (IllegalArgumentException e) {
                // 壊れた 1 件だけ落とす。残りのセッションは使える。
            }
        }
        return out;
    }
}
