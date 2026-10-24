package com.shakenokirimi12.uoa_app.data.timetable;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

/** 共有 ID と QR / リンク。iOS の TimetableShareLink と同じ形。android.net.Uri は単体テストで動かないので java.net.URI で読む。 */
public final class TimetableShareLink {
    /** リンクのホスト。ブラウザで開くと uoa-app-push の /t/:id が「アプリで開く」を出す。 */
    public static final String WEB_BASE = "https://uoa-app.shakenokiri.me/push";

    private TimetableShareLink() {}

    /** QR に入れる URL。標準のカメラで読むとアプリ (TimetableIncomingActivity) が開く。 */
    @NonNull
    public static String appUrl(@NonNull String shareId) { return "uoa-app://timetable?id=" + shareId; }

    @NonNull
    public static String webUrl(@NonNull String shareId) { return WEB_BASE + "/t/" + shareId; }

    public static boolean isShareId(@Nullable String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** QR・リンク・貼り付けた文字から共有 ID を取り出す。`uoa-app://timetable?id=`、`https://…/t/<id>`、ID だけ。 */
    @Nullable
    public static String shareId(@Nullable String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (isShareId(trimmed)) return trimmed;
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return null;
        }
        if ("uoa-app".equals(uri.getScheme())) {
            if (!"timetable".equals(uri.getHost()) || uri.getRawQuery() == null) return null;
            for (String part : uri.getRawQuery().split("&")) {
                if (part.startsWith("id=")) {
                    String id = part.substring(3);
                    return isShareId(id) ? id : null;
                }
            }
            return null;
        }
        String expectedHost = URI.create(WEB_BASE).getHost();
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || !uri.getHost().equalsIgnoreCase(expectedHost) || uri.getPath() == null) return null;
        String[] parts = uri.getPath().split("/");
        if (parts.length < 2) return null;
        String last = parts[parts.length - 1];
        return "t".equals(parts[parts.length - 2]) && isShareId(last) ? last : null;
    }

    /** Intent の data から。 */
    @Nullable
    public static String shareId(@Nullable Uri uri) { return uri != null ? shareId(uri.toString()) : null; }
}
