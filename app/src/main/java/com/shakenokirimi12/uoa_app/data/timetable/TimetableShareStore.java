package com.shakenokirimi12.uoa_app.data.timetable;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.services.TimetableShareApi;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 友達の時間割と、自分が出している共有を持つ。画面はすべてこれを見る。iOS の TimetableShareStore と同じ。
 * 通信するメソッド (startSharing / open / accept / collectReplies / acceptOffer / refresh / flushFeedDeletions /
 * stopSharing) はメインスレッドから呼ばない。
 *
 * v3 (docs/timetable-share-v3.md の 10): 共有にはこの端末の鍵で署名し、同じ鍵の共有は同じ友達として更新する。
 * 「今後も共有し続ける」はサーバーのフィードで続ける。決まりそのものは TimetableShareRules にある。
 */
public final class TimetableShareStore {
    private static final String TAG = "TimetableShareStore";
    static final String PREFS = "timetable_share";
    private static final String KEY_FRIENDS = "friends_v1";
    private static final String KEY_OUTGOING = "outgoing_v1";
    private static final String KEY_MY_NAME = "my_name";
    private static final String KEY_FEED_DELETIONS = "feed_deletions_v1";
    /**
     * 同じブランチの前の版が、フィードを作った鍵を端末に 1 つだけ記録していた場所。今は友達ごと・並びの項目ごとに持つ。
     * 起動したときに 1 回だけ、鍵を持たない友達と並びの既定値にして消す。
     */
    private static final String KEY_LEGACY_FEED_SIGNING_KEY = "feed_signing_key_v1";
    /** 名前の入力欄の上限。送るときは SharedTimetable.MAX_NAME で切る。 */
    public static final int MY_NAME_MAX = 20;
    /** 名前の無い友達に付ける名前 (保存する)。 */
    public static String unnamed() {
        return com.shakenokirimi12.uoa_app.services.AppStrings.get(com.shakenokirimi12.uoa_app.R.string.timetable_friend_unnamed);
    }

    private static TimetableShareStore instance;

