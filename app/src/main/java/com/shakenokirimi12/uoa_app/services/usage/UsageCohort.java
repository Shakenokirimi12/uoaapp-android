package com.shakenokirimi12.uoa_app.services.usage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学籍番号から「何期生か」だけを取り出す。利用状況で送るのはこの 2 つ (区分と期) だけで、
 * 学籍番号そのものや残りの桁は送らない・残さない。
 *
 * 学部は s1xx0yyy (xx が期。s1270123 なら 27 期)。大学院の m5xx… / d8xx… も同じ位置に期がある前提で、
 * 先頭の 2 文字を区分として送る。形が違う ID (教職員・桁数違い・空) は「不明」(null) にして推測しない。
 * 期を学年に直すことはしない (対応が確かめられていない)。
 * iOS の UsageCohort と同じ規則。
 */
public final class UsageCohort {
    private static final Pattern ID = Pattern.compile("^(s1|m5|d8)(\\d{2})\\d{4}$");

    /** "s1" / "m5" / "d8"。 */
    @NonNull public final String category;
    /** 0〜99。 */
    public final int cohort;

    private UsageCohort(@NonNull String category, int cohort) {
        this.category = category;
        this.cohort = cohort;
    }

    /** 形の分からないものは null。 */
    @Nullable
    public static UsageCohort parse(@Nullable String username) {
        if (username == null) return null;
        Matcher m = ID.matcher(username.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        return new UsageCohort(m.group(1), Integer.parseInt(m.group(2)));
    }
}
