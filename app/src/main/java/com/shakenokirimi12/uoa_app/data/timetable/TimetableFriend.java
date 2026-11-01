package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 受け取った友達の時間割。端末の中だけに置く (サーバーにもアカウントにも結び付けない)。
 * v3 の項目 (publicKey 以下) はどれも省略でき、古い版で保存した友達は無いまま読む (docs/timetable-share-v3.md の 10.3)。
 */
public final class TimetableFriend {
    /** 項目から決める状態 (10.3)。一覧の行と友達の画面に出す。 */
    public enum State { SHARING, WAITING, STOPPED, TERM_ONLY }

    @NonNull public final String id;
    /** 画面に出す名前。受け取った後に変えられる。 */
    @NonNull public final String name;
    /** 相手が付けて送ってきた名前。同じ鍵の共有がもう一度届いたら、これだけを更新する (name は利用者のもの)。 */
    @NonNull public final String sentName;
    public final long receivedAt;
    @NonNull public final SharedTimetable timetable;

    /** 相手の公開鍵 (SPKI DER の base64url)。v2 (署名なし) で受け取った友達は null。同じ人の判定に使う。 */
    @Nullable public final String publicKey;
    /** 今期だけの友達を消すための学期 ("2026-2")。null なら receivedAt から決める。 */
    @Nullable public final String term;
    /** 自分が作った、この相手宛てのフィード。 */
    @Nullable public final String myFeedId;
    /** そのフィードを作った鍵の公開鍵。今の鍵と違えば (バックアップから戻した) 更新も削除もできない。 */
    @Nullable public final String myFeedKey;
    /** そのフィードに最後に使った版番号 (送れなかった番号も含む。次は必ずこれより大きくする)。 */
    public final long myFeedVersion;
    /** そのフィードに最後に置いた時間割の contentKey。 */
    @Nullable public final String myFeedContentKey;
    /** 追っている相手のフィード。 */
    @Nullable public final String peerFeedId;
    /** 相手のフィードから最後に取り込んだ版番号。null ならまだ取り込んでいない。 */
    @Nullable public final Long peerFeedVersion;
    /** 相手から「今後も共有し続ける」を申し込まれ、まだ答えていないときの相手のフィード。 */
    @Nullable public final String offeredFeedId;
    /** 相手が共有を止めた (相手のフィードが 410 だった)。 */
    public final boolean sharingStopped;
    /**
     * 申し込みに「共有し続ける」と答えて作り始めた自分のフィード (まだ相手の欄につないでいない)。作る・つなぐの応答を
     * 受け取れなかったときに残し、次に答えるときは新しく作らずにこれを使う (作ったかもしれないフィードを増やさない)。
     */
    @Nullable public final String pendingFeedId;
    @Nullable public final String pendingFeedKey;
    public final long pendingFeedVersion;
    /** 作りかけのフィードを作ったときに答えていた申し込み (相手のフィード)。違う申し込みには使わない。 */
    @Nullable public final String pendingFeedOffer;

    public TimetableFriend(@NonNull String id, @NonNull String name, @NonNull String sentName, long receivedAt,
                           @NonNull SharedTimetable timetable) {
        this(id, name, sentName, receivedAt, timetable, null, null, null, null, 0, null, null, null, null, false,
                null, null, 0, null);
    }

    TimetableFriend(@NonNull String id, @NonNull String name, @NonNull String sentName, long receivedAt,
                    @NonNull SharedTimetable timetable, @Nullable String publicKey, @Nullable String term,
                    @Nullable String myFeedId, @Nullable String myFeedKey, long myFeedVersion,
                    @Nullable String myFeedContentKey, @Nullable String peerFeedId, @Nullable Long peerFeedVersion,
                    @Nullable String offeredFeedId, boolean sharingStopped, @Nullable String pendingFeedId,
                    @Nullable String pendingFeedKey, long pendingFeedVersion, @Nullable String pendingFeedOffer) {
        this.id = id;
        this.name = name;
        this.sentName = sentName;
        this.receivedAt = receivedAt;
        this.timetable = timetable;
        this.publicKey = publicKey;
        this.term = term;
        this.myFeedId = myFeedId;
        this.myFeedKey = myFeedKey;
        this.myFeedVersion = myFeedVersion;
        this.myFeedContentKey = myFeedContentKey;
        this.peerFeedId = peerFeedId;
        this.peerFeedVersion = peerFeedVersion;
        this.offeredFeedId = offeredFeedId;
        this.sharingStopped = sharingStopped;
        this.pendingFeedId = pendingFeedId;
        this.pendingFeedKey = pendingFeedKey;
        this.pendingFeedVersion = pendingFeedVersion;
        this.pendingFeedOffer = pendingFeedOffer;
    }

