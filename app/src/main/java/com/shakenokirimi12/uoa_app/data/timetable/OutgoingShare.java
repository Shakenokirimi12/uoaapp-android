package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 自分が出している共有 (QR / リンク)。返事を取るトークンは暗号化 prefs に置く (PreferenceManager)。 */
public final class OutgoingShare {
    @NonNull public final String shareId;
    public final long expiresAt;
    /** 取り込んだ返事。同じ返事を二度取り込まない。 */
    @NonNull public final List<String> seenReplyIds;

    public OutgoingShare(@NonNull String shareId, long expiresAt, @NonNull List<String> seenReplyIds) {
        this.shareId = shareId;
        this.expiresAt = expiresAt;
        this.seenReplyIds = Collections.unmodifiableList(new ArrayList<>(seenReplyIds));
    }

    public boolean isExpired(long nowMs) { return nowMs >= expiresAt; }
}
