package com.shakenokirimi12.uoa_app.data.timetable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 時間割共有 v3 の決まり (docs/timetable-share-v3.md の 10.3〜10.7) のうち、通信も保存先も使わない部分。
 * TimetableShareStore はこれを通して友達と「消すのを待つフィード」を変える。単体テストの対象。
 */
final class TimetableShareRules {
    private TimetableShareRules() {}

    static final class Received {
        @NonNull final TimetableFriend friend;
        /** 同じ鍵の友達だった (足していない)。 */
        final boolean updated;
        /** 同じ鍵の友達の時間割を、届いたもので置き換えた (古い・同じ時刻のものなら false)。「更新しました」はこのときだけ。 */
        final boolean replaced;

        Received(@NonNull TimetableFriend friend, boolean updated, boolean replaced) {
            this.friend = friend;
            this.updated = updated;
            this.replaced = replaced;
        }
    }

    static int index(@NonNull List<TimetableFriend> friends, @NonNull String id) {
        for (int i = 0; i < friends.size(); i++) if (friends.get(i).id.equals(id)) return i;
        return -1;
    }

    static int indexByKey(@NonNull List<TimetableFriend> friends, @Nullable String publicKey) {
        if (publicKey == null) return -1;
        for (int i = 0; i < friends.size(); i++) if (publicKey.equals(friends.get(i).publicKey)) return i;
        return -1;
    }

    /**
     * 届いた時間割を保存する (10.5)。署名付き (publicKey あり) で同じ鍵の友達がいれば、その友達の時間割・送ってきた名前・
     * 受け取った日時・学期を更新し、止まった印を外す。利用者が付けた表示名と、一覧の並びは変えない。
     * それ以外 (v2 の署名なしを含む) は新しい友達として先頭に足す。名前では同じ人と決めない (なりすましで上書きされる)。
     * 同じ鍵でも、共有中 (peerFeedId あり) の友達と、時間割の createdAt が持っているもの以前のときは、時間割と受け取った
     * 日時は変えない (共有中は相手のフィードだけが正しい。古い共有を送り直して前の時間割に戻させない)。友達は返すので、
     * その返事の申し込み (offer) は取り込める。
     */
    @NonNull
    static Received receive(@NonNull List<TimetableFriend> friends, @NonNull SharedTimetable timetable,
                            @Nullable String publicKey, long now, @NonNull String newId) {
        String term = ShareTerm.of(now);
        int i = indexByKey(friends, publicKey);
        if (i >= 0) {
            TimetableFriend existing = friends.get(i);
            // 共有中の友達の時間割は、相手のフィードからだけ取り込む (QR・返事では置き換えない)。申し込みは呼んだ側が取り込む。
            if (existing.peerFeedId != null || !isNewer(timetable, existing)) return new Received(existing, true, false);
            TimetableFriend updated = existing.edit()
                    .timetable(timetable).sentName(timetable.name).receivedAt(now).term(term).sharingStopped(false)
                    .build();
            friends.set(i, updated);
            return new Received(updated, true, true);
        }
        TimetableFriend added = TimetableShareStore.add(friends, timetable, now, newId).edit()
                .publicKey(publicKey).term(term).build();
        friends.set(0, added);
        return new Received(added, false, false);
    }

    /** 届いた時間割が、持っているものより後に作られたか (createdAt は秒まで)。同じ・前なら取り込まない。 */
    private static boolean isNewer(@NonNull SharedTimetable incoming, @NonNull TimetableFriend existing) {
        return incoming.createdAtMillis() > existing.timetable.createdAtMillis();
    }

    /** 返事の「今後も共有し続ける」(feedId) を、まだ答えていない申し込みとして持つ。今追っているフィードなら何もしない。 */
    @NonNull
    static TimetableFriend offer(@NonNull TimetableFriend f, @Nullable String feedId) {
        if (feedId == null || feedId.equals(f.peerFeedId)) return f;
        return f.edit().offeredFeedId(feedId).build();
    }

    /**
     * 自分のフィード (と作りかけのフィード) があれば、消すのを待つ並びに積む。版番号は使った番号 + 1。
     * 呼んだ側は、積んだフィードを友達から外す (clearMyFeed / clearPendingFeed)。
     */
    static void queueOwnFeed(@NonNull List<FeedDeletion> queue, @NonNull TimetableFriend f) {
        queueFeed(queue, f.myFeedId, f.myFeedVersion + 1, f.myFeedKey);
        queueFeed(queue, f.pendingFeedId, f.pendingFeedVersion + 1, f.pendingFeedKey);
    }