    @NonNull
    public State state() {
        if (myFeedId != null && peerFeedId != null) return State.SHARING;
        if (myFeedId != null) return State.WAITING;
        if (sharingStopped) return State.STOPPED;
        return State.TERM_ONLY;
    }

    /**
     * 学期。古い版で保存した友達は起動したときに今の学期を書き込む (TimetableShareRules.adoptCurrentTerm) ので、
     * 受け取った日から決めるのはその前に読んだときだけ。
     */
    @NonNull
    public String effectiveTerm() { return term != null ? term : ShareTerm.of(receivedAt); }

    @NonNull
    public Builder edit() { return new Builder(this); }

    /** 項目を変えた写しを作る (友達は変えられない値にしておき、保存の前に組み直す)。 */
    public static final class Builder {
        @NonNull String id;
        @NonNull String name;
        @NonNull String sentName;
        long receivedAt;
        @NonNull SharedTimetable timetable;
        @Nullable String publicKey;
        @Nullable String term;
        @Nullable String myFeedId;
        @Nullable String myFeedKey;
        long myFeedVersion;
        @Nullable String myFeedContentKey;
        @Nullable String peerFeedId;
        @Nullable Long peerFeedVersion;
        @Nullable String offeredFeedId;
        boolean sharingStopped;
        @Nullable String pendingFeedId;
        @Nullable String pendingFeedKey;
        long pendingFeedVersion;
        @Nullable String pendingFeedOffer;

        Builder(@NonNull TimetableFriend f) {
            id = f.id;
            name = f.name;
            sentName = f.sentName;
            receivedAt = f.receivedAt;
            timetable = f.timetable;
            publicKey = f.publicKey;
            term = f.term;
            myFeedId = f.myFeedId;
            myFeedKey = f.myFeedKey;
            myFeedVersion = f.myFeedVersion;
            myFeedContentKey = f.myFeedContentKey;
            peerFeedId = f.peerFeedId;
            peerFeedVersion = f.peerFeedVersion;
            offeredFeedId = f.offeredFeedId;
            sharingStopped = f.sharingStopped;
            pendingFeedId = f.pendingFeedId;
            pendingFeedKey = f.pendingFeedKey;
            pendingFeedVersion = f.pendingFeedVersion;
            pendingFeedOffer = f.pendingFeedOffer;
        }

        public Builder name(@NonNull String v) { name = v; return this; }
        public Builder sentName(@NonNull String v) { sentName = v; return this; }
        public Builder receivedAt(long v) { receivedAt = v; return this; }
        public Builder timetable(@NonNull SharedTimetable v) { timetable = v; return this; }
        public Builder publicKey(@Nullable String v) { publicKey = v; return this; }
        public Builder term(@Nullable String v) { term = v; return this; }
        public Builder myFeedId(@Nullable String v) { myFeedId = v; return this; }
        public Builder myFeedKey(@Nullable String v) { myFeedKey = v; return this; }
        public Builder myFeedVersion(long v) { myFeedVersion = v; return this; }
        public Builder myFeedContentKey(@Nullable String v) { myFeedContentKey = v; return this; }
        public Builder peerFeedId(@Nullable String v) { peerFeedId = v; return this; }
        public Builder peerFeedVersion(@Nullable Long v) { peerFeedVersion = v; return this; }
        public Builder offeredFeedId(@Nullable String v) { offeredFeedId = v; return this; }
        public Builder sharingStopped(boolean v) { sharingStopped = v; return this; }
        public Builder pendingFeedId(@Nullable String v) { pendingFeedId = v; return this; }
        public Builder pendingFeedKey(@Nullable String v) { pendingFeedKey = v; return this; }
        public Builder pendingFeedVersion(long v) { pendingFeedVersion = v; return this; }
        public Builder pendingFeedOffer(@Nullable String v) { pendingFeedOffer = v; return this; }

        /** 自分のフィード (と、その鍵・版番号・contentKey) を外す。 */
        public Builder clearMyFeed() { myFeedId = null; myFeedKey = null; myFeedContentKey = null; return this; }

        /** 作りかけのフィードを外す。 */
        public Builder clearPendingFeed() {
            pendingFeedId = null;
            pendingFeedKey = null;
            pendingFeedVersion = 0;
            pendingFeedOffer = null;
            return this;
        }

        @NonNull
        public TimetableFriend build() {
            return new TimetableFriend(id, name, sentName, receivedAt, timetable, publicKey, term, myFeedId, myFeedKey,
                    myFeedVersion, myFeedContentKey, peerFeedId, peerFeedVersion, offeredFeedId, sharingStopped,
                    pendingFeedId, pendingFeedKey, pendingFeedVersion, pendingFeedOffer);
        }
    }
}
