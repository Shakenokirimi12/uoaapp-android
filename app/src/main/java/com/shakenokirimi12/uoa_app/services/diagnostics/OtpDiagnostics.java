package com.shakenokirimi12.uoa_app.services.diagnostics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shakenokirimi12.uoa_app.BuildConfig;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OTP メールの取得と PC への中継の 1 回ごとの記録を、開発者のエラー収集 (Wana、Sentry 互換) に送る (利用者の判断、2026-10-25)。
 * 本番で失敗したときに、どの手順でどれだけ時間を使ったかを全部見るため (中継サーバの detail は 80 文字しか無い)。
 * iOS の OTPDiagnostics と同じ形。
 * <ul>
 *     <li>送るもの: 結果・理由・detail・段階ごとの時間・手順の記録 (コマンドの名前・応答の状態・件数・バイト数・時間)・
 *     どこで動いたか・方式・版・問い合わせ ID。</li>
 *     <li>送らないもの: ID / パスワード・コード・メールの中身・差出人・件名・AINS の ID。手順の記録にもこれらは書かない。</li>
 *     <li>Wana の store API ({@code /api/<id>/store/}、Sentry の旧式の口。~/Development/Wana/apps/ingest/src/index.ts) に
 *     JSON を 1 回 POST するだけ。Sentry の SDK は入れない。</li>
 *     <li>送るのはコードを返したあと。上限は短く、失敗しても何もしない (best effort)。</li>
 * </ul>
 */
public final class OtpDiagnostics {
    private OtpDiagnostics() {}

    /** iOS の uoa_appApp.swift の SentrySDK と同じ DSN (プロジェクト UoA-App)。鍵は公開の鍵 (送るだけに使う)。 */
    static final String STORE_URL = "https://ingest.wana.shakenokiri.me/api/236139337/store/";
    static final String SENTRY_KEY = "wanadb4ca87b115539ceaa0db192f2a2d6b1daf1f3ec22cb2b21";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** どこで動いたか (tag の context)。 */
    public static final String CONTEXT_RELAY = "android-relay";
    public static final String CONTEXT_LOGIN = "android-login";

    private static volatile OkHttpClient client;

    private static OkHttpClient client(long timeoutMs) {
        OkHttpClient base = client;
        if (base == null) {
            base = new OkHttpClient();
            client = base;
        }
        return base.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build();
    }

    /** 1 行の手順。 */
    public static final class Step {
        public final long at;
        public final long elapsedMs;
        @NonNull public final String message;

        Step(long at, long elapsedMs, @NonNull String message) {
            this.at = at;
            this.elapsedMs = elapsedMs;
            this.message = message;
        }
    }

    /**
     * 手順の記録 (Logcat の Log.i と Wana の breadcrumbs)。どのスレッドからも書ける。
     * コマンドの名前・応答の状態・件数・バイト数・時間だけを書く。ID / パスワード・コード・メールの中身・差出人・件名は書かない。
     */
    public static final class Trail {
        /** 1 回で残す手順の上限 (新着の確認を 1 秒おきに 18 秒続けても 100 行ほど)。 */
        static final int MAX_STEPS = 300;
        @NonNull private final String tag;
        private final long createdAt = System.currentTimeMillis();
        private final List<Step> steps = new ArrayList<>();
        private final Map<String, Long> marks = new TreeMap<>();
        private final Map<String, String> values = new TreeMap<>();

        public Trail(@NonNull String tag) {
            this.tag = tag;
        }

        public void note(@NonNull String message) {
            long now = System.currentTimeMillis();
            long elapsed = now - createdAt;
            synchronized (this) {
                if (steps.size() < MAX_STEPS) steps.add(new Step(now, elapsed, message));
                else if (steps.size() == MAX_STEPS) steps.add(new Step(now, elapsed, "(これより後の手順は省略)"));
            }
            logInfo(tag, "+" + elapsed + "ms " + message);
        }

        /** 時刻の印 (extra の timings_ms に、作ってからのミリ秒で出す)。 */
        public synchronized void mark(@NonNull String name, long at) { marks.put(name, at - createdAt); }

        public void mark(@NonNull String name) { mark(name, System.currentTimeMillis()); }

