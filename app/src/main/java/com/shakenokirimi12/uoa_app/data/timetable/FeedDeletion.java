package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/** 消すのを待つ自分のフィード (10.3)。端末に残し、友達の一覧を開いたときに DELETE する。 */
public final class FeedDeletion {
    @NonNull public final String feedId;
    /** DELETE に付ける版番号 (そのフィードの myFeedVersion + 1)。 */
    public final long version;
    /**
     * そのフィードを作った鍵の公開鍵。今の鍵と違えば (バックアップから戻した) 署名しても 403 なので、送らずに外す。
     * null は分からない (今の鍵と違うものとして扱う)。
     */
    @Nullable public final String signingKey;

    public FeedDeletion(@NonNull String feedId, long version, @Nullable String signingKey) {
        this.feedId = feedId;
        this.version = version;
        this.signingKey = signingKey;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FeedDeletion)) return false;
        FeedDeletion d = (FeedDeletion) o;
        return feedId.equals(d.feedId) && version == d.version && Objects.equals(signingKey, d.signingKey);
    }

    @Override
    public int hashCode() { return Objects.hash(feedId, version, signingKey); }
}
