package com.shakenokirimi12.uoa_app.data.timetable;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.CalendarEvent;
import com.shakenokirimi12.uoa_app.services.TimetableShareApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 友達の時間割と、自分が出している共有を持つ。画面はすべてこれを見る。iOS の TimetableShareStore と同じ。
 * 通信するメソッド (startSharing / collectReplies / stopSharing) はメインスレッドから呼ばない。
 */
public final class TimetableShareStore {
    static final String PREFS = "timetable_share";
    private static final String KEY_FRIENDS = "friends_v1";
    private static final String KEY_OUTGOING = "outgoing_v1";
    private static final String KEY_MY_NAME = "my_name";
    /** 名前の入力欄の上限。送るときは SharedTimetable.MAX_NAME で切る。 */
    public static final int MY_NAME_MAX = 20;
    public static final String UNNAMED = "名前なし";

    private static TimetableShareStore instance;

    private final SharedPreferences prefs;
    private final PreferenceManager secure;
    private final TimetableShareApi api = new TimetableShareApi();
    private final List<TimetableFriend> friends = new ArrayList<>();

    private TimetableShareStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secure = PreferenceManager.getInstance(context.getApplicationContext());
        friends.addAll(readFriends(prefs.getString(KEY_FRIENDS, null)));
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
        for (TimetableFriend f : friends) if (f.id.equals(id)) return f;
        return null;
    }

    /**
     * 届いた時間割を新しい友達として保存する。同じ名前の友達がいても上書きしない: 返事には認証が無く、QR を見た
     * 第三者や同じ名前の別の友達の返事で、今の友達の時間割が黙って置き換わってしまうため。
     */
    @NonNull
    public synchronized TimetableFriend save(@NonNull SharedTimetable timetable, long receivedAt) {
        TimetableFriend f = add(friends, timetable, receivedAt, UUID.randomUUID().toString());
        persistFriends();
        return f;
    }

    /** save の中身 (保存先に触らない)。いつも先頭に足す。 */
    @NonNull
    static TimetableFriend add(@NonNull List<TimetableFriend> friends, @NonNull SharedTimetable timetable,
                               long receivedAt, @NonNull String newId) {
        TimetableFriend f = new TimetableFriend(newId, timetable.name.isEmpty() ? UNNAMED : timetable.name,
                timetable.name, receivedAt, timetable);
        friends.add(0, f);
        return f;
    }

    public synchronized void rename(@NonNull String id, @NonNull String name) {
        String cleaned = SharedTimetable.sanitize(name, SharedTimetable.MAX_NAME);
        if (cleaned.isEmpty()) return;
        for (int i = 0; i < friends.size(); i++) {
            TimetableFriend f = friends.get(i);
            if (f.id.equals(id)) {
                friends.set(i, new TimetableFriend(f.id, cleaned, f.sentName, f.receivedAt, f.timetable));
                persistFriends();
                return;
            }
        }
    }

    public synchronized void remove(@NonNull String id) {
        friends.removeIf(f -> f.id.equals(id));
        persistFriends();
    }

    private void persistFriends() {
        JsonArray array = new JsonArray();
        for (TimetableFriend f : friends) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.id);
            o.addProperty("name", f.name);
            o.addProperty("sentName", f.sentName);
            o.addProperty("receivedAt", f.receivedAt);
            o.add("timetable", f.timetable.toJson());
            array.add(o);
        }
        prefs.edit().putString(KEY_FRIENDS, array.toString()).apply();
    }

    @NonNull
    static List<TimetableFriend> readFriends(@Nullable String json) {
        List<TimetableFriend> result = new ArrayList<>();
        if (json == null) return result;
        try {
            for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                SharedTimetable t = SharedTimetable.normalize(o.get("timetable"));
                if (t == null) continue;
                result.add(new TimetableFriend(o.get("id").getAsString(), o.get("name").getAsString(),
                        o.get("sentName").getAsString(), o.get("receivedAt").getAsLong(), t));
            }
        } catch (RuntimeException ignored) {
            // 壊れた保存は読まない (受け取り直せば戻る)。
        }
        return result;
    }

    // ── 自分 ──

    @NonNull
    public String myName() { return prefs.getString(KEY_MY_NAME, ""); }

    public void setMyName(@NonNull String name) {
        prefs.edit().putString(KEY_MY_NAME, SharedTimetable.sanitize(name, MY_NAME_MAX)).apply();
    }

    /** 自分の時間割。授業が 1 つも無ければ null (送れない)。 */
    @Nullable
    public SharedTimetable myTimetable(@NonNull List<CalendarEvent> events, long nowMs) {
        List<SharedClass> classes = TimetableDerivation.weeklyClasses(events, nowMs);
        return classes.isEmpty() ? null : SharedTimetable.create(myName(), nowMs, classes);
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
            return new OutgoingShare(o.get("shareId").getAsString(), o.get("expiresAt").getAsLong(), key, seen);
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

    /** 有効な共有があり、時間割 (名前と授業) が同じならそれを、無ければ新しく作る。 */
    @NonNull
    public OutgoingShare startSharing(@NonNull SharedTimetable timetable, boolean forceNew) throws TimetableShareApi.Failure {
        String key = contentKey(timetable);
        OutgoingShare current = outgoing();
        if (!forceNew && current != null && current.contentKey.equals(key)
                && !current.isExpired(System.currentTimeMillis() + 10 * 60_000L) && ownerToken() != null) {
            return current;
        }
        // 作り直す前に、古い共有に届いている返事を取り込む (止めると返事も消える)。
        // 古い共有を止められなくても (通信の失敗)、新しい共有は作る。古い方は期限で消える。
        try {
            collectReplies();
        } catch (TimetableShareApi.Failure ignored) {
            // 取り込めなかった返事は、古い共有と一緒に期限で消える。
        }
        if (!stopSharing()) forgetOutgoing();
        TimetableShareApi.Created created = api.create(timetable);
        if (!secure.setTimetableShareOwnerToken(created.ownerToken)) {
            // トークンを置けないと返事を読めず、後で「期限切れ」と取り違える。サーバーの共有は消して、始めなかったことにする。
            try {
                api.delete(created.shareId, created.ownerToken);
            } catch (TimetableShareApi.Failure ignored) {
                // 消せなかった共有は 24 時間で切れる。
            }
            throw TimetableShareApi.Failure.cannotStoreOnDevice();
        }
        OutgoingShare share = new OutgoingShare(created.shareId, created.expiresAt, key, new ArrayList<>());
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

    /** 届いた返事を友達として保存し、新しく届いた友達を返す。期限切れなら共有を片付ける。 */
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
        synchronized (this) {
            // 待っている間に止めた・作り直したなら、古い共有の返事は取り込まない。
            OutgoingShare share = outgoing();
            if (share == null || !share.shareId.equals(current.shareId)) return received;
            List<String> seen = new ArrayList<>(share.seenReplyIds);
            for (TimetableShareApi.Reply r : replies) {
                if (seen.contains(r.replyId)) continue;
                received.add(save(r.timetable, System.currentTimeMillis()));
                seen.add(r.replyId);
            }
            if (!received.isEmpty()) setOutgoing(new OutgoingShare(share.shareId, share.expiresAt, share.contentKey, seen));
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

    /**
     * ログアウト (この端末のデータを消す) のとき。受け取った友達・自分の名前・出している共有を手元から消し、
     * 共有はサーバーからも消しにいく (消せなくても 24 時間で切れる)。トークンは PreferenceManager.clearAll() で
     * 消えるので、先に写してから消す。通信はメインスレッドでしない。
     */
    public void clearAllOnLogout() {
        OutgoingShare current = outgoing();
        String token = ownerToken();
        synchronized (this) {
            friends.clear();
            prefs.edit().clear().apply();
            secure.setTimetableShareOwnerToken(null);
        }
        if (current == null || token == null) return;
        new Thread(() -> {
            try {
                api.delete(current.shareId, token);
            } catch (TimetableShareApi.Failure ignored) {
                // 消せなかった共有は期限で消える。
            }
        }, "timetable-share-stop").start();
    }
}
