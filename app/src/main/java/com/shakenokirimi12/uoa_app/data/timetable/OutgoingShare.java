package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 自分が出している共有 (QR / リンク)。返事を取るトークンは暗号化 prefs に置く (PreferenceManager)。 */
public final class OutgoingShare {
    @NonNull public final String shareId;
    public final long expiresAt;
    /** 作ったときの時間割 (TimetableShareStore.contentKey)。変わっていたら使い回さない。 */
    @NonNull public final String contentKey;
    /** 取り込んだ返事。同じ返事を二度取り込まない。 */
    @NonNull public final List<String> seenReplyIds;
    /**
     * 署名付き (v3) で作ったか。署名なしの古い共有は、contentKey が同じでも使い回さない
     * (読んだ相手が同じ人を見分けられず、継続共有も選べないため)。
     */
    public final boolean signed;

    public OutgoingShare(@NonNull String shareId, long expiresAt, @NonNull String contentKey, @NonNull List<String> seenReplyIds) {
        this(shareId, expiresAt, contentKey, seenReplyIds, false);
    }

    public OutgoingShare(@NonNull String shareId, long expiresAt, @NonNull String contentKey, @NonNull List<String> seenReplyIds,
                         boolean signed) {
        this.shareId = shareId;
        this.expiresAt = expiresAt;
        this.contentKey = contentKey;
        this.seenReplyIds = Collections.unmodifiableList(new ArrayList<>(seenReplyIds));
        this.signed = signed;
    }

    public boolean isExpired(long nowMs) { return nowMs >= expiresAt; }
}
