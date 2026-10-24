package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.Nullable;

/**
 * Moodle のカレンダーイベントから「課題を開く URL」を選ぶ。
 *
 * イベントの `id` はカレンダーイベントの ID で、`mod/assign/view.php?id=` が求めるコース
 * モジュール ID (cmid) とは別物。以前はこれを混同して、違う課題か存在しない課題を開いていた。
 * Moodle の event_exporter は `url` に活動の画面 (`/mod/<name>/view.php?id=<cmid>`) を、
 * `viewurl` にカレンダーの日表示を入れて返すので、`url` を優先して `viewurl` に落とす。
 */
public final class MoodleEventUrl {
    private MoodleEventUrl() {}

    /** 開く先が無ければ null。 */
    @Nullable
    public static String pick(@Nullable String url, @Nullable String viewurl) {
        if (isHttp(url)) return url;
        if (isHttp(viewurl)) return viewurl;
        return null;
    }

    private static boolean isHttp(@Nullable String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("https://") || t.startsWith("http://");
    }
}
