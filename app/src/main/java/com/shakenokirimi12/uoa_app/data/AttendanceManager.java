package com.shakenokirimi12.uoa_app.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 出席の記録。AINS のアカウント (ユーザー名) ごとに分けて持つ ("data@<accountId>")。アカウントを切り替えても
 * 前のアカウントの記録は消さず、そのアカウントに戻れば見える。
 *
 * accountId はユーザー名の SHA-256 の先頭 16 バイト (iOS と同じ)。この prefs は OS のバックアップに入るので、
 * 学籍番号をそのままキーに書かない。versionCode 7 はユーザー名をそのまま書いていた ("data@<ユーザー名>") ので、
 * 起動後の最初の読み書きで accountId のキーへ移す。
 *
 * 分ける前の記録 ("data") は、ログインしているアカウントのものとして移す (初めて起動したとき、または次に
 * ログインしたとき)。誰もログインしていない間は "data" のまま読み書きし、次にログインしたアカウントに移す。
 * 移す先に記録が既にあれば足し合わせ、どちらの記録も消さない。
 */
public class AttendanceManager {
    private static final String PREF_NAME = "attendance_data_v2";
    /** アカウントに分ける前の記録と、誰もログインしていない間の記録。 */
    static final String KEY_DATA = "data";

    /** 保存先。テストでは Map で置き換える。 */
    interface Store {
        @Nullable String get(@NonNull String key);
        /** key に value を書き、removeKey があれば同じ書き込みで消す (移すときに片方だけ残らないように)。 */
        void put(@NonNull String key, @NonNull String value, @Nullable String removeKey);
        /** 書いてある鍵の一覧 (バックアップの書き出し用)。 */
        @NonNull Set<String> keys();
    }

    private static AttendanceManager instance;
    private final Store store;
    private final Supplier<String> currentUser;
    private final Gson gson = new Gson();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    /**
     * ABSENT_UNCONFIRMED は自動で付けた「欠席？（要確認）」。位置情報では来なかったように見えたが、それだけでは
     * 欠席と言い切れない (通知の取りこぼし・ジオフェンスの外の建物)。利用者が選ぶまで、どの集計にも入れない。
     */
    public enum Status { PRESENT, ABSENT, LATE, ABSENT_UNCONFIRMED }

    public static class HistoryItem {
        public String id;
        public String date;
        public String status;
        public long timestamp;
        public String source;
    }

    public static class Record {
        public int present;
        public int absent;
        public int late;
        public List<HistoryItem> history = new ArrayList<>();
    }

    public static class AttendanceSummary {
        public final int present, absent, late;
        public final List<HistoryItem> history;
        AttendanceSummary(int p, int a, int l, List<HistoryItem> h) {
            present = p; absent = a; late = l; history = h;
        }
    }

    private AttendanceManager(Context ctx) {
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        PreferenceManager accounts = PreferenceManager.getInstance(ctx);
        this.store = new Store() {
            @Override public String get(@NonNull String key) { return prefs.getString(key, null); }
            @Override public void put(@NonNull String key, @NonNull String value, @Nullable String removeKey) {
                SharedPreferences.Editor e = prefs.edit().putString(key, value);
                if (removeKey != null) e.remove(removeKey);
                e.apply();
            }
            @NonNull @Override public Set<String> keys() { return new HashSet<>(prefs.getAll().keySet()); }
        };
        this.currentUser = accounts::getUsername;
    }

    AttendanceManager(@NonNull Store store, @NonNull Supplier<String> currentUser) {
        this.store = store;
        this.currentUser = currentUser;
    }

    /** username の記録の保存先。誰もログインしていなければ分ける前の "data"。 */
    static String keyFor(@Nullable String username) {
        String u = username != null ? username.trim() : "";
        return u.isEmpty() ? KEY_DATA : KEY_DATA + "@" + accountId(u);
    }

