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

    /** 届いた時間割を保存する。同じ名前 (相手が付けた名前) の友達が既にいれば、その友達を上書きする。 */
    @NonNull
    public synchronized TimetableFriend save(@NonNull SharedTimetable timetable, long receivedAt) {
        TimetableFriend f = upsert(friends, timetable, receivedAt, UUID.randomUUID().toString());
        persistFriends();
        return f;
    }

    /** save の中身 (保存先に触らない)。名前の無いものは毎回別の友達として先頭に足す。 */
    @NonNull
    static TimetableFriend upsert(@NonNull List<TimetableFriend> friends, @NonNull SharedTimetable timetable,
                                  long receivedAt, @NonNull String newId) {
        if (!timetable.name.isEmpty()) {
            for (int i = 0; i < friends.size(); i++) {
                TimetableFriend old = friends.get(i);
                if (old.sentName.equals(timetable.name)) {
                    TimetableFriend updated = new TimetableFriend(old.id, old.name, old.sentName, receivedAt, timetable);
                    friends.set(i, updated);
                    return updated;
                }
            }
        }
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
            return new OutgoingShare(o.get("shareId").getAsString(), o.get("expiresAt").getAsLong(), seen);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void setOutgoing(@NonNull OutgoingShare share) {
        JsonObject o = new JsonObject();
        o.addProperty("shareId", share.shareId);
        o.addProperty("expiresAt", share.expiresAt);
        JsonArray seen = new JsonArray();
        for (String id : share.seenReplyIds) seen.add(id);
        o.add("seenReplyIds", seen);
        prefs.edit().putString(KEY_OUTGOING, o.toString()).apply();
    }

    private void clearOutgoing() {
        prefs.edit().remove(KEY_OUTGOING).apply();
        secure.setTimetableShareOwnerToken(null);
    }

    @Nullable
    private String ownerToken() {
        String t = secure.getTimetableShareOwnerToken();
        return t == null || t.isEmpty() ? null : t;
    }

    /** 有効な共有があればそれを、無ければ新しく作る。名前を変えたときは作り直す (forceNew)。 */
    @NonNull
    public OutgoingShare startSharing(@NonNull SharedTimetable timetable, boolean forceNew) throws TimetableShareApi.Failure {
        OutgoingShare current = outgoing();
        if (!forceNew && current != null && !current.isExpired(System.currentTimeMillis() + 10 * 60_000L)
                && ownerToken() != null) {
            return current;
        }
        stopSharing();
        TimetableShareApi.Created created = api.create(timetable);
        secure.setTimetableShareOwnerToken(created.ownerToken);
        OutgoingShare share = new OutgoingShare(created.shareId, created.expiresAt, new ArrayList<>());
        synchronized (this) { setOutgoing(share); }
        return share;
    }

    /** 届いた返事を友達として保存し、新しく届いた友達を返す。期限切れなら共有を片付ける。 */
    @NonNull
    public List<TimetableFriend> collectReplies() throws TimetableShareApi.Failure {
        OutgoingShare current = outgoing();
        List<TimetableFriend> received = new ArrayList<>();
        if (current == null) return received;
        String token = ownerToken();
        if (current.isExpired(System.currentTimeMillis()) || token == null) {
            synchronized (this) { clearOutgoing(); }
            return received;
        }
        List<TimetableShareApi.Reply> replies;
        try {
            replies = api.replies(current.shareId, token);
        } catch (TimetableShareApi.Failure e) {
            if (e.isExpired()) {
                synchronized (this) { clearOutgoing(); }
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
            if (!received.isEmpty()) setOutgoing(new OutgoingShare(share.shareId, share.expiresAt, seen));
        }
        return received;
    }

    /** 共有を止める。サーバーからも消す (消せなくても期限で消える)。 */
    public void stopSharing() {
        OutgoingShare current = outgoing();
        if (current == null) return;
        String token = ownerToken();
        if (token != null) {
            try {
                api.delete(current.shareId, token);
            } catch (TimetableShareApi.Failure ignored) {
                // 消せなかった共有は 24 時間で切れる。手元は片付ける。
            }
        }
        synchronized (this) {
            OutgoingShare now = outgoing();
            if (now != null && now.shareId.equals(current.shareId)) clearOutgoing();
        }
    }
}
