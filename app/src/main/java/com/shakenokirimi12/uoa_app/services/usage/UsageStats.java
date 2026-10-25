package com.shakenokirimi12.uoa_app.services.usage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shakenokirimi12.uoa_app.BuildConfig;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.NetworkClient;
import com.shakenokirimi12.uoa_app.services.PushNotificationService;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 画面ごとの利用回数を端末で数え、1 日 1 回、前日までの分を自前のサーバー (POST /push/api/usage) に送る。
 *
 * 送るもの: 日付ごとの機能の回数、利用状況だけの ID (installId)、OS、アプリの版、学籍番号の区分と期 (UsageCohort)。
 * 送らないもの: 学籍番号そのもの・残りの桁、端末 ID (device_id)、FCM のトークン、時間割などの中身。
 *
 * installId は送るときに初めて作る乱数で、このファイル (usage_stats) にだけ置く。device_id などとは別に作り、
 * 一緒に送らない・ログに出さない。設定で OFF にするかログアウトすると、回数と一緒に消す (次に送るとき作り直す)。
 * usage_stats は OS のバックアップから外してある (xml/backup_rules, xml/data_extraction_rules)。別の端末に
 * 同じ ID を持ち込まないため。
 *
 * 送り直し: 2xx と 400 / 413 (送り直しても通らない) は送った日を消す。429・5xx・通信の失敗は残し、
 * 1 時間あけて次のきっかけ (前面に出たとき・バックグラウンドの同期) で送り直す。サーバーは
 * (installId, 日, 機能) の最初の 1 回だけを数えるので、届いたのに応答を受け取れなかった分を送り直しても二重にならない。
 */
public final class UsageStats {
    private static final String TAG = "UsageStats";
    static final String PREFS = "usage_stats";
    private static final String KEY_COUNTS = "counts_v1";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_LAST_FAILURE_AT = "last_failure_at";
    private static final long RETRY_AFTER_FAILURE_MS = TimeUnit.HOURS.toMillis(1);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final Object lock = new Object();
    /** 読み込んだ台帳。プロセスで 1 つ (記録のたびに JSON を読み直さない)。lock の中でだけ触る。 */
    @Nullable private static UsageLedger cached;
    private static final AtomicBoolean sending = new AtomicBoolean(false);
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private UsageStats() {}

    /** 機能を 1 回使ったと数える。OFF なら何もしない。どのスレッドからでもよい。 */
    public static void record(@NonNull Context context, @NonNull String feature) {
        Context app = context.getApplicationContext();
        // ON/OFF は lock の中で見る。外で見ると、見た直後に OFF (setEnabled) が回数を消し、そのあとで
        // ここが書き戻して、OFF にしたのに回数が残る。
        synchronized (lock) {
            if (!PreferenceManager.getInstance(app).isUsageStatsEnabled()) return;
            UsageLedger ledger = ledger(app);
            ledger.increment(LocalDate.now(), feature);
            save(app, ledger);
        }
    }

    /** 設定の「利用状況と診断情報を送る」。OFF にしたら、まだ送っていない回数と installId を消す。 */
    public static void setEnabled(@NonNull Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        // record と同じ lock の中で切り替えて消す (record が OFF の後に書き戻さないように)。
        synchronized (lock) {
            PreferenceManager.getInstance(app).setUsageStatsEnabled(enabled);
            if (!enabled) clearLocal(app);
        }
    }

    /** 回数・installId・失敗の記録を消す (OFF・ログアウト)。設定の値はここでは変えない。 */
    public static void clearLocal(@NonNull Context context) {
        Context app = context.getApplicationContext();
        synchronized (lock) {
            cached = new UsageLedger();
            prefs(app).edit().clear().apply();
        }
    }

    /** 前面に出たときに呼ぶ。送る分があれば裏のスレッドで送る。 */
    public static void sendIfDueAsync(@NonNull Context context) {
        Context app = context.getApplicationContext();
        executor.execute(() -> sendIfDue(app));
    }

    /**
     * 送る分があれば送る。通信するので、メインスレッドでは呼ばない (バックグラウンドの同期から呼ぶ)。
     * 例外は投げない (失敗しても呼び出し元の処理を止めない)。
     */
    public static void sendIfDue(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (!sending.compareAndSet(false, true)) return;
        try {
            sendSerialized(app);
        } catch (RuntimeException e) {
            Log.w(TAG, "usage report failed: " + e.getClass().getSimpleName());
        } finally {
            sending.set(false);
        }
    }

    private static void sendSerialized(Context app) {
        PreferenceManager settings = PreferenceManager.getInstance(app);
        if (!settings.isUsageStatsEnabled()) return;
        long now = System.currentTimeMillis();
        long lastFailure = prefs(app).getLong(KEY_LAST_FAILURE_AT, 0);
        if (lastFailure > 0 && now >= lastFailure && now - lastFailure < RETRY_AFTER_FAILURE_MS) return;

        LocalDate today = LocalDate.now();
        List<String> days;
        String body;
        synchronized (lock) {
            if (!settings.isUsageStatsEnabled()) return;
            UsageLedger ledger = ledger(app);
            if (ledger.dropExpired(today)) save(app, ledger);
            days = ledger.sendableDays(today);
            if (days.isEmpty()) return;
            body = ledger.buildBody(installId(app), BuildConfig.VERSION_NAME,
                    UsageCohort.parse(settings.getUsername()), days).toString();
        }

        Integer status = null;
        try {
            OkHttpClient client = NetworkClient.getNoCookieClient().newBuilder()
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build();
            Request req = new Request.Builder()
                    .url(PushNotificationService.BASE_URL + "/api/usage")
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                status = resp.code();
            }
        } catch (Exception e) {
            Log.d(TAG, "usage report not sent: " + e.getClass().getSimpleName());
        }

        synchronized (lock) {
            SharedPreferences.Editor editor = prefs(app).edit();
            if (status != null && isFinal(status)) {
                // OFF にされていたら clearLocal が済ませている (ここで書き戻すと消した ID が残る)。
                if (!settings.isUsageStatsEnabled()) return;
                UsageLedger ledger = ledger(app);
                ledger.remove(days);
                editor.putString(KEY_COUNTS, ledger.toJson()).remove(KEY_LAST_FAILURE_AT).apply();
                if (status >= 400) Log.w(TAG, "usage report rejected (" + status + "); dropped " + days.size() + " day(s)");
            } else {
                if (!settings.isUsageStatsEnabled()) return;
                editor.putLong(KEY_LAST_FAILURE_AT, now).apply();
                if (status != null) Log.d(TAG, "usage report failed (" + status + "); will retry");
            }
        }
    }

    /** 送った日を消してよい応答か。2xx と、送り直しても通らない 400 / 413。 */
    static boolean isFinal(int status) {
        return (status >= 200 && status < 300) || status == 400 || status == 413;
    }

    private static UsageLedger ledger(Context app) {
        if (cached == null) cached = UsageLedger.fromJson(prefs(app).getString(KEY_COUNTS, null));
        return cached;
    }

    private static void save(Context app, UsageLedger ledger) {
        prefs(app).edit().putString(KEY_COUNTS, ledger.toJson()).apply();
    }

    /** lock の中で呼ぶ。無ければ作って保存する。 */
    private static String installId(Context app) {
        SharedPreferences p = prefs(app);
        String id = p.getString(KEY_INSTALL_ID, null);
        if (id != null && id.matches("^[0-9a-f]{32}$")) return id;
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        id = sb.toString();
        p.edit().putString(KEY_INSTALL_ID, id).apply();
        return id;
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