    static void queueFeed(@NonNull List<FeedDeletion> queue, @Nullable String feedId, long version, @Nullable String key) {
        if (feedId == null) return;
        for (FeedDeletion d : queue) if (d.feedId.equals(feedId)) return;
        queue.add(new FeedDeletion(feedId, version, key));
    }

    // ── 申し込みに答える (10.5) ──

    /**
     * 開いた共有の相手と、もう共有し続けているか (選ばせずに「すでに共有し続けています」を出す)。今の鍵で作った
     * 自分のフィードがあるときだけ。鍵をなくした (バックアップから戻した) 端末では選び直させる。
     */
    static boolean alreadySharing(@Nullable TimetableFriend f, @Nullable String currentKey) {
        return f != null && f.myFeedId != null && currentKey != null && currentKey.equals(f.myFeedKey);
    }

    /**
     * 前に答えたときの作りかけのフィードを使い回すか。今の鍵で作ったものだけ (鍵が変わっていたら作り直す)。
     * 使い回せば、応答が届かなかった create・setPeer を同じ id で送り直せる (サーバーに余分なフィードを作らない)。
     */
    static boolean reusePending(@NonNull TimetableFriend f, @NonNull String currentKey) {
        // 同じ申し込みのために作ったものだけ。申し込みを持たない作りかけ (開発中の版で保存したもの) は使わない。
        return f.pendingFeedId != null && currentKey.equals(f.pendingFeedKey)
                && f.offeredFeedId != null && f.offeredFeedId.equals(f.pendingFeedOffer);
    }

    /**
     * 相手のフィードにつなげた。前の自分のフィード (別の id) は積み、作りかけと申し込みを外して共有中にする。
     * contentKey は作ったばかりで中身が今の時間割と同じときだけ渡す (使い回したときは null にして次の更新で PUT する)。
     */
    @NonNull
    static TimetableFriend linkAccepted(@NonNull TimetableFriend f, @NonNull List<FeedDeletion> queue, @NonNull String feedId,
                                        @NonNull String key, @NonNull String offered, @Nullable String contentKey) {
        if (f.myFeedId != null && !f.myFeedId.equals(feedId)) queueFeed(queue, f.myFeedId, f.myFeedVersion + 1, f.myFeedKey);
        return f.edit().myFeedId(feedId).myFeedKey(key).myFeedVersion(0).myFeedContentKey(contentKey).clearPendingFeed()
                .peerFeedId(offered).peerFeedVersion(null).offeredFeedId(null).sharingStopped(false).build();
    }

    /**
     * 相手のフィードにつなげないと分かった (410: 無い、409: 相手の欄がもうほかのフィードを指している、403・400)。
     * 作ったフィードを積み、申し込みを外す (dropOffer)。今の共有 (myFeedId) はそのまま。
     */
    @NonNull
    static TimetableFriend abandonAccept(@NonNull TimetableFriend f, @NonNull List<FeedDeletion> queue, @NonNull String feedId,
                                         @NonNull String key, @NonNull String offered) {
        queueFeed(queue, feedId, 1, key);
        if (offered.equals(f.offeredFeedId)) return dropOffer(f, queue);
        // 待っている間に申し込みが変わった: 新しい申し込みは残し、積んだフィードだけ作りかけから外す。
        return feedId.equals(f.pendingFeedId) ? f.edit().clearPendingFeed().build() : f;
    }

    /**
     * 申し込みを外す (断った・送り直しても通らない失敗)。作りかけのフィードも、作れていたかもしれないので鍵ごと積んで外す。
     * 残すと、共有中の友達は学期の掃除の対象にならないので、ずっと積まれない。
     */
    @NonNull
    static TimetableFriend dropOffer(@NonNull TimetableFriend f, @NonNull List<FeedDeletion> queue) {
        queueFeed(queue, f.pendingFeedId, f.pendingFeedVersion + 1, f.pendingFeedKey);
        return f.edit().offeredFeedId(null).clearPendingFeed().build();
    }

