package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 強制アップデートの判定規則。Android の API に依存させず、単体テストで固定する (ForceUpdatePolicyTest)。
 * iOS アプリと同じ規則にそろえる。
 *
 * - バージョンは数字とドットだけ ("1.0.0")。前後の空白は許す。それ以外 (接尾辞・空・桁あふれ) は
 *   「分からない」(null) として扱い、0 とみなさない。0 とみなすと、壊れた値で判定が逆に倒れる。
 * - 末尾の 0 は意味を持たない (1.0 == 1.0.0)。各桁は数値で比べる (1.10 > 1.9)。
 * - 最後に取得できた最低バージョンを端末に残し、取得に失敗している間もそれで判定する。
 *   一度も取得できていないときだけ通す (サーバーが落ちていても使えるように)。
 */
public final class ForceUpdatePolicy {
    private ForceUpdatePolicy() {}

    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");

    /** 末尾の 0 を落とした各桁。読めなければ null。 */
    @Nullable
    static int[] parseVersion(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (!VERSION.matcher(s).matches()) return null;
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null; // 桁あふれ
            }
        }
        int len = out.length;
        while (len > 1 && out[len - 1] == 0) len--;
        int[] trimmed = new int[len];
        System.arraycopy(out, 0, trimmed, 0, len);
        return trimmed;
    }

    /** a と b を比べる。どちらかが読めなければ null。 */
    @Nullable
    static Integer compareVersions(@Nullable String a, @Nullable String b) {
        int[] pa = parseVersion(a), pb = parseVersion(b);
        if (pa == null || pb == null) return null;
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /** ストアを開く URL として使えるか。https (ホストあり) か market:// だけを通す。 */
    static boolean isValidStoreUrl(@Nullable String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.isEmpty() || !s.equals(raw)) return false;
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if (scheme.equalsIgnoreCase("https")) {
                return uri.getHost() != null && !uri.getHost().isEmpty();
            }
            return scheme.equalsIgnoreCase("market");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** 端末に残す、最後に取得できた強制アップデートの条件。 */
    public static final class Requirement {
        @NonNull public final String minVersion;
        @Nullable public final String storeUrl;
        @Nullable public final String message;

        public Requirement(@NonNull String minVersion, @Nullable String storeUrl, @Nullable String message) {
            this.minVersion = minVersion;
            this.storeUrl = storeUrl;
            this.message = message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Requirement)) return false;
            Requirement r = (Requirement) o;
            return minVersion.equals(r.minVersion)
                    && Objects.equals(storeUrl, r.storeUrl)
                    && Objects.equals(message, r.message);
        }

        @Override
        public int hashCode() { return Objects.hash(minVersion, storeUrl, message); }

        @NonNull
        @Override
        public String toString() { return "Requirement(" + minVersion + ", " + storeUrl + ")"; }
    }

    /**
     * 取得に成功した (HTTP 2xx・JSON が読めた・success が true) ときの、次に残す条件。
     * - 最低バージョンが無い・空 → 条件を消す (サーバーが「最低バージョンなし」と言っている)。
     * - 最低バージョンが読めない → 手元の条件をそのまま残す (壊れた値で解除も強制もしない)。
     * - 読める → その値と、使える URL (使えなければ null) と文面を残す。
     * 取得に失敗したときは呼ばない (手元の条件をそのまま使う)。
     */
    @Nullable
    static Requirement afterSuccessfulFetch(@Nullable Requirement cached, @Nullable String minVersion,
                                            @Nullable String storeUrl, @Nullable String message) {
        if (minVersion == null || minVersion.trim().isEmpty()) return null;
        if (parseVersion(minVersion) == null) return cached;
        String url = isValidStoreUrl(storeUrl) ? storeUrl : null;
        String msg = message != null && !message.trim().isEmpty() ? message : null;
        return new Requirement(minVersion.trim(), url, msg);
    }

    /** 自分のバージョンが条件の最低バージョンより古ければ true。どちらかが読めなければ false。 */
    static boolean isBlocked(@Nullable String ownVersion, @Nullable Requirement requirement) {
        if (requirement == null) return false;
        Integer c = compareVersions(ownVersion, requirement.minVersion);
        return c != null && c < 0;
    }

    /** 開くストアの URL。条件の URL が使えなければ、このアプリの Google Play のページ。 */
    @NonNull
    static String storeUrl(@Nullable Requirement requirement, @NonNull String packageName) {
        if (requirement != null && isValidStoreUrl(requirement.storeUrl)) return requirement.storeUrl;
        return playStoreUrl(packageName);
    }

    @NonNull
    public static String playStoreUrl(@NonNull String packageName) {
        return "https://play.google.com/store/apps/details?id=" + packageName;
    }
}
