package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * リモートフラグ (カンマ区切り) で上書きできる文言リスト。CampusSquare/Moodle の成功マーカー・
 * 認証失敗マーカーが同じ形で使う。空・未設定なら既定値。
 */
public final class MarkerList {
    private MarkerList() {}

    @NonNull
    public static List<String> parse(@Nullable String raw, @NonNull String[] defaults) {
        List<String> parsed = new ArrayList<>();
        if (raw != null) {
            for (String m : raw.split(",")) {
                String t = m.trim();
                if (!t.isEmpty()) parsed.add(t);
            }
        }
        return parsed.isEmpty() ? Arrays.asList(defaults) : parsed;
    }

    /** 大文字小文字を区別しない部分一致。 */
    public static boolean containsAnyIgnoreCase(@NonNull String html, @NonNull List<String> markers) {
        String lowered = html.toLowerCase();
        for (String m : markers) {
            if (!m.isEmpty() && lowered.contains(m.toLowerCase())) return true;
        }
        return false;
    }
}