    /**
     * 鍵をなくしたとき (10.7 の補い)。自分のフィードを作った鍵が今の鍵 (無ければ null) と違う友達は、そのフィードを
     * 更新も削除もできないので、継続共有の項目を外して今期だけ (学期は今) にする。その鍵の消すのを待つフィードも外す
     * (署名しても 403 になるだけ。サーバーの 365 日の掃除で消える)。変えたら true。
     */
    static boolean dropLostKeyFeeds(@NonNull List<TimetableFriend> friends, @NonNull List<FeedDeletion> queue,
                                    @Nullable String currentKey, @NonNull String term) {
        boolean changed = false;
        for (int i = 0; i < friends.size(); i++) {
            TimetableFriend f = friends.get(i);
            boolean lostMine = f.myFeedId != null && (currentKey == null || !currentKey.equals(f.myFeedKey));
            boolean lostPending = f.pendingFeedId != null && (currentKey == null || !currentKey.equals(f.pendingFeedKey));
            if (!lostMine && !lostPending) continue;
            TimetableFriend.Builder b = f.edit();
            if (lostMine) b.clearMyFeed().peerFeedId(null).peerFeedVersion(null).term(term);
            if (lostPending) b.clearPendingFeed();
            friends.set(i, b.build());
            changed = true;
        }
        for (Iterator<FeedDeletion> it = queue.iterator(); it.hasNext(); ) {
            FeedDeletion d = it.next();
            if (currentKey != null && currentKey.equals(d.signingKey)) continue;
            it.remove();
            changed = true;
        }
        return changed;
    }

    /**
     * 前の版 (同じブランチの途中) は鍵を端末に 1 つだけ記録していた。その値を、鍵を持たない友達と並びの既定値にする
     * (1 回だけ。呼んだ側は記録を消す)。変えたら true。
     */
    static boolean adoptLegacyFeedKey(@NonNull List<TimetableFriend> friends, @NonNull List<FeedDeletion> queue,
                                      @NonNull String legacyKey) {
        boolean changed = false;
        for (int i = 0; i < friends.size(); i++) {
            TimetableFriend f = friends.get(i);
            if (f.myFeedId == null || f.myFeedKey != null) continue;
            friends.set(i, f.edit().myFeedKey(legacyKey).build());
            changed = true;
        }
        for (int i = 0; i < queue.size(); i++) {
            FeedDeletion d = queue.get(i);
            if (d.signingKey != null) continue;
            queue.set(i, new FeedDeletion(d.feedId, d.version, legacyKey));
            changed = true;
        }
        return changed;
    }

    /**
     * 古い版で保存した友達 (学期を持たない) は、今の学期の友達として学期を書き込む (利用者の判断)。受け取った日から
     * 決めると、更新した直後に前の学期の友達がまとめて消えてしまう。書き込んだので、次に学期が変わったときに消える。
     * 変えたら true。
     */
    static boolean adoptCurrentTerm(@NonNull List<TimetableFriend> friends, @NonNull String currentTerm) {
        boolean changed = false;
        for (int i = 0; i < friends.size(); i++) {
            TimetableFriend f = friends.get(i);
            if (f.term != null) continue;
            friends.set(i, f.edit().term(currentTerm).build());
            changed = true;
        }
        return changed;
    }

    /**
     * 学期で消す (10.4)。今期だけ・承認待ち・共有が止まりました の友達で、学期が今と違うものを消す。
     * 承認待ちの友達は自分のフィードを積む。共有中の友達は消さない。消したら true。
     */
    static boolean purgeByTerm(@NonNull List<TimetableFriend> friends, @NonNull List<FeedDeletion> queue,
                               @NonNull String currentTerm) {
        boolean changed = false;
        for (Iterator<TimetableFriend> it = friends.iterator(); it.hasNext(); ) {
            TimetableFriend f = it.next();
            if (f.state() == TimetableFriend.State.SHARING || f.effectiveTerm().equals(currentTerm)) continue;
            queueOwnFeed(queue, f);
            it.remove();
            changed = true;
        }
        return changed;
    }

    /** 相手のフィードが消えていた (410)。止まった印を立て、自分のフィードを積んで外す。今期だけとして残す (10.6 の 5)。 */
    @NonNull
    static TimetableFriend peerFeedGone(@NonNull TimetableFriend f, @NonNull List<FeedDeletion> queue, @NonNull String term) {
        queueOwnFeed(queue, f);
        return f.edit().sharingStopped(true).peerFeedId(null).peerFeedVersion(null).clearMyFeed().clearPendingFeed()
                .term(term).build();
    }

