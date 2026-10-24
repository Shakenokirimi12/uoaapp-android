package com.shakenokirimi12.uoa_app.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.SecureRandom;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 問い合わせ ID (support ID)。アプリを入れたあと最初に使うときに作る 8 文字 (Crockford base32、表示は XXXX-XXXX)。
 * iOS の SupportID.swift と同じ形。
 *
 * 設定の画面のいちばん下に出し、利用者が問い合わせに書き添える。運営は管理画面の検索でこの ID から
 * 端末・エラー報告・予約・OTP 中継の連携を引く。そのため通知の登録・エラー報告・OTP 中継の連携で送る。
 *
 * 端末の id (device_id) は出さない。サーバーの DELETE /api/devices/:id などは端末の id だけでその端末のデータを
 * 消せるので、画面に出すと鍵を配ることになる。問い合わせ ID はどの口でも認可に使わない。
 *
 * 秘密ではないので平文の prefs に置く (EncryptedSharedPreferences には置かない)。寿命は device_id と同じにする:
 * バックアップで別の端末へ持ち込まず (PreferenceManager.DEVICE_LOCAL_KEYS)、ログアウト (clearAll) で作り直す。
 */
public final class SupportId {
    /** Crockford base32。読み違えやすい I L O U を除いた 32 文字。 */
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    /** 保存する形。サーバー (uoa-app-push の normalizeSupportId) と同じ文字の集まり。 */
    private static final Pattern FORM = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$");

    private SupportId() {}

    @NonNull
    public static String generate() {
        return generate(new SecureRandom());
    }

    @NonNull
    static String generate(@NonNull Random random) {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static boolean isValid(@Nullable String value) {
        return value != null && FORM.matcher(value).matches();
    }
}