    /** ユーザー名 (前後の空白を除く) の SHA-256 の先頭 16 バイトを 16 進で。prefs のキーに使う (iOS と同じ)。 */
    public static String accountId(@NonNull String username) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(username.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format(Locale.US, "%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** accountId の形 (16 進 32 文字) か。versionCode 7 のユーザー名そのままのキーと分ける。 */
    public static boolean isAccountId(@NonNull String s) {
        return s.matches("[0-9a-f]{32}");
    }

    /** versionCode 7 のキー ("data@<ユーザー名>") を移し終えたか (このプロセスで)。 */
    private boolean rawKeysMigrated;

    /**
     * versionCode 7 が書いたユーザー名そのままのキーを、accountId のキーへ移す。移す先にもあれば足し合わせる。
     * 1 アカウントずつ、足した記録の書き込みと元のキーの削除を 1 回の書き込み (同じ Editor) で行うので、途中で
     * 止まっても片方だけが残ることはない。読めないものは触らない。
     */
    private void migrateRawUsernameKeys() {
        if (rawKeysMigrated) return;
        boolean done = true;
        for (String key : store.keys()) {
            if (!key.startsWith(KEY_DATA + "@")) continue;
            String suffix = key.substring(KEY_DATA.length() + 1);
            if (isAccountId(suffix)) continue;
            String target = keyFor(suffix);
            Map<String, Record> from = parse(store.get(key));
            Map<String, Record> into = parse(store.get(target));
            if (from == null || into == null) { done = false; continue; }
            mergeInto(into, from);
            store.put(target, gson.toJson(into), key);
        }
        rawKeysMigrated = done;
    }

    // 画面 (手動の記録) と自動出席・自動の欠席 (別スレッド) が同じ保存先を読み書きする。読んで書き戻すまでを
    // ひとまとまりにしないと、片方の書き込みが消える。public の読み書きは synchronized にする。

    public static synchronized AttendanceManager getInstance(Context ctx) {
        if (instance == null) instance = new AttendanceManager(ctx);
        return instance;
    }

    /** 分ける前の記録が残っていて、誰かがログインしていれば、そのアカウントに移す。起動時にも呼ぶ。 */
    public synchronized void migrateLegacyRecords() {
        migrateRawUsernameKeys();
        String key = keyFor(currentUser.get());
        if (KEY_DATA.equals(key)) return;
        String legacyJson = store.get(KEY_DATA);
        if (legacyJson == null) return;
        Map<String, Record> legacy = parse(legacyJson);
        // 読めない "data" は消さずに残す (読めないまま移すと記録を失う)。
        if (legacy == null) return;
        Map<String, Record> target = parse(store.get(key));
        if (target == null) return;
        mergeInto(target, legacy);
        store.put(key, gson.toJson(target), KEY_DATA);
    }

    /**
     * from の記録を into に足す。同じ id の記録は重ねず、日付ごとと同じ選び方で 1 つを残す (片方だけ確かめた
     * 記録なら、確かめた方)。
     */
    static void mergeInto(Map<String, Record> into, Map<String, Record> from) {
        for (Map.Entry<String, Record> e : from.entrySet()) {
            Record src = e.getValue();
            if (src == null || src.history == null) continue;
            Record dst = into.get(e.getKey());
            if (dst == null) { dst = new Record(); into.put(e.getKey(), dst); }
            if (dst.history == null) dst.history = new ArrayList<>();
            Map<String, Integer> indexById = new HashMap<>();
            for (int i = 0; i < dst.history.size(); i++) {
                HistoryItem h = dst.history.get(i);
                if (h != null && h.id != null) indexById.putIfAbsent(h.id, i);
            }
            for (HistoryItem h : src.history) {
                if (h == null) continue;
                Integer at = h.id != null ? indexById.get(h.id) : null;
                if (at == null) {
                    if (h.id != null) indexById.put(h.id, dst.history.size());
                    dst.history.add(h);
                } else if (prefer(h, dst.history.get(at))) {
                    dst.history.set(at, h);
                }
            }
            keepOnePerDate(dst.history);
        }
    }

    /**
     * 記録は 1 日 1 つ (addHistory が同じ日を置き換える)。合わせた結果、同じ日に 2 つあれば 1 つにする (両方残すと
     * 同じ授業を 2 回数える)。確かめた記録 (出席・欠席・遅刻) を「欠席？（要確認）」より優先し、同じ種類なら後に
     * 付けた方を残す。日付の無いものはそのまま残す。
     */
    private static void keepOnePerDate(List<HistoryItem> history) {
        Map<String, HistoryItem> latest = new HashMap<>();
        for (HistoryItem h : history) {
            if (h.date == null) continue;
            HistoryItem kept = latest.get(h.date);
            if (kept == null || prefer(h, kept)) latest.put(h.date, h);
        }
        history.removeIf(h -> h.date != null && latest.get(h.date) != h);
    }

    private static boolean prefer(HistoryItem candidate, HistoryItem kept) {
        boolean candidateUnconfirmed = Status.ABSENT_UNCONFIRMED.name().equals(candidate.status);
        boolean keptUnconfirmed = Status.ABSENT_UNCONFIRMED.name().equals(kept.status);
        if (candidateUnconfirmed != keptUnconfirmed) return keptUnconfirmed;
        return candidate.timestamp > kept.timestamp;
    }

    /**
     * バックアップに書き出す、アカウントごとの記録 (accountId → JSON)。分ける前の記録と誰もログインしていない間の
     * 記録は "" に入る。ユーザー名は書き出さない。
     */
    public synchronized Map<String, String> exportByAccount() {
        migrateLegacyRecords();
        Map<String, String> out = new HashMap<>();
        for (String key : store.keys()) {
            String json = store.get(key);
            if (json == null) continue;
            if (KEY_DATA.equals(key)) out.put("", json);
            else if (key.startsWith(KEY_DATA + "@")) {
                // 読めずに移せなかった versionCode 7 のキー (ユーザー名そのもの) も、書き出すのは accountId。
                String suffix = key.substring(KEY_DATA.length() + 1);
                out.put(isAccountId(suffix) ? suffix : accountId(suffix), json);
            }
        }
        return out;
    }

    /** 今のアカウントの記録の JSON (前の版のバックアップの形)。無ければ null。 */
    @Nullable
    public synchronized String exportCurrent() {
        migrateLegacyRecords();
        return store.get(keyOf(null));
    }

    /**
     * バックアップの記録を username (accountId でもよい) の記録に足す ("" は分ける前の記録で、ログインしている
     * アカウントに移る)。
     * 上書きはしない (読み戻しで今の記録を失わない)。読めなければ何もせず false。
     */
    public synchronized boolean importRecords(@NonNull String username, @Nullable String json) {
        Map<String, Record> from = json != null ? parse(json) : null;
        if (from == null) return false;
        // この版の書き出しは accountId、versionCode 7 の書き出しはユーザー名そのもの。
        String key = isAccountId(username) ? KEY_DATA + "@" + username : keyFor(username);
        Map<String, Record> into = parse(store.get(key));
        if (into == null) return false;
        mergeInto(into, from);
        store.put(key, gson.toJson(into), null);
        migrateLegacyRecords();
        return true;
    }

    /** null は読めなかったとき (無ければ空)。 */
    @Nullable
    private Map<String, Record> parse(@Nullable String json) {
        if (json == null) return new HashMap<>();
        Type type = new TypeToken<Map<String, Record>>() {}.getType();
        try {
            Map<String, Record> map = gson.fromJson(json, type);
            if (map == null) return new HashMap<>();
            // "history" の無い記録 (手で書き換えた・壊れた) でも読めるようにする。
            map.values().removeIf(r -> r == null);
            for (Record r : map.values()) {
                if (r.history == null) r.history = new ArrayList<>();
                r.history.removeIf(h -> h == null);
            }
            return map;
        } catch (RuntimeException e) { return null; }
    }

    /**
     * 読み書きする保存先。username が null なら今のアカウント。1 回の読み書きの中で 1 度だけ決め、読んだのと
     * 同じ保存先に書く (途中でアカウントが切り替わっても、前のアカウントの記録を次のアカウントに書かない)。
     */
    private String keyOf(@Nullable String username) {
        return keyFor(username != null ? username : currentUser.get());
    }

    /** 読めなければ空 (表示用)。書き戻す処理は loadForWrite を使う。 */
    private Map<String, Record> loadAll(String key) {
        Map<String, Record> map = loadForWrite(key);
        return map != null ? map : new HashMap<>();
    }

    /**
     * 書き戻すための読み込み。読めなければ null で、呼び出し側は書かずに止める (空で書き戻すと、読めなかった
     * 記録をすべて消す)。
     */
    @Nullable
    private Map<String, Record> loadForWrite(String key) {
        migrateLegacyRecords();
        Map<String, Record> map = parse(store.get(key));
        if (map == null) {
            android.util.Log.w("AttendanceManager", "Attendance records unreadable; not writing");
            return null;
        }
        return map;
    }

    private void saveAll(String key, Map<String, Record> data) {
        store.put(key, gson.toJson(data), null);
    }

    /** 今日の記録を消す (デバッグのテスト科目の後始末用)。 */
    public synchronized void removeToday(String courseId) {
        String key = keyOf(null);
        Map<String, Record> all = loadForWrite(key);
        if (all == null) return;
        Record r = all.get(courseId);
        if (r == null) return;
        String today = dateFmt.format(new Date());
        r.history.removeIf(h -> today.equals(h.date));
        if (r.history.isEmpty()) all.remove(courseId);
        saveAll(key, all);
    }

    public synchronized AttendanceSummary getAttendance(String courseId) {
        String key = keyOf(null);
        Map<String, Record> all = loadAll(key);
        Record r = all.get(courseId);
        if (r == null) return new AttendanceSummary(0, 0, 0, new ArrayList<>());

        int p = 0, a = 0, l = 0;
        for (HistoryItem h : r.history) {
            if (h.status == null) continue;
            switch (h.status) {
                case "PRESENT": p++; break;
                case "ABSENT": a++; break;
                case "LATE": l++; break;
            }
        }
        List<HistoryItem> sorted = new ArrayList<>(r.history);
        Collections.sort(sorted, (x, y) -> Long.compare(y.timestamp, x.timestamp));
        return new AttendanceSummary(p, a, l, sorted);
    }

    public void addHistory(String courseId, Status status, Date date, String source) {
        addHistory(null, courseId, status, date, source);
    }

    /**
     * username の記録に足す (null なら今のアカウント)。保存したら true。記録が読めず書かなかったら false
     * (呼び出し側は「登録した」と知らせない)。
     */
    public synchronized boolean addHistory(@Nullable String username, String courseId, Status status, Date date,
                                           String source) {
        String key = keyOf(username);
        Map<String, Record> all = loadForWrite(key);
        if (all == null) return false;
        Record r = all.get(courseId);
        if (r == null) { r = new Record(); all.put(courseId, r); }

        String dateStr = dateFmt.format(date);
        Iterator<HistoryItem> it = r.history.iterator();
        while (it.hasNext()) {
            if (dateStr.equals(it.next().date)) it.remove();
        }

        HistoryItem item = new HistoryItem();
        item.id = UUID.randomUUID().toString();
        item.date = dateStr;
        item.status = status.name();
        item.timestamp = System.currentTimeMillis();
        item.source = source;
        r.history.add(item);
        saveAll(key, all);
        return true;
    }

    /**
     * その日に記録が無いときだけ足す (確かめてから足すまでを 1 つにする。別々に呼ぶと、そのあいだに付いた
     * 手動の記録を上書きする)。足したら true。記録があった、または記録が読めなかったら false。
     */
    public synchronized boolean addHistoryIfUnrecorded(@NonNull String username, String courseId, Status status,
                                                       Date date, String source) {
        String dateStr = dateFmt.format(date);
        Record r = loadAll(keyOf(username)).get(courseId);
        if (r != null) for (HistoryItem h : r.history) if (dateStr.equals(h.date)) return false;
        return addHistory(username, courseId, status, date, source);
    }

    /** 記録の状態を手で変える (自動の欠席を出席に直す、など)。手で変えたものは手動の記録になる。 */
    public void updateStatus(String courseId, String historyId, Status status) {
        updateStatus(null, courseId, historyId, status);
    }

    /**
     * username の記録を変える (null なら今のアカウント)。通知のボタンは、尋ねたときのアカウントの記録を変える
     * (切り替えたあとに押されても、今のアカウントの記録を探さない)。
     */
    public synchronized void updateStatus(@Nullable String username, String courseId, String historyId, Status status) {
        String key = keyOf(username);
        Map<String, Record> all = loadForWrite(key);
        if (all == null) return;
        Record r = all.get(courseId);
        if (r == null) return;
        for (HistoryItem h : r.history) {
            if (historyId.equals(h.id)) {
                h.status = status.name();
                h.source = "manual";
            }
        }
        saveAll(key, all);
    }

    /** 科目ごとの、記録 (手動・自動) がある日付。 */
    public Map<String, Set<String>> recordedDates() { return recordedDates(null); }

    /** username の記録がある日付 (null なら今のアカウント)。 */
    public synchronized Map<String, Set<String>> recordedDates(@Nullable String username) {
        String key = keyOf(username);
        Map<String, Set<String>> out = new HashMap<>();
        for (Map.Entry<String, Record> e : loadAll(key).entrySet()) {
            Set<String> dates = new HashSet<>();
            for (HistoryItem h : e.getValue().history) dates.add(h.date);
            out.put(e.getKey(), dates);
        }
        return out;
    }

    /**
     * 「欠席？（要確認）」を付ける。その日に記録が既にあれば何もしない (手動の記録を上書きしない)。
     * @param classEnd 授業の終わり。履歴はこの順に並ぶので、付けた時刻ではなく授業の時刻にする。
     * @return 付けた記録の id。付けなかったら null。
     */
    @Nullable
    public synchronized String addUnconfirmedAbsenceIfUnrecorded(@NonNull String username, String courseId,
                                                                 String date, long classEnd) {
        String key = keyOf(username);
        Map<String, Record> all = loadForWrite(key);
        if (all == null) return null;
        Record r = all.get(courseId);
        if (r == null) { r = new Record(); all.put(courseId, r); }
        for (HistoryItem h : r.history) if (date.equals(h.date)) return null;
        HistoryItem item = new HistoryItem();
        item.id = UUID.randomUUID().toString();
        item.date = date;
        item.status = Status.ABSENT_UNCONFIRMED.name();
        item.timestamp = classEnd;
        item.source = "auto";
        r.history.add(item);
        saveAll(key, all);
        return item.id;
    }

    public synchronized void removeHistory(String courseId, String historyId) {
        String key = keyOf(null);
        Map<String, Record> all = loadForWrite(key);
        if (all == null) return;
        Record r = all.get(courseId);
        if (r == null) return;
        Iterator<HistoryItem> it = r.history.iterator();
        while (it.hasNext()) {
            if (historyId.equals(it.next().id)) it.remove();
        }
        saveAll(key, all);
    }
}