    /** 「共有を止める」(10.7)。自分のフィードを積み、継続共有の項目を外す。今の時間割は今期だけとして残す。 */
    @NonNull
    static TimetableFriend stopSharing(@NonNull TimetableFriend f, @NonNull List<FeedDeletion> queue, @NonNull String term) {
        queueOwnFeed(queue, f);
        return f.edit().clearMyFeed().clearPendingFeed().peerFeedId(null).peerFeedVersion(null)
                .offeredFeedId(null).term(term).build();
    }

    /**
     * 承認待ちの友達を、自分のフィードの相手の欄 (ownFeedPeer) につなげてよいか (10.6 の 4)。
     * 友達がまだ承認待ちで同じ自分のフィードを持ち、相手の欄のフィードが友達の鍵で署名されているときだけ。
     * expectedPeer (返事の申し込みの feedId) を渡したときは、相手の欄がその値のときだけ。
     */
    static boolean canLinkWaiting(@Nullable TimetableFriend f, @NonNull String myFeedId, @Nullable String ownFeedPeer,
                                  @Nullable String expectedPeer, @Nullable SignedShare.Verified peer) {
        if (f == null || f.state() != TimetableFriend.State.WAITING || !myFeedId.equals(f.myFeedId)) return false;
        if (ownFeedPeer == null || (expectedPeer != null && !expectedPeer.equals(ownFeedPeer))) return false;
        return peerFeedBelongsTo(f, peer);
    }

    /**
     * 承認待ちの友達を相手のフィードにつなぐ。申し込みは値によらず外す (つながったあとに残った申し込みに答えると、
     * 新しいフィードを作って今のフィードを消し、共有が切れる)。作りかけのフィード (前に作れたが、つなぐ応答を
     * 受け取れなかったもの) も鍵ごと積んで外す。残すと共有中の友達は学期の掃除の対象にならず、ずっと積まれない。
     */
    @NonNull
    static TimetableFriend linkPeer(@NonNull TimetableFriend f, @NonNull List<FeedDeletion> queue,
                                    @NonNull String peerFeedId) {
        queueFeed(queue, f.pendingFeedId, f.pendingFeedVersion + 1, f.pendingFeedKey);
        return f.edit().clearPendingFeed().peerFeedId(peerFeedId).peerFeedVersion(null).offeredFeedId(null).build();
    }

    /** 申し込みに答える必要がもう無いか。友達が消された、または申し込みが無くて共有中 (更新がつないだ)。 */
    static boolean alreadyAnswered(@Nullable TimetableFriend f) {
        return f == null || (f.offeredFeedId == null && f.peerFeedId != null);
    }

    /**
     * 申し込みに、今の自分のフィード (承認待ち・共有中) をそのまま相手として答えるか。新しく作らず、前のフィードも消さない。
     * お互いが同時に答えても FA.peer=FB・FB.peer=FA でそろう (作り直すと、どちらも相手が消したフィードを指して切れる)。
     * 共有中の友達の新しい申し込み (相手がフィードを作り直した) も同じ。前の相手のフィードは相手のものなので積まない。
     * 自分のフィードを今の鍵で作ったときだけ (違えば署名できないので、新しく作る)。
     */
    static boolean canReuseMyFeed(@Nullable TimetableFriend f, @Nullable String currentKey) {
        return f != null && f.myFeedId != null && currentKey != null && currentKey.equals(f.myFeedKey);
    }

    enum AcceptRoute { PENDING, MY_FEED, NEW_FEED }

    /**
     * 申し込みにどう答えるか。作りかけ (今の鍵) があればまずそれで送り直す: 前の setPeer が届いて応答だけ失われたなら、
     * 同じ値の書き直しで 200 になり、作りかけが自分のフィードになる (先に今のフィードで送ると 409 になり、相手は
     * 作りかけを追い続けてしまう)。次に今の自分のフィード、どちらも無ければ新しく作る。
     */
    @NonNull
    static AcceptRoute acceptRoute(@NonNull TimetableFriend f, @Nullable String currentKey) {
        if (currentKey != null && reusePending(f, currentKey)) return AcceptRoute.PENDING;
        if (canReuseMyFeed(f, currentKey)) return AcceptRoute.MY_FEED;
        return AcceptRoute.NEW_FEED;
    }

