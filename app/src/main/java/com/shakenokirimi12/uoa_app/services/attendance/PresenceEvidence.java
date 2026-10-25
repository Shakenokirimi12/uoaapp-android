package com.shakenokirimi12.uoa_app.services.attendance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 自動出席の「欠席」の根拠と判定 (iOS の AbsenceEvidence と同じ規則)。
 *
 * 欠席は「来なかった」ことの証明が要る。出席は通知が届いた事実だけで記録できるが、欠席は「通知が届かなかった」
 * ことしか根拠にできず、監視が止まっていても同じに見える。そこで、監視が授業の間ずっと動いていたと記録から
 * 言えるときだけ欠席にし、言えなければ何も付けない (推測で欠席にしない)。Android にも Context にも依存しない。
 */
public final class PresenceEvidence {
    private PresenceEvidence() {}

    /** 授業の終わりから、欠席かどうかを決めるまで待つ時間。ジオフェンスの通知は数分遅れて届くことがあり、
     *  終わる直前に着いた人の通知を待たずに欠席にしないための余白。欠席の判定の基準ではない。 */
    public static final long DELIVERY_MARGIN_MS = 15 * 60_000L;
    /** 同じ境界の通過で届く通知どうしの時刻のずれの許容。 */
    static final long SIMULTANEOUS_TOLERANCE_MS = 60_000L;

    public enum Kind {
        /** キャンパスの中にいた。 */
        INSIDE,
        /** キャンパスの外にいた。 */
        OUTSIDE,
        /** この時点で、監視に要る条件 (自動出席オン・権限・位置情報・ジオフェンスの登録など) が全部そろっていた。 */
        MONITORING_OK,
        /** この時点で、条件のどれかが欠けていた。 */
        MONITORING_LOST,
        /** since から at まで、監視が続いていたか分からない (強制停止・再起動など)。 */
        GAP
    }

    public static final class Entry {
        public Kind kind;
        public long at;
        /** INSIDE / OUTSIDE のとき、境界の通過と分かっている通知なら true。分からなければ false。 */
        @Nullable public Boolean crossing;
        /** GAP の始まり。 */
        @Nullable public Long since;

        public Entry() {}

        public Entry(@NonNull Kind kind, long at, @Nullable Boolean crossing, @Nullable Long since) {
            this.kind = kind;
            this.at = at;
            this.crossing = crossing;
            this.since = since;
        }

        boolean isObservation() { return kind == Kind.INSIDE || kind == Kind.OUTSIDE; }
        boolean isCheckpoint() { return kind == Kind.MONITORING_OK || kind == Kind.MONITORING_LOST; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry e = (Entry) o;
            return kind == e.kind && at == e.at && Objects.equals(crossing, e.crossing) && Objects.equals(since, e.since);
        }

        @Override public int hashCode() { return Objects.hash(kind, at, crossing, since); }

        @NonNull @Override public String toString() { return kind + "@" + at + (since != null ? "<-" + since : ""); }
    }

    public enum Verdict {
        /** 授業の間ずっと外にいて、その間ずっと監視が動いていた。 */
        ABSENT,
        /** 授業の間にキャンパスの中にいた (出席の記録が無くても欠席にはしない)。 */
        NOT_ABSENT,
        /** 監視が続いていたと言えない。何も付けない。 */
        UNKNOWN,
        /** まだ決められない (授業が終わっていない・通知の遅れを待っている)。 */
        PENDING
    }

    /**
     * 授業 1 回分の判定。欠席になるのは次が全部そろうときだけ:
     * 1. 窓の始まりの時点で分かっている最後の状態が「外」で、その時点 (t0) で監視が動いていた。
     * 2. t0 から窓の終わり + margin の後の最初の確認まで、監視が欠けた記録も、続いていたか分からない区間も無い。
     * 3. t0 から窓の終わり + margin まで、「中」の記録が無い。
     * 4. 窓の終わり + margin の後に、状態 (中か外か) を確かめた記録がある。
     * 5. その後に「中」と分かったなら、それが窓の後の通過で説明できる (通過の通知を取りこぼしていない)。
     */
    @NonNull
    public static Verdict verdict(long windowStart, long windowEnd, @NonNull List<Entry> entries, long now, long margin) {
        long end = windowEnd + margin;
        if (now < end) return Verdict.PENDING;

        // 時刻順。同じ時刻なら記録した順を保つ (sort は安定)。
        List<Entry> log = new ArrayList<>(entries);
        Collections.sort(log, (a, b) -> Long.compare(a.at, b.at));

        int startIndex = -1;
        for (int i = 0; i < log.size(); i++) {
            Entry e = log.get(i);
            if (e.isObservation() && e.at <= windowStart) startIndex = i;
        }
        if (startIndex < 0) return Verdict.UNKNOWN;
        if (log.get(startIndex).kind != Kind.OUTSIDE) return Verdict.NOT_ABSENT;
        long t0 = log.get(startIndex).at;

        int openingIndex = -1;
        for (int i = 0; i <= startIndex; i++) if (log.get(i).isCheckpoint()) openingIndex = i;
        if (openingIndex < 0 || log.get(openingIndex).kind != Kind.MONITORING_OK) return Verdict.UNKNOWN;

        int closingIndex = -1;
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).isCheckpoint() && log.get(i).at >= end) { closingIndex = i; break; }
        }
        if (closingIndex < 0 || log.get(closingIndex).kind != Kind.MONITORING_OK) return Verdict.UNKNOWN;
        long closingAt = log.get(closingIndex).at;

        for (int i = openingIndex + 1; i <= closingIndex; i++) {
            if (log.get(i).kind == Kind.MONITORING_LOST) return Verdict.UNKNOWN;
        }
        for (Entry e : log) {
            if (e.kind == Kind.GAP && (e.since != null ? e.since : e.at) <= closingAt && e.at >= t0) return Verdict.UNKNOWN;
        }
        for (Entry e : log) {
            if (e.kind == Kind.INSIDE && e.at > t0 && e.at <= end) return Verdict.NOT_ABSENT;
        }

        // 授業の後に状態を確かめていなければ決めない。登録し直したときの状態の通知 (INITIAL_TRIGGER) は判定より後に
        // 届くので、届いて判定し直すまで待つ。これが無いと、入った通知を取りこぼしたまま欠席になる。
        boolean observedAfter = false;
        for (Entry e : log) if (e.isObservation() && e.at >= end) { observedAfter = true; break; }
        if (!observedAfter) return Verdict.UNKNOWN;

        Entry laterInside = null;
        for (Entry e : log) {
            if (e.kind == Kind.INSIDE && e.at > end) { laterInside = e; break; }
        }
        if (laterInside != null && !Boolean.TRUE.equals(laterInside.crossing)) {
            // 中にいると分かったが、そこまでに通過の通知が届いていなければ、入った時刻が分からない。
            boolean crossed = false;
            boolean seenOutsideAfterEnd = false;
            for (Entry e : log) {
                if (e.kind == Kind.INSIDE && Boolean.TRUE.equals(e.crossing) && e.at > t0
                        && e.at <= laterInside.at + SIMULTANEOUS_TOLERANCE_MS) crossed = true;
                if (e.kind == Kind.OUTSIDE && e.at >= end && e.at < laterInside.at) seenOutsideAfterEnd = true;
            }
            if (!crossed && !seenOutsideAfterEnd) return Verdict.UNKNOWN;
        }
        return Verdict.ABSENT;
    }
}