        /** tag に出す値 (方式・返送の HTTP ステータスなど)。 */
        public synchronized void set(@NonNull String key, @NonNull String value) { values.put(key, value); }

        @Nullable
        public synchronized String value(@NonNull String key) { return values.get(key); }

        @NonNull
        public synchronized List<Step> steps() { return new ArrayList<>(steps); }

        @NonNull
        public synchronized Map<String, Long> marks() { return new TreeMap<>(marks); }

        @NonNull
        public synchronized Map<String, String> values() { return new TreeMap<>(values); }
    }

    /** Log.i。単体テストでは android.util.Log が使えない (呼ぶと例外になる) ので、そのときは何もしない。 */
    public static void logInfo(@NonNull String tag, @NonNull String message) {
        try {
            android.util.Log.i(tag, message);
        } catch (RuntimeException ignored) {
            // 単体テスト (android.jar のスタブ)。
        }
    }

    /** 版。release は Wana の「どの版で起きたか」。 */
    public static final class Build {
        @NonNull final String versionName;
        final int versionCode;
        final boolean debug;

        public Build(@NonNull String versionName, int versionCode, boolean debug) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.debug = debug;
        }

        @NonNull
        public static Build current() {
            return new Build(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG);
        }
    }

    /** 1 つの手順の並び (category ごと)。 */
    public static final class Crumbs {
        @NonNull final String category;
        @NonNull final List<Step> steps;

        public Crumbs(@NonNull String category, @NonNull List<Step> steps) {
            this.category = category;
            this.steps = steps;
        }
    }

    /**
     * 1 件の記録 (Sentry のイベント)。message は理由ごとにまとまるよう、時間などの変わる値を入れない。
     * failure が null なら成功 (level info)。
     */
    @NonNull
    public static JsonObject event(@NonNull String name, @Nullable String failure, @NonNull String context,
                                   @NonNull String method, @Nullable String supportId, @NonNull Map<String, String> tags,
                                   @NonNull JsonObject extra, @NonNull List<Crumbs> crumbs, @NonNull Build build, long now) {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT));
        event.addProperty("timestamp", now / 1000.0);
        event.addProperty("platform", "java");
        event.addProperty("logger", name);
        event.addProperty("level", failure == null ? "info" : "error");
        event.addProperty("message", failure == null ? name + " success" : name + " failure: " + failure);
        event.addProperty("release", "uoa-app-android@" + build.versionName + "+" + build.versionCode);
        event.addProperty("environment", build.debug ? "debug" : "production");
        JsonObject t = new JsonObject();
        for (Map.Entry<String, String> e : new TreeMap<>(tags).entrySet()) t.addProperty(e.getKey(), e.getValue());
        t.addProperty("outcome", failure == null ? "success" : "failure");
        t.addProperty("context", context);
        t.addProperty("method", method);
        t.addProperty("platform", "android");
        t.addProperty("app_version", build.versionName);
        t.addProperty("build", String.valueOf(build.versionCode));
        if (supportId != null) t.addProperty("support_id", supportId);
        event.add("tags", t);
        event.add("extra", extra);
        List<Object[]> all = new ArrayList<>();
        for (Crumbs c : crumbs) for (Step s : c.steps) all.add(new Object[] {s, c.category});
        all.sort((a, b) -> Long.compare(((Step) a[0]).at, ((Step) b[0]).at));
        JsonArray values = new JsonArray();
        for (Object[] pair : all) {
            Step s = (Step) pair[0];
            JsonObject crumb = new JsonObject();
            crumb.addProperty("timestamp", s.at / 1000.0);
            crumb.addProperty("category", (String) pair[1]);
            crumb.addProperty("message", "+" + s.elapsedMs + "ms " + s.message);
            crumb.addProperty("level", "info");
            crumb.addProperty("type", "default");
            values.add(crumb);
        }
        JsonObject breadcrumbs = new JsonObject();
        breadcrumbs.add("values", values);
        event.add("breadcrumbs", breadcrumbs);
        return event;
    }

    /** ミリ秒の表を extra に入れる形にする。 */
    @NonNull
    public static JsonObject millis(@NonNull Map<String, Long> timings) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Long> e : timings.entrySet()) out.addProperty(e.getKey(), e.getValue());
        return out;
    }

    /** 失敗の detail から、まとめる単位 (先頭の scan-stall など) を取る。時間 (:t..) や繋ぎ直し (:r1) は tag と extra で見る。 */
    @NonNull
    public static String label(@NonNull String detail) {
        int colon = detail.indexOf(':');
        return colon < 0 ? detail : detail.substring(0, colon);
    }

    /** 今の問い合わせ ID (読めなければ null)。 */
    @Nullable
    public static String supportId() {
        try {
            return PreferenceManager.getInstance().getSupportId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * この形を送るか。同じ形は 1 日 (端末の日付) に 1 回、アプリの版ごとに送る。形を確かめるのが目的なので、
     * 認証アプリ方式で otp_type が無いのが普通の画面でも、ログイン (バックグラウンドを含む) のたびには送らない。
     * 送れたかは分からない (sendAsync) ので、圏外などで落ちても翌日にまた送れるよう、日付も印に入れる。読めなければ送らない。
     */
    public static synchronized boolean claimOtpPageShape(@NonNull String shape) {
        try {
            PreferenceManager prefs = PreferenceManager.getInstance();
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            String mark = day + "|" + BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")|" + shape;
            if (mark.equals(prefs.getOtpPageShapeSent())) return false;
            prefs.setOtpPageShapeSent(mark);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 設定の「利用状況と診断情報を送る」(PreferenceManager.isUsageStatsEnabled、既定はオン)。オフなら送らない。
     * 読めなければ (初期化の前など) 送らない側に倒す。
     */
    public static boolean enabled() {
        try {
            return PreferenceManager.getInstance().isUsageStatsEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 送る。全部を並べて送り、呼んだスレッドで全体で timeoutMs まで待つ (時間が来たら取りやめる)。
     * 中継の仕事のスレッドから、答えを返して処理済みにしたあとに呼ぶ。設定がオフなら送らない。
     */
    public static void sendBlocking(@NonNull List<JsonObject> events, long timeoutMs) {
        if (events.isEmpty() || !enabled()) return;
        OkHttpClient c = client(timeoutMs);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(events.size());
        List<Call> calls = new ArrayList<>();
        for (JsonObject event : events) {
            try {
                Call call = c.newCall(request(event));
                calls.add(call);
                call.enqueue(new Callback() {
                    @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        logInfo("IMAPOTPFetcher", "diagnostics not sent: " + e.getClass().getSimpleName());
                        done.countDown();
                    }

                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                        logInfo("IMAPOTPFetcher", "diagnostics sent -> " + response.code());
                        response.close();
                        done.countDown();
                    }
                });
            } catch (RuntimeException e) {
                logInfo("IMAPOTPFetcher", "diagnostics not sent: " + e.getClass().getSimpleName());
                done.countDown();
            }
        }
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                for (Call call : calls) call.cancel();
            }
        } catch (InterruptedException e) {
            for (Call call : calls) call.cancel();
            Thread.currentThread().interrupt();
        }
    }

    /** 送る (待たない)。アプリのログインの経路から呼ぶ。設定がオフなら送らない。 */
    public static void sendAsync(@NonNull List<JsonObject> events) {
        if (!enabled()) return;
        for (JsonObject event : events) {
            try {
                client(10_000).newCall(request(event)).enqueue(new Callback() {
                    @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        logInfo("IMAPOTPFetcher", "diagnostics not sent: " + e.getClass().getSimpleName());
                    }

                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                        logInfo("IMAPOTPFetcher", "diagnostics sent -> " + response.code());
                        response.close();
                    }
                });
            } catch (RuntimeException e) {
                logInfo("IMAPOTPFetcher", "diagnostics not sent: " + e.getClass().getSimpleName());
            }
        }
    }

    @NonNull
    static Request request(@NonNull JsonObject event) {
        return new Request.Builder()
                .url(STORE_URL)
                .header("X-Sentry-Auth", "Sentry sentry_version=7, sentry_client=uoa-otp-diagnostics-android/1.0, sentry_key=" + SENTRY_KEY)
                .post(RequestBody.create(event.toString(), JSON))
                .build();
    }

}