    /**
     * 自分のフィードがサーバーに無い (410、または相手の欄に書こうとして 400)。消すものは無いので外すだけ。
     * 継続共有の項目を外して今期だけ (学期は今) にする。申し込みは残す (新しく作って答え直せる)。
     */
    @NonNull
    static TimetableFriend dropOwnFeed(@NonNull TimetableFriend f, @NonNull String term) {
        return f.edit().clearMyFeed().peerFeedId(null).peerFeedVersion(null).term(term).build();
    }

    /** 今の自分のフィードで答えてつながった。作りかけがあれば積んで外す (linkPeer)。 */
    @NonNull
    static TimetableFriend acceptedWithMyFeed(@NonNull TimetableFriend f, @NonNull List<FeedDeletion> queue,
                                              @NonNull String offered) {
        return linkPeer(f, queue, offered);
    }

    /**
     * 承認待ちの友達の、相手の欄に書かれたフィードがもう無い (410)。相手が止めたので、自分のフィード (と作りかけ) を
     * 積んで外し、今期だけ (学期は今) にする。共有中にはしない。そのフィードの申し込みも外す。
     */
    @NonNull
    static TimetableFriend waitingPeerGone(@NonNull TimetableFriend f, @NonNull List<FeedDeletion> queue,
                                           @NonNull String goneFeedId, @NonNull String term) {
        queueOwnFeed(queue, f);
        TimetableFriend.Builder b = f.edit().clearMyFeed().clearPendingFeed().peerFeedId(null).peerFeedVersion(null)
                .term(term);
        if (goneFeedId.equals(f.offeredFeedId)) b.offeredFeedId(null);
        return b.build();
    }

    /** 相手の欄に書かれたフィードを、この友達のものとしてつないでよいか (10.6 の 4)。友達の鍵で署名されたものだけ。 */
    static boolean peerFeedBelongsTo(@NonNull TimetableFriend f, @Nullable SignedShare.Verified peer) {
        return f.publicKey != null && peer != null && f.publicKey.equals(peer.share.publicKey);
    }

    /**
     * 相手のフィードを取り込む (10.6 の 5)。公開鍵が友達のものと同じで、版番号が前に取り込んだものより大きいときだけ。
     * 取り込まないときは null。
     */
    @Nullable
    static TimetableFriend applyPeerFeed(@NonNull TimetableFriend f, @NonNull SignedShare.Verified feed, long version,
                                         long now) {
        if (f.publicKey == null || !f.publicKey.equals(feed.share.publicKey)) return null;
        if (f.peerFeedVersion != null && version <= f.peerFeedVersion) return null;
        // createdAt では比べない。版番号は相手の鍵の署名で守られていて、日時が進んだ端末から一度届くと、そのあとの
        // 時間割がずっと取り込まれなくなる。
        return f.edit().timetable(feed.timetable).sentName(feed.timetable.name).receivedAt(now)
                .term(ShareTerm.of(now)).peerFeedVersion(version).build();
    }

    /**
     * 消すのを待つフィードの DELETE の結果で、並びから外すか (10.3)。200・404・410 とほかの 4xx は外す
     * (送り直しても変わらない。403 と 409 も、積んだ削除の版番号は上がらないので通らない。365 日の掃除で消える)。
     * 通信の失敗 (0)・429・5xx と署名できなかったもの (-2) は残して次に送る。-1 は 200 で本文が読めなかったもの。
     */
    static boolean dropFromQueue(int status) {
        return !deletionFailed(status);
    }

    /**
     * 「すべてのデータを削除」で、フィードの DELETE を失敗と数えるか。並びが残すもの (送り直せば通るかもしれないもの)
     * だけが失敗。外すもの (400・403・409 など) は送り直しても通らないので、知らせても利用者にできることがない。
     */
    static boolean deletionFailed(int status) {
        if (status == 0 || status == 429 || status == -2) return true;
        if (status >= 500) return true;
        return !(status == -1 || status == 200 || (status >= 400 && status < 500));
    }

    // ── 保存の形 ──