    private final SharedPreferences prefs;
    private final PreferenceManager secure;
    private final TimetableShareApi api = new TimetableShareApi();
    private final ShareSigner signer = new KeyStoreShareSigner();
    private final SecureRandom random = new SecureRandom();
    private final List<TimetableFriend> friends = new ArrayList<>();
    /** 消すのを待つ自分のフィード (10.3)。friends と同じく this で守る。 */
    private final List<FeedDeletion> deletions = new ArrayList<>();
    /** フィードの通信 (更新・削除・承諾) を 1 本ずつにする。同じフィードに版番号を取り合わせないため。 */
    private final Object feedLock = new Object();
    /** 一覧を開いたときの更新は同時に 2 本走らせない (10.6)。 */
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private TimetableShareStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secure = PreferenceManager.getInstance(context.getApplicationContext());
        friends.addAll(TimetableShareRules.readFriends(prefs.getString(KEY_FRIENDS, null)));
        deletions.addAll(TimetableShareRules.readDeletions(prefs.getString(KEY_FEED_DELETIONS, null)));
        String legacyKey = prefs.getString(KEY_LEGACY_FEED_SIGNING_KEY, null);
        if (legacyKey != null) {
            TimetableShareRules.adoptLegacyFeedKey(friends, deletions, legacyKey);
            persistDeletions();
            persistFriends();
            prefs.edit().remove(KEY_LEGACY_FEED_SIGNING_KEY).apply();
        }
        if (TimetableShareRules.adoptCurrentTerm(friends, ShareTerm.of(System.currentTimeMillis()))) persistFriends();
        // 起動したときに、学期の変わった今期だけの友達を消す (10.4)。積んだフィードは一覧を開いたときに消す。
        purgeExpiredTerms();
    }

    public static synchronized TimetableShareStore getInstance(@NonNull Context context) {
        if (instance == null) instance = new TimetableShareStore(context);
        return instance;
    }

    @NonNull
    public TimetableShareApi api() { return api; }

    // ── 友達 ──

    @NonNull
    public synchronized List<TimetableFriend> friends() { return new ArrayList<>(friends); }

    @Nullable
    public synchronized TimetableFriend friend(@NonNull String id) {
        int i = TimetableShareRules.index(friends, id);
        return i >= 0 ? friends.get(i) : null;
    }

    /** 新しい友達を先頭に足す (保存先に触らない)。名前が無ければ「表示名なし」を付けて保存する。 */
    @NonNull
    static TimetableFriend add(@NonNull List<TimetableFriend> friends, @NonNull SharedTimetable timetable,
                               long receivedAt, @NonNull String newId) {
        TimetableFriend f = new TimetableFriend(newId, timetable.name.isEmpty() ? unnamed() : timetable.name,
                timetable.name, receivedAt, timetable);
        friends.add(0, f);
        return f;
    }

    public synchronized void rename(@NonNull String id, @NonNull String name) {
        String cleaned = SharedTimetable.sanitize(name, SharedTimetable.MAX_NAME);
        if (cleaned.isEmpty()) return;
        int i = TimetableShareRules.index(friends, id);
        if (i < 0) return;
        friends.set(i, friends.get(i).edit().name(cleaned).build());
        persistFriends();
    }

    /** 友達を消す。この相手宛ての自分のフィードがあれば、先に「消すのを待つフィード」に積む (10.7)。 */
    public synchronized void remove(@NonNull String id) {
        int i = TimetableShareRules.index(friends, id);
        if (i < 0) return;
        TimetableShareRules.queueOwnFeed(deletions, friends.get(i));
        friends.remove(i);
        persistDeletions();
        persistFriends();
    }

    /** 学期の変わった今期だけ・承認待ち・止まった友達を消す (10.4)。 */
    public synchronized void purgeExpiredTerms() {
        if (TimetableShareRules.purgeByTerm(friends, deletions, ShareTerm.of(System.currentTimeMillis()))) {
            persistDeletions();
            persistFriends();
        }
    }

    private void persistFriends() {
        prefs.edit().putString(KEY_FRIENDS, TimetableShareRules.friendsJson(friends)).apply();
    }

    private void persistDeletions() {
        prefs.edit().putString(KEY_FEED_DELETIONS, TimetableShareRules.deletionsJson(deletions)).apply();
    }

    /** id の友達を f に置き換える (待っている間に消された友達は戻さない)。this を持って呼ぶ。 */
    private void replace(@NonNull TimetableFriend f) {
        int i = TimetableShareRules.index(friends, f.id);
        if (i >= 0) friends.set(i, f);
    }

    // ── 自分 ──

    @NonNull
    public String myName() { return prefs.getString(KEY_MY_NAME, ""); }

    public void setMyName(@NonNull String name) {
        prefs.edit().putString(KEY_MY_NAME, SharedTimetable.sanitize(name, MY_NAME_MAX)).apply();
    }

    /** 自分の時間割。授業が 1 つも無ければ null (送れない)。createdAt は今 (署名付きの共有はこれを毎回作り直す)。 */
    @Nullable
    public SharedTimetable myTimetable(@NonNull List<CalendarEvent> events, long nowMs) {
        List<SharedClass> classes = TimetableDerivation.weeklyClasses(events, nowMs);
        return classes.isEmpty() ? null : SharedTimetable.create(myName(), nowMs, classes);
    }

    // ── 署名 ──

    /** 署名付きの共有を作る。鍵が使えない (KeyStore の不具合など) ときは、この端末では共有できないものとして扱う。 */
    @NonNull
    private SignedShare signShare(@NonNull SharedTimetable timetable) throws TimetableShareApi.Failure {
        try {
            return SignedShare.sign(timetable, signer);
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "signing a share failed: " + e.getClass().getSimpleName());
            throw TimetableShareApi.Failure.cannotStoreOnDevice();
        }
    }

    @NonNull
    private String signFeed(@NonNull String op, @NonNull String feedId, long version, @NonNull String content)
            throws TimetableShareApi.Failure {
        try {
            return ShareCrypto.base64url(signer.sign(ShareCrypto.feedSigningInput(op, feedId, version, content)));
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "signing a feed operation failed: " + e.getClass().getSimpleName());
            throw TimetableShareApi.Failure.cannotStoreOnDevice();
        }
    }

    /**
     * 今ある鍵だけで共有に署名する (一覧の更新の PUT)。「すべてのデータを削除」が鍵を消したあとに、更新が鍵を
     * 作り直さないように。鍵が無い・使えないときは Failure (-2)。
     */
    @NonNull
    private SignedShare signShareWithExistingKey(@NonNull SharedTimetable timetable) throws TimetableShareApi.Failure {
        SignedShare s;
        try {
            s = SignedShare.signWithExistingKey(timetable, signer);
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "signing a share failed: " + e.getClass().getSimpleName());
            throw TimetableShareApi.Failure.cannotStoreOnDevice();
        }
        if (s == null) throw TimetableShareApi.Failure.cannotStoreOnDevice();
        return s;
    }

    /** フィードを作った鍵 (key) が今の鍵のときだけ署名する (作らない)。違う・無い・使えないときは Failure (-2)。 */
    @NonNull
    private String signFeedWithKey(@Nullable String key, @NonNull String op, @NonNull String feedId, long version,
                                   @NonNull String content) throws TimetableShareApi.Failure {
        byte[] sig = null;
        try {
            if (key != null) sig = signer.signIfKeyIs(key, ShareCrypto.feedSigningInput(op, feedId, version, content));
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "signing a feed operation failed: " + e.getClass().getSimpleName());
        }
        if (sig == null) throw TimetableShareApi.Failure.cannotStoreOnDevice();
        return ShareCrypto.base64url(sig);
    }

    /** 自分の公開鍵。まだ鍵を作っていなければ null (作らない)。 */
    @Nullable
    private String myPublicKey() {
        try {
            return signer.existingPublicKey();
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /** フィードの id。こちらが作る 128 ビットの乱数 (32 桁の 16 進)。 */
    @NonNull
    private String newFeedId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte b : bytes) id.append(String.format("%02x", b & 0xff));
        return id.toString();
    }

    /** フィードを作り、その id を返す。重なったとき (409) は 1 回だけ id を作り直す。鍵は share.publicKey。 */
    @NonNull
    private String createFeed(@NonNull SignedShare share) throws TimetableShareApi.Failure {
        for (int attempt = 0; ; attempt++) {
            String feedId = newFeedId();
            try {
                api.createFeed(feedId, share, signFeed("create", feedId, 0, share.timetable));
                return feedId;
            } catch (TimetableShareApi.Failure e) {
                if (e.status != 409 || attempt >= 1) throw e;
            }
        }
    }

    // ── 自分が出す共有 ──

    @Nullable
    public synchronized OutgoingShare outgoing() {
        String json = prefs.getString(KEY_OUTGOING, null);
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            List<String> seen = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("seenReplyIds")) seen.add(e.getAsString());
            String key = o.has("contentKey") ? o.get("contentKey").getAsString() : "";
            boolean signed = o.has("signed") && o.get("signed").getAsBoolean();
            return new OutgoingShare(o.get("shareId").getAsString(), o.get("expiresAt").getAsLong(), key, seen, signed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void setOutgoing(@NonNull OutgoingShare share) {
        JsonObject o = new JsonObject();
        o.addProperty("shareId", share.shareId);
        o.addProperty("expiresAt", share.expiresAt);
        o.addProperty("contentKey", share.contentKey);
        JsonArray seen = new JsonArray();
        for (String id : share.seenReplyIds) seen.add(id);
        o.add("seenReplyIds", seen);
        o.addProperty("signed", share.signed);
        prefs.edit().putString(KEY_OUTGOING, o.toString()).apply();
    }

    /** 手元の共有とトークンだけを消す (サーバーの共有は期限で消える)。 */
    public synchronized void forgetOutgoing() {
        prefs.edit().remove(KEY_OUTGOING).apply();
        secure.setTimetableShareOwnerToken(null);
    }

    @Nullable
    private String ownerToken() {
        String t = secure.getTimetableShareOwnerToken();
        return t == null || t.isEmpty() ? null : t;
    }

    /**
     * 有効な署名付きの共有があり、時間割 (名前と授業) が同じならそれを、無ければ新しく作る。
     * 署名なしで作った古い共有は使い回さない (10.3)。
     */
    @NonNull
    public OutgoingShare startSharing(@NonNull SharedTimetable timetable, boolean forceNew) throws TimetableShareApi.Failure {
        String key = contentKey(timetable);
        OutgoingShare current = outgoing();
        if (!forceNew && current != null && current.signed && current.contentKey.equals(key)
                && !current.isExpired(System.currentTimeMillis() + 10 * 60_000L) && ownerToken() != null) {
            return current;
        }
        // 署名は通信より先に作る (鍵が使えないなら、古い共有を止めずに失敗を返す)。
        SignedShare signed = signShare(timetable);
        // 作り直す前に、古い共有に届いている返事を取り込む (止めると返事も消える)。
        // 古い共有を止められなくても (通信の失敗)、新しい共有は作る。古い方は期限で消える。
        try {
            collectReplies();
        } catch (TimetableShareApi.Failure ignored) {
            // 取り込めなかった返事は、古い共有と一緒に期限で消える。
        }
        if (!stopSharing()) forgetOutgoing();
        TimetableShareApi.Created created = api.create(signed);
        if (!secure.setTimetableShareOwnerToken(created.ownerToken)) {
            // トークンを置けないと返事を読めず、後で「期限切れ」と取り違える。サーバーの共有は消して、始めなかったことにする。
            try {
                api.delete(created.shareId, created.ownerToken);
            } catch (TimetableShareApi.Failure ignored) {
                // 消せなかった共有は 24 時間で切れる。
            }
            throw TimetableShareApi.Failure.cannotStoreOnDevice();
        }
        OutgoingShare share = new OutgoingShare(created.shareId, created.expiresAt, key, new ArrayList<>(), true);
        synchronized (this) { setOutgoing(share); }
        return share;
    }

    /** 名前と授業の組み合わせ (作った日時は除く)。同じなら同じ共有を使い回せる。iOS の contentKey と同じ。 */
    @NonNull
    public static String contentKey(@NonNull SharedTimetable timetable) {
        List<String> parts = new ArrayList<>();
        parts.add(timetable.name);
        for (SharedClass c : timetable.classes) parts.add(c.day + "|" + c.start + "|" + c.end + "|" + c.title + "|" + c.room);
        return String.join("\n", parts);
    }

    /**
     * 届いた返事を友達として保存し、新しく届いた (または更新した) 友達を返す。期限切れなら共有を片付ける。
     * 署名付きの返事は確かめ、合わないものと自分の鍵のものは取り込まない (見たことにする)。
     * 返事に feedId があれば「今後も共有しますか」と聞くために offeredFeedId に持つ (10.5)。
     */
    @NonNull
    public List<TimetableFriend> collectReplies() throws TimetableShareApi.Failure {
        OutgoingShare current = outgoing();
        List<TimetableFriend> received = new ArrayList<>();
        if (current == null) return received;
        String token = ownerToken();
        if (current.isExpired(System.currentTimeMillis()) || token == null) {
            forgetIfStill(current.shareId);
            return received;
        }
        List<TimetableShareApi.Reply> replies;
        try {
            replies = api.replies(current.shareId, token);
        } catch (TimetableShareApi.Failure e) {
            if (e.isExpired()) {
                // 待っている間に作り直していたら、新しい共有は消さない。
                forgetIfStill(current.shareId);
                return received;
            }
            throw e;
        }
        String myKey = myPublicKey();
        List<String[]> held = new ArrayList<>();
        synchronized (this) {
            // 待っている間に止めた・作り直したなら、古い共有の返事は取り込まない。
            OutgoingShare share = outgoing();
            if (share == null || !share.shareId.equals(current.shareId)) return received;
            List<String> seen = new ArrayList<>(share.seenReplyIds);
            boolean changed = false;
            for (TimetableShareApi.Reply r : replies) {
                if (seen.contains(r.replyId)) continue;
                seen.add(r.replyId);
                changed = true;
                long now = System.currentTimeMillis();
                TimetableFriend f;
                if (r.share != null) {
                    SignedShare.Verified v = SignedShare.verify(r.share);
                    if (v == null || v.share.publicKey.equals(myKey)) continue;
                    f = TimetableShareRules.receive(friends, v.timetable, v.share.publicKey, now,
                            UUID.randomUUID().toString()).friend;
                    if (r.feedId != null && f.state() == TimetableFriend.State.WAITING) {
                        // 承認待ちの友達の申し込みは、自分のフィードの相手の欄を確かめるまで持たない (その間に聞かない)。
                        held.add(new String[] {f.id, f.myFeedId, r.feedId});
                    } else {
                        f = TimetableShareRules.offer(f, r.feedId);
                        replace(f);
                    }
                } else if (r.timetable != null) {
                    f = TimetableShareRules.receive(friends, r.timetable, null, now, UUID.randomUUID().toString()).friend;
                } else {
                    continue;
                }
                // 同じ人の返事が 2 つあっても、画面には 1 回だけ出す。
                for (int i = received.size() - 1; i >= 0; i--) if (received.get(i).id.equals(f.id)) received.remove(i);
                received.add(f);
            }
            if (changed) {
                persistFriends();
                setOutgoing(new OutgoingShare(share.shareId, share.expiresAt, share.contentKey, seen, share.signed));
            }
        }
        // 承認待ちの友達から、こちらのフィードの相手の欄に書かれているフィードの申し込みが来た (お互いの QR を読み合った)。
        // それはもうつながっている相手のフィードなので、申し込みとして聞かずにつなぐ。答えると (setPeer で) 新しい
        // フィードを作って前のフィードを消し、どちらからも 410 になって共有が切れる。
        // つながらなければ、そこで初めて申し込みとして持つ (相手の欄が空・別の値・相手の鍵でない)。
        if (!held.isEmpty()) {
            synchronized (feedLock) {
                for (String[] w : held) {
                    if (linkWaiting(w[0], w[1], w[2]) != LinkResult.NOT_LINKED) continue;
                    synchronized (this) {
                        TimetableFriend f = friend(w[0]);
                        if (f == null || f.peerFeedId != null) continue;
                        replace(TimetableShareRules.offer(f, w[2]));
                        persistFriends();
                    }
                }
            }
        }
        return received;
    }

    /**
     * 共有を止める。サーバーから消せたとき (もう無いときを含む) だけ手元も片付けて true を返す。
     * 通信できなかったときは、手元の共有とトークンを残して false を返す (もう一度止められるように)。
     */
    public boolean stopSharing() {
        OutgoingShare current = outgoing();
        if (current == null) return true;
        String token = ownerToken();
        if (token != null) {
            try {
                api.delete(current.shareId, token);
            } catch (TimetableShareApi.Failure e) {
                if (!e.isExpired()) return false;
                // 既に切れている・消えている。
            }
        }
        forgetIfStill(current.shareId);
        return true;
    }

    private synchronized void forgetIfStill(@NonNull String shareId) {
        OutgoingShare now = outgoing();
        if (now != null && now.shareId.equals(shareId)) forgetOutgoing();
    }

    // ── 受け取る (B の側、10.5) ──

    /** 開いた共有。share があれば署名を確かめたもので、timetable はその署名された時間割。 */
    public static final class Incoming {
        @NonNull public final SharedTimetable timetable;
        /** 確かめた署名付きの共有。v2 (署名なし) なら null で、継続共有は選べない。 */
        @Nullable public final SignedShare share;
        /** 同じ鍵の友達に、自分のフィードがもうある (選ばせずに「すでに共有し続けています」を出す)。 */
        public final boolean alreadySharing;

        Incoming(@NonNull SharedTimetable timetable, @Nullable SignedShare share, boolean alreadySharing) {
            this.timetable = timetable;
            this.share = share;
            this.alreadySharing = alreadySharing;
        }
    }

    /** 共有を開く。自分の QR・署名が合わない共有は Failure (保存しない)。 */
    @NonNull
    public Incoming open(@NonNull String shareId) throws TimetableShareApi.Failure {
        OutgoingShare mine = outgoing();
        if (mine != null && mine.shareId.equals(shareId)) throw TimetableShareApi.Failure.ownShare();
        TimetableShareApi.Fetched fetched = api.fetch(shareId);
        if (fetched.share != null) {
            SignedShare.Verified v = SignedShare.verify(fetched.share);
            if (v == null) throw TimetableShareApi.Failure.unverified();
            if (v.share.publicKey.equals(myPublicKey())) throw TimetableShareApi.Failure.ownShare();
            String current = myPublicKey();
            boolean already;
            synchronized (this) {
                int i = TimetableShareRules.indexByKey(friends, v.share.publicKey);
                already = TimetableShareRules.alreadySharing(i >= 0 ? friends.get(i) : null, current);
            }
            return new Incoming(v.timetable, v.share, already);
        }
        if (fetched.timetable == null) throw TimetableShareApi.Failure.of(-1);
        return new Incoming(fetched.timetable, null, false);
    }

    /** accept の結果。終わりの画面の文面はこれで決める。 */
    public static final class Accepted {
        @NonNull public final TimetableFriend friend;
        /** 同じ鍵の友達の時間割を置き換えた (「時間割を更新しました」)。同じ鍵でも古い共有なら false。 */
        public final boolean updated;
        public final boolean sent;
        @Nullable public final TimetableShareApi.Failure sendError;
        /** 「今後も共有し続ける」を選んだが、フィードを作れなかった (友達は今期だけ)。 */
        public final boolean sharingNotStarted;
        /** この操作でフィードを作り、返事で申し込んだ (「相手の同意を待っています」はこのときだけ)。 */
        public final boolean startedWaiting;

        Accepted(@NonNull TimetableFriend friend, boolean updated, boolean sent,
                 @Nullable TimetableShareApi.Failure sendError, boolean sharingNotStarted, boolean startedWaiting) {
            this.friend = friend;
            this.updated = updated;
            this.sent = sent;
            this.sendError = sendError;
            this.sharingNotStarted = sharingNotStarted;
            this.startedWaiting = startedWaiting;
        }
    }

    /**
     * 開いた共有を保存し、sendBack なら自分の時間割 (署名付き) を返事として送る。keepSharing (署名付きのときだけ) なら、
     * 相手宛てのフィードを作って返事に feedId を付ける。同じ鍵の友達にフィードがもうあれば、その id を付ける。
     */
    @NonNull
    public Accepted accept(@NonNull String shareId, @NonNull Incoming incoming, boolean sendBack, boolean keepSharing,
                           @Nullable SharedTimetable mine) {
        TimetableShareRules.Received received;
        synchronized (this) {
            received = TimetableShareRules.receive(friends, incoming.timetable,
                    incoming.share != null ? incoming.share.publicKey : null, System.currentTimeMillis(),
                    UUID.randomUUID().toString());
            persistFriends();
        }
        String friendId = received.friend.id;
        boolean sent = false;
        TimetableShareApi.Failure sendError = null;
        boolean notStarted = false;
        boolean startedWaiting = false;
        if (sendBack && mine != null) {
            try {
                SignedShare own = signShare(mine);
                String feedId = null;
                boolean created = false;
                // 今の鍵で作ったフィードだけ使い回す。鍵を失ったフィードは更新できないので、相手に教えても止まったままになる。
                if (incoming.share != null && received.friend.myFeedId != null
                        && own.publicKey.equals(received.friend.myFeedKey)) {
                    feedId = received.friend.myFeedId;
                } else if (incoming.share != null && keepSharing) {
                    try {
                        feedId = createFeed(own);
                        created = true;
                        synchronized (this) {
                            TimetableFriend f = friend(friendId);
                            if (f != null) {
                                replace(f.edit().myFeedId(feedId).myFeedKey(own.publicKey).myFeedVersion(0)
                                        .myFeedContentKey(contentKey(mine)).build());
                                persistFriends();
                            } else {
                                // 待っている間に消された。作ったフィードは片付ける。
                                deletions.add(new FeedDeletion(feedId, 1, own.publicKey));
                                persistDeletions();
                                feedId = null;
                                created = false;
                            }
                        }
                    } catch (TimetableShareApi.Failure e) {
                        notStarted = true;
                    }
                }
                try {
                    api.reply(shareId, own, feedId);
                    sent = true;
                    startedWaiting = created;
                } catch (TimetableShareApi.Failure e) {
                    sendError = e;
                    if (created) {
                        // 申し込みが届かなかったフィードは相手に見つけてもらえない。積んで消し、今期だけに戻す。
                        synchronized (this) {
                            TimetableFriend f = friend(friendId);
                            if (f != null && feedId.equals(f.myFeedId)) {
                                TimetableShareRules.queueFeed(deletions, f.myFeedId, f.myFeedVersion + 1, f.myFeedKey);
                                replace(f.edit().clearMyFeed().build());
                                persistFriends();
                                persistDeletions();
                            }
                        }
                    }
                }
            } catch (TimetableShareApi.Failure e) {
                sendError = e;
            }
        }
        TimetableFriend latest = friend(friendId);
        return new Accepted(latest != null ? latest : received.friend, received.replaced, sent, sendError, notStarted,
                startedWaiting);
    }

    // ── 申し込みに答える (A の側、10.5) ──

    /** 「今後も共有しますか」と聞く友達 (1 人ずつ聞く)。skip (この画面で始められなかった友達) は飛ばす。無ければ null。 */
    @Nullable
    public synchronized TimetableFriend nextOffer(@NonNull java.util.Set<String> skip) {
        for (TimetableFriend f : friends) if (f.offeredFeedId != null && !skip.contains(f.id)) return f;
        return null;
    }

    /** 「今期だけにする」。相手には伝えない (相手は承認待ちのまま)。作りかけのフィードがあれば積む。 */
    public synchronized void declineOffer(@NonNull String friendId) {
        TimetableFriend f = friend(friendId);
        if (f == null || f.offeredFeedId == null) return;
        replace(TimetableShareRules.dropOffer(f, deletions));
        persistDeletions();
        persistFriends();
    }

    /**
     * 「共有し続ける」。相手のフィードの相手の欄に自分のフィードの id を書く。できたら true。
     * 作りかけ (今の鍵) があれば、まずそれで送り直す。次に、今の鍵で作った myFeedId があれば (承認待ち・共有中)
     * そのフィードをそのまま使う (acceptWithMyFeed)。それ以外は新しく作る (TimetableShareRules.acceptRoute)。
     * 友達が消された・申し込みが無くてもう共有中 (更新がつないだ) のときも true。
     * 作る前に id を友達に「作りかけ」として残し、作る・つなぐの応答を受け取れなかったとき (通信の失敗など) は残したまま
     * false を返す。次に答えるときは新しく作らずに同じ id で送り直す (作れていたら 409、つなげていたら同じ値の書き直しで 200)。
     * 相手のフィードが無い (410)・相手の欄がほかのフィードで埋まっている (409) ときは、作りかけを積んで申し込みを外す。
     * 自分の時間割が無いときも false (申し込みは残す)。待っている間に友達が消されたときは、作ったフィードを積んで true。
     */
    public boolean acceptOffer(@NonNull String friendId, @Nullable SharedTimetable mine) {
        TimetableFriend start = friend(friendId);
        // 消された友達・もうつながった友達 (聞いている間に更新がつないだ) には答えなくてよい。失敗とは知らせない。
        if (TimetableShareRules.alreadyAnswered(start)) return true;
        if (start.offeredFeedId == null) return false;
        String offered = start.offeredFeedId;
        synchronized (feedLock) {
            dropFeedsOfLostKey();
            TimetableFriend now = friend(friendId);
            if (TimetableShareRules.alreadyAnswered(now)) return true;
            if (!offered.equals(now.offeredFeedId)) return false;
            if (TimetableShareRules.acceptRoute(now, myPublicKey()) == TimetableShareRules.AcceptRoute.MY_FEED) {
                Boolean done = acceptWithMyFeed(friendId, offered, now.myFeedId, now.myFeedKey);
                // null: 自分のフィードがもう無かった (外した)。同じ答えの中で、新しく作って答え直す。
                if (done != null) return done;
            }
            if (mine == null) return false;
            SignedShare own;
            try {
                own = signShare(mine);
            } catch (TimetableShareApi.Failure e) {
                return false;
            }
            String feedId = null;
            boolean createdNow = false;
            for (int attempt = 0; attempt < 2 && feedId == null; attempt++) {
                String candidate;
                boolean reused;
                synchronized (this) {
                    TimetableFriend f = friend(friendId);
                    if (TimetableShareRules.alreadyAnswered(f)) return true;
                    if (!offered.equals(f.offeredFeedId)) return false;
                    reused = TimetableShareRules.reusePending(f, own.publicKey);
                    candidate = reused ? f.pendingFeedId : newFeedId();
                    if (!reused) {
                        // 前の作りかけ (別の申し込み・前の鍵) は、作れていたかもしれないので鍵ごと積む。
                        TimetableShareRules.queueFeed(deletions, f.pendingFeedId, f.pendingFeedVersion + 1, f.pendingFeedKey);
                        // 送る前に残す。応答を受け取れなくても、作ったかもしれないフィードを見失わない。
                        replace(f.edit().pendingFeedId(candidate).pendingFeedKey(own.publicKey).pendingFeedVersion(0)
                                .pendingFeedOffer(offered).build());
                        persistDeletions();
                        persistFriends();
                    }
                }
                try {
                    api.createFeed(candidate, own, signFeed("create", candidate, 0, own.timetable));
                    feedId = candidate;
                    createdNow = true;
                } catch (TimetableShareApi.Failure e) {
                    if (e.status == 409 && reused) {
                        // 前に答えたときに作れていた (応答だけ届かなかった)。中身は古いかもしれないので、次の更新で PUT する。
                        feedId = candidate;
                    } else if (e.status == 409) {
                        // ほかのフィードと id が重なった。作りかけを外して、id を作り直す。
                        synchronized (this) {
                            TimetableFriend f = friend(friendId);
                            if (f != null && candidate.equals(f.pendingFeedId)) {
                                replace(f.edit().clearPendingFeed().build());
                                persistFriends();
                            }
                        }
                    } else {
                        return false;
                    }
                }
            }
            if (feedId == null) return false;
            try {
                // 書き込むのは相手のフィードの欄、書く値は自分のフィード。自分のフィードの鍵で署名する
                // (その鍵を持つ人だけが、そのフィードを相手として名乗れる)。
                api.setPeer(offered, feedId, signFeedWithKey(own.publicKey, "peer", offered, 0, feedId));
            } catch (TimetableShareApi.Failure e) {
                if (!e.isGone() && e.status != 409 && e.status != 403 && e.status != 400) return false;
                // 410: 相手のフィードが無い。409: 相手の欄がもうほかのフィードを指している (同じ値なら 200 が返る)。
                // 403 (署名が違う)・400 (署名なし・同じ鍵・自分のフィードが無い): 送り直しても通らない。
                // どれも、このフィードはつなげない。通信の失敗・署名できなかった (-2) ときは作りかけを残す。
                synchronized (this) {
                    TimetableFriend f = friend(friendId);
                    if (f != null) {
                        replace(TimetableShareRules.abandonAccept(f, deletions, feedId, own.publicKey, offered));
                    } else {
                        TimetableShareRules.queueFeed(deletions, feedId, 1, own.publicKey);
                    }
                    persistDeletions();
                    persistFriends();
                }
                return false;
            }
            synchronized (this) {
                TimetableFriend f = friend(friendId);
                if (f == null) {
                    // 待っている間に消された。作ったフィードは積んで消す (相手からは止まったように見える)。
                    // 友達を消したのは利用者なので、失敗とは知らせない。
                    TimetableShareRules.queueFeed(deletions, feedId, 1, own.publicKey);
                    persistDeletions();
                    return true;
                }
                // 前のフィード (作り直しの申し込みなど) は、新しい方がつながってから積む。失敗したときに今の共有を壊さない。
                replace(TimetableShareRules.linkAccepted(f, deletions, feedId, own.publicKey, offered,
                        createdNow ? contentKey(mine) : null));
                persistDeletions();
                persistFriends();
            }
            return true;
        }
    }

    /**
     * 申し込みに、今の自分のフィード (myFeedId) で答える (承認待ち・共有中)。相手のフィードの相手の欄に myFeedId を書き、
     * つながったら true。410・409・403 (送り直しても通らない) は申し込みを外す (自分のフィードは消さない)。
     * 400 はサーバーが自分のフィードを見つけられなかった (30 日の掃除など) ので、自分のフィードを外して null
     * (呼んだ側が新しく作って答え直す)。通信の失敗・署名できなかったときは申し込みを残して false。feedLock を持って呼ぶ。
     */
    @Nullable
    private Boolean acceptWithMyFeed(@NonNull String friendId, @NonNull String offered, @NonNull String myFeedId,
                                     @NonNull String key) {
        try {
            api.setPeer(offered, myFeedId, signFeedWithKey(key, "peer", offered, 0, myFeedId));
        } catch (TimetableShareApi.Failure e) {
            if (e.status == 400) {
                synchronized (this) {
                    TimetableFriend f = friend(friendId);
                    if (f == null) return true;
                    if (myFeedId.equals(f.myFeedId)) {
                        replace(TimetableShareRules.dropOwnFeed(f, ShareTerm.of(System.currentTimeMillis())));
                        persistFriends();
                    }
                }
                return null;
            }
            if (!e.isGone() && e.status != 409 && e.status != 403) return false;
            synchronized (this) {
                TimetableFriend f = friend(friendId);
                if (f != null && offered.equals(f.offeredFeedId)) {
                    replace(TimetableShareRules.dropOffer(f, deletions));
                    persistDeletions();
                    persistFriends();
                }
            }
            return false;
        }
        synchronized (this) {
            TimetableFriend f = friend(friendId);
            // 待っている間に消された・止めた (自分のフィードは積んである)。利用者の操作なので失敗とは知らせない。
            if (f == null || !myFeedId.equals(f.myFeedId)) return true;
            replace(TimetableShareRules.acceptedWithMyFeed(f, deletions, offered));
            persistDeletions();
            persistFriends();
            return true;
        }
    }

    // ── 更新 (友達の一覧を開いたとき、10.6) ──

    /**
     * 一覧を開いたときの更新。学期で消す → 積んだフィードを消す → 自分のフィードを PUT → 承認待ちの相手の欄を見る →
     * 相手のフィードを取り込む、の順。もう走っていれば何もせず false。mine は今の自分の時間割 (無ければ PUT しない)。
     */
    public boolean refresh(@Nullable SharedTimetable mine) {
        if (!refreshing.compareAndSet(false, true)) return false;
        try {
            synchronized (feedLock) {
                dropFeedsOfLostKey();
                purgeExpiredTerms();
                flushDeletionsLocked();
                pushMyFeeds(mine);
                checkWaiting();
                pullPeerFeeds();
            }
            return true;
        } finally {
            refreshing.set(false);
        }
    }

    /** 積んだフィードを今送る (「共有を止める」の後)。 */
    public void flushFeedDeletions() {
        synchronized (feedLock) {
            dropFeedsOfLostKey();
            flushDeletionsLocked();
        }
    }

    /**
     * 自分のフィードを作った鍵が、もうこの端末に無いとき (バックアップから戻した・別の端末へ移した。AndroidKeyStore の
     * 鍵は移らない) は、そのフィードを更新も削除もできない (サーバーは 403)。共有中のまま更新が止まらないよう、
     * その友達の継続共有の項目を外して今期だけにし、その鍵の積んだフィードも外す。フィードは 365 日の掃除で消える (10.9)。
     * 鍵を読めなかった (KeyStore の不具合) ときは何もしない。
     */
    private void dropFeedsOfLostKey() {
        String current;
        try {
            current = signer.existingPublicKey();
        } catch (GeneralSecurityException | RuntimeException e) {
            return;
        }
        synchronized (this) {
            if (TimetableShareRules.dropLostKeyFeeds(friends, deletions, current, ShareTerm.of(System.currentTimeMillis()))) {
                persistDeletions();
                persistFriends();
            }
        }
    }

    private void flushDeletionsLocked() {
        List<FeedDeletion> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(deletions); }
        for (FeedDeletion d : snapshot) {
            int status = deleteFeed(d);
            if (!TimetableShareRules.dropFromQueue(status)) continue;
            synchronized (this) {
                deletions.remove(d);
                persistDeletions();
            }
        }
    }

    /** DELETE して HTTP の状態を返す (成功は 200)。署名できなければ -2 (並びに残す)。 */
    private int deleteFeed(@NonNull FeedDeletion d) {
        try {
            api.deleteFeed(d.feedId, d.version, signFeedWithKey(d.signingKey, "delete", d.feedId, d.version, ""));
            return 200;
        } catch (TimetableShareApi.Failure e) {
            return e.status;
        }
    }

    private void pushMyFeeds(@Nullable SharedTimetable mine) {
        if (mine == null) return;
        String key = contentKey(mine);
        List<TimetableFriend> targets = new ArrayList<>();
        synchronized (this) {
            for (TimetableFriend f : friends) if (f.myFeedId != null && !key.equals(f.myFeedContentKey)) targets.add(f);
        }
        if (targets.isEmpty()) return;
        SignedShare own;
        try {
            own = signShareWithExistingKey(mine);
        } catch (TimetableShareApi.Failure e) {
            return;
        }
        for (TimetableFriend t : targets) {
            String feedId = t.myFeedId;
            long version;
            synchronized (this) {
                TimetableFriend f = friend(t.id);
                if (f == null || !feedId.equals(f.myFeedId)) continue;
                // 送る前に版番号を上げて残す。送れなかった番号は飛ばしてよい (前より大きければ受け付けられる)。
                version = f.myFeedVersion + 1;
                replace(f.edit().myFeedVersion(version).build());
                persistFriends();
            }
            try {
                api.putFeed(feedId, own, version, signFeedWithKey(t.myFeedKey, "put", feedId, version, own.timetable));
                synchronized (this) {
                    TimetableFriend f = friend(t.id);
                    if (f != null && feedId.equals(f.myFeedId)) {
                        replace(f.edit().myFeedContentKey(key).build());
                        persistFriends();
                    }
                }
            } catch (TimetableShareApi.Failure e) {
                if (!e.isGone()) continue;
                // 自分のフィードが消えていた (掃除など)。継続共有をやめて今期だけにする。
                synchronized (this) {
                    TimetableFriend f = friend(t.id);
                    if (f != null && feedId.equals(f.myFeedId)) {
                        replace(TimetableShareRules.dropOwnFeed(f, ShareTerm.of(System.currentTimeMillis())));
                        persistFriends();
                    }
                }
            }
        }
    }

    private void checkWaiting() {
        List<TimetableFriend> targets = new ArrayList<>();
        synchronized (this) {
            for (TimetableFriend f : friends) if (f.state() == TimetableFriend.State.WAITING) targets.add(f);
        }
        for (TimetableFriend t : targets) linkWaiting(t.id, t.myFeedId, null);
    }

    /**
     * 10.6 の 4。自分のフィード (myFeedId) の相手の欄を読み、そこに書かれたフィードが友達の鍵で署名されていれば
     * 友達をつなぐ (共有中)。expectedPeer を渡したときは、相手の欄がその値のときだけ。
     * ほかの人の QR を送り直した誰かが自分のフィードを書いたなら無視して待ち続ける (つなぐと「共有中」なのに更新が来ない)。
     * 自分のフィードが無い (410) なら継続共有の項目を外す。相手の欄のフィードが無い (410) なら、相手が止めたので
     * 自分のフィードを積んで今期だけにする。通信の失敗・ほかの応答は待ち続ける。feedLock を持って呼ぶ。
     */
    private enum LinkResult { LINKED, PEER_GONE, NOT_LINKED }

    @NonNull
    private LinkResult linkWaiting(@NonNull String friendId, @NonNull String myFeedId, @Nullable String expectedPeer) {
        String ownFeedPeer;
        try {
            ownFeedPeer = api.getFeed(myFeedId).peerFeedId;
        } catch (TimetableShareApi.Failure e) {
            if (!e.isGone()) return LinkResult.NOT_LINKED;
            synchronized (this) {
                TimetableFriend f = friend(friendId);
                if (f != null && myFeedId.equals(f.myFeedId)) {
                    // 学期を今にする (今期だけになった)。前の学期のままだと、次の学期の掃除の前に消えてしまう。
                    replace(TimetableShareRules.dropOwnFeed(f, ShareTerm.of(System.currentTimeMillis())));
                    persistFriends();
                }
            }
            return LinkResult.NOT_LINKED;
        }
        if (ownFeedPeer == null || (expectedPeer != null && !expectedPeer.equals(ownFeedPeer))) {
            return LinkResult.NOT_LINKED;
        }
        SignedShare.Verified peer;
        try {
            peer = SignedShare.verify(api.getFeed(ownFeedPeer).share);
        } catch (TimetableShareApi.Failure e) {
            if (!e.isGone()) return LinkResult.NOT_LINKED;
            synchronized (this) {
                TimetableFriend f = friend(friendId);
                if (f != null && myFeedId.equals(f.myFeedId) && f.peerFeedId == null) {
                    replace(TimetableShareRules.waitingPeerGone(f, deletions, ownFeedPeer,
                            ShareTerm.of(System.currentTimeMillis())));
                    persistDeletions();
                    persistFriends();
                }
            }
            return LinkResult.PEER_GONE;
        }
        synchronized (this) {
            TimetableFriend f = friend(friendId);
            if (!TimetableShareRules.canLinkWaiting(f, myFeedId, ownFeedPeer, expectedPeer, peer)) {
                return LinkResult.NOT_LINKED;
            }
            replace(TimetableShareRules.linkPeer(f, deletions, ownFeedPeer));
            persistDeletions();
            persistFriends();
            return LinkResult.LINKED;
        }
    }

    private void pullPeerFeeds() {
        List<TimetableFriend> targets = new ArrayList<>();
        synchronized (this) {
            for (TimetableFriend f : friends) if (f.peerFeedId != null) targets.add(f);
        }
        for (TimetableFriend t : targets) {
            String peer = t.peerFeedId;
            try {
                TimetableShareApi.Feed feed = api.getFeed(peer);
                SignedShare.Verified v = SignedShare.verify(feed.share);
                if (v == null) continue;
                synchronized (this) {
                    TimetableFriend f = friend(t.id);
                    if (f == null || !peer.equals(f.peerFeedId)) continue;
                    TimetableFriend updated = TimetableShareRules.applyPeerFeed(f, v, feed.version, System.currentTimeMillis());
                    if (updated == null) continue;
                    replace(updated);
                    persistFriends();
                }
            } catch (TimetableShareApi.Failure e) {
                // 通信の失敗などは何もしない (最後に取り込んだ時間割を出したまま)。
                if (!e.isGone()) continue;
                synchronized (this) {
                    TimetableFriend f = friend(t.id);
                    if (f == null || !peer.equals(f.peerFeedId)) continue;
                    replace(TimetableShareRules.peerFeedGone(f, deletions, ShareTerm.of(System.currentTimeMillis())));
                    persistDeletions();
                    persistFriends();
                }
            }
        }
    }

    // ── 止める (10.7) ──

    /** 「共有を止める」。自分のフィードを積んで継続共有の項目を外す。積んだものは呼び出し側が flushFeedDeletions で送る。 */
    public synchronized void stopContinuousSharing(@NonNull String friendId) {
        TimetableFriend f = friend(friendId);
        if (f == null) return;
        replace(TimetableShareRules.stopSharing(f, deletions, ShareTerm.of(System.currentTimeMillis())));
        persistDeletions();
        persistFriends();
    }

    // ── すべてのデータを削除 ──

    /**
     * ログアウト (この端末のデータを消す) のとき。受け取った友達・自分の名前・出している共有を手元から消し、
     * 共有はサーバーからも消しにいく (消せなくても 24 時間で切れる)。トークンは PreferenceManager.clearAll() で
     * 消えるので、先に写してから消す。通信はメインスレッドでしない。
     */
    public void clearAllOnLogout() {
        clearAllOnLogout(null);
    }

    /**
     * onFailure: 出している共有か、自分のフィードをサーバーから消せなかったときに呼ぶ (ログアウトで知らせる)。
     * 積んであるフィードと、全部の友達の自分のフィードを DELETE してから鍵を消す (10.7)。鍵が無いと署名できないので、
     * 送れなかったものは並びごと捨てる (365 日の掃除で消える)。
     */
    public void clearAllOnLogout(@androidx.annotation.Nullable Runnable onFailure) {
        OutgoingShare current = outgoing();
        String token = ownerToken();
        List<FeedDeletion> feeds;
        synchronized (this) {
            feeds = new ArrayList<>(deletions);
            for (TimetableFriend f : friends) TimetableShareRules.queueOwnFeed(feeds, f);
            friends.clear();
            deletions.clear();
            prefs.edit().clear().apply();
            secure.setTimetableShareOwnerToken(null);
        }
        new Thread(() -> {
            boolean failed = false;
            // 署名を先に全部作ってから鍵を消し、そのあとで送る。送るのを待ってから鍵を消すと、その間に
            // 次のユーザーが作った鍵を消してしまうことがある (通信は 1 件 20 秒まで待つ)。
            // feedLock は取らない: 一覧の更新が通信で待っている間に鍵の削除が遅れ、その間に次のユーザーが作った鍵を
            // 消してしまわないように。更新の途中で鍵が消えても、その署名が 403 で外れるだけ。
            List<FeedDeletion> signedFeeds = new ArrayList<>();
            List<String> signatures = new ArrayList<>();
            String keyNow;
            try {
                keyNow = signer.existingPublicKey();
            } catch (GeneralSecurityException | RuntimeException e) {
                // 鍵を読めない (KeyStore の不具合) ので、どれも署名できない。消せなかったことは知らせる。
                keyNow = null;
                if (!feeds.isEmpty()) failed = true;
            }
            for (FeedDeletion d : feeds) {
                // 今の鍵で作っていないフィード (バックアップから戻した・鍵が無い) は署名できない。消せないことが
                // 分かっているので送らず、失敗にも数えない (365 日の掃除で消える)。
                if (keyNow == null || !keyNow.equals(d.signingKey)) continue;
                try {
                    signatures.add(signFeedWithKey(d.signingKey, "delete", d.feedId, d.version, ""));
                    signedFeeds.add(d);
                } catch (TimetableShareApi.Failure e) {
                    failed = true;
                }
            }
            try {
                signer.deleteKey();
            } catch (GeneralSecurityException | RuntimeException e) {
                // 鍵が残ると、次に共有したとき相手から同じ人に見える。サーバーのデータではないので知らせはしない。
                Log.w(TAG, "deleting the signing key failed: " + e.getClass().getSimpleName());
            }
            if (current != null && token != null) {
                try {
                    api.delete(current.shareId, token);
                } catch (TimetableShareApi.Failure e) {
                    // 消せなかった共有は期限 (24 時間) で消える。消せなかったことは知らせる。
                    failed = true;
                }
            }
            for (int i = 0; i < signedFeeds.size(); i++) {
                FeedDeletion d = signedFeeds.get(i);
                try {
                    api.deleteFeed(d.feedId, d.version, signatures.get(i));
                } catch (TimetableShareApi.Failure e) {
                    // 消すのを待つ並びと同じ決まりで数える。並びが残すもの (通信の失敗・429・5xx) だけが失敗。
                    // 400・403・409 などは送り直しても通らないので、失敗とは数えない (365 日の掃除で消える)。
                    if (TimetableShareRules.deletionFailed(e.status)) failed = true;
                }
            }
            if (failed && onFailure != null) onFailure.run();
        }, "timetable-share-stop").start();
    }
}