    @NonNull
    static String friendsJson(@NonNull List<TimetableFriend> friends) {
        JsonArray array = new JsonArray();
        for (TimetableFriend f : friends) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.id);
            o.addProperty("name", f.name);
            o.addProperty("sentName", f.sentName);
            o.addProperty("receivedAt", f.receivedAt);
            o.add("timetable", f.timetable.toJson());
            if (f.publicKey != null) o.addProperty("publicKey", f.publicKey);
            if (f.term != null) o.addProperty("term", f.term);
            if (f.myFeedId != null) o.addProperty("myFeedId", f.myFeedId);
            if (f.myFeedKey != null) o.addProperty("myFeedKey", f.myFeedKey);
            if (f.myFeedVersion != 0) o.addProperty("myFeedVersion", f.myFeedVersion);
            if (f.myFeedContentKey != null) o.addProperty("myFeedContentKey", f.myFeedContentKey);
            if (f.peerFeedId != null) o.addProperty("peerFeedId", f.peerFeedId);
            if (f.peerFeedVersion != null) o.addProperty("peerFeedVersion", f.peerFeedVersion);
            if (f.offeredFeedId != null) o.addProperty("offeredFeedId", f.offeredFeedId);
            if (f.sharingStopped) o.addProperty("sharingStopped", true);
            if (f.pendingFeedId != null) o.addProperty("pendingFeedId", f.pendingFeedId);
            if (f.pendingFeedKey != null) o.addProperty("pendingFeedKey", f.pendingFeedKey);
            if (f.pendingFeedVersion != 0) o.addProperty("pendingFeedVersion", f.pendingFeedVersion);
            if (f.pendingFeedOffer != null) o.addProperty("pendingFeedOffer", f.pendingFeedOffer);
            array.add(o);
        }
        return array.toString();
    }

    /** 保存した友達を読む。v3 の項目は無くてもよい (古い版で保存した友達)。読めない友達は飛ばす。 */
    @NonNull
    static List<TimetableFriend> readFriends(@Nullable String json) {
        List<TimetableFriend> result = new ArrayList<>();
        if (json == null) return result;
        JsonArray array;
        try {
            array = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            // 壊れた保存は読まない (受け取り直せば戻る)。
            return result;
        }
        for (JsonElement e : array) {
            try {
                JsonObject o = e.getAsJsonObject();
                SharedTimetable t = SharedTimetable.normalize(o.get("timetable"));
                if (t == null) continue;
                result.add(new TimetableFriend(o.get("id").getAsString(), o.get("name").getAsString(),
                        o.get("sentName").getAsString(), o.get("receivedAt").getAsLong(), t,
                        optString(o, "publicKey"), optString(o, "term"), optString(o, "myFeedId"),
                        optString(o, "myFeedKey"),
                        o.has("myFeedVersion") ? o.get("myFeedVersion").getAsLong() : 0,
                        optString(o, "myFeedContentKey"), optString(o, "peerFeedId"),
                        o.has("peerFeedVersion") ? o.get("peerFeedVersion").getAsLong() : null,
                        optString(o, "offeredFeedId"),
                        o.has("sharingStopped") && o.get("sharingStopped").getAsBoolean(),
                        optString(o, "pendingFeedId"), optString(o, "pendingFeedKey"),
                        o.has("pendingFeedVersion") ? o.get("pendingFeedVersion").getAsLong() : 0,
                        optString(o, "pendingFeedOffer")));
            } catch (RuntimeException ignored) {
                // この 1 人だけ読まない。ほかの友達まで消さない。
            }
        }
        return result;
    }

    @NonNull
    static String deletionsJson(@NonNull List<FeedDeletion> queue) {
        JsonArray array = new JsonArray();
        for (FeedDeletion d : queue) {
            JsonObject o = new JsonObject();
            o.addProperty("feedId", d.feedId);
            o.addProperty("version", d.version);
            if (d.signingKey != null) o.addProperty("signingKey", d.signingKey);
            array.add(o);
        }
        return array.toString();
    }

    @NonNull
    static List<FeedDeletion> readDeletions(@Nullable String json) {
        List<FeedDeletion> result = new ArrayList<>();
        if (json == null) return result;
        try {
            for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                result.add(new FeedDeletion(o.get("feedId").getAsString(), o.get("version").getAsLong(),
                        optString(o, "signingKey")));
            }
        } catch (RuntimeException ignored) {
            // 壊れた並びは読まない。消せなかったフィードは 365 日の掃除で消える。
        }
        return result;
    }

    @Nullable
    private static String optString(@NonNull JsonObject o, @NonNull String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
