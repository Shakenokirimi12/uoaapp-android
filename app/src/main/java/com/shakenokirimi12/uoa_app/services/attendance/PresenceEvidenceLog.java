package com.shakenokirimi12.uoa_app.services.attendance;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.services.LocationGeofenceService;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 位置の監視の証拠を SharedPreferences に残す (iOS の PresenceEvidenceLog に当たる)。端末の外へは出さない。
 *
 * アプリのプロセスが落ちてもジオフェンスは Google Play 開発者サービスが持ち続ける。ただし、強制停止すると
 * 通知の宛先 (PendingIntent) が消え、再起動ではジオフェンス自体が消える。どちらも次にプロセスが起きるまで
 * 分からないので、起動のたびに前の終了の理由 (API 30 以上) と再起動を見て、監視が続いていたと言えない区間を
 * GAP として残す。理由を読めない API 29 以下では、自動の欠席を付けない (AutoAbsenceRunner)。
 */
public final class PresenceEvidenceLog {
    private static final String TAG = "PresenceEvidence";
    private static final String PREF_NAME = "presence_evidence";
    private static final String KEY_ENTRIES = "entries_v1";
    private static final String KEY_LAST_ALIVE = "last_alive";
    private static final String KEY_LAST_EXIT_SEEN = "last_exit_seen";
    private static final String KEY_REGISTERED = "geofence_registered";
    /** 監視の証拠を残す期間。判定の範囲 (AutoAbsencePlanner.LOOKBACK_MS) より長くし、授業の前の記録まで残す。 */
    static final long RETENTION_MS = 10L * 24 * 60 * 60_000L;

    private static final Gson gson = new Gson();

    private PresenceEvidenceLog() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public static synchronized List<PresenceEvidence.Entry> entries(@NonNull Context ctx) {
        return load(prefs(ctx));
    }

    public static synchronized void append(@NonNull Context ctx, @NonNull PresenceEvidence.Kind kind,
                                           @Nullable Boolean crossing, long at) {
        appendLocked(prefs(ctx), new PresenceEvidence.Entry(kind, at, crossing, null), System.currentTimeMillis());
    }

    /** ジオフェンスの登録に成功した・登録を外した (消えた)。Android には登録済みの一覧を読む API が無いので自分で持つ。 */
    public static synchronized void setRegistered(@NonNull Context ctx, boolean registered) {
        prefs(ctx).edit().putBoolean(KEY_REGISTERED, registered).apply();
    }

    /** 今の監視の状態を証拠として残す。 */
    public static void checkpoint(@NonNull Context ctx) {
        boolean ok = isMonitoringHealthy(ctx);
        append(ctx, ok ? PresenceEvidence.Kind.MONITORING_OK : PresenceEvidence.Kind.MONITORING_LOST, null,
                System.currentTimeMillis());
    }

    /** 自動の欠席の根拠になる「監視が動いていたか」。どれかが欠けていれば、到着を取りこぼしうる。 */
    public static boolean isMonitoringHealthy(@NonNull Context ctx) {
        if (!PreferenceManager.getInstance(ctx).isAutoAttendanceEnabled()) return false;
        if (!AppConfigService.getInstance().isFeatureEnabled("auto_attendance_enabled")) return false;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return false;
        // 「常に許可」が無いと、アプリを閉じている間はジオフェンスの通知が届かない。
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        if (lm == null || !LocationManagerCompat.isLocationEnabled(lm)) return false;
        if (Build.VERSION.SDK_INT >= 28) {
            // バッテリーセーバーが位置情報を絞る設定のとき、画面が消えている間は位置を取らない。
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm != null && pm.isPowerSaveMode()
                    && pm.getLocationPowerSaveMode() != PowerManager.LOCATION_MODE_NO_CHANGE) return false;
            // バックグラウンドの動作を「制限」にされていると、閉じている間の通知を当てにできない。
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            if (am != null && am.isBackgroundRestricted()) return false;
        }
        synchronized (PresenceEvidenceLog.class) {
            return prefs(ctx).getBoolean(KEY_REGISTERED, false);
        }
    }

    /**
     * プロセスが起きたとき (UoaApplication)。前に生きていた時刻から今までに、強制停止などの終了か再起動があれば
     * GAP を残し、ジオフェンスを登録し直す。
     */
    public static void onProcessStart(@NonNull Context ctx) {
        try {
            onProcessStartUnchecked(ctx);
        } catch (RuntimeException e) {
            // 起動を止めない。読めなかったときは監視が続いていたと言えないので、分からない区間にする。
            Log.e(TAG, "Could not check the previous exit", e);
            try {
                synchronized (PresenceEvidenceLog.class) {
                    SharedPreferences sp = prefs(ctx);
                    long now = System.currentTimeMillis();
                    long since = sp.getLong(KEY_LAST_ALIVE, 0L);
                    sp.edit().putBoolean(KEY_REGISTERED, false).apply();
                    if (since > 0) appendLocked(sp, new PresenceEvidence.Entry(PresenceEvidence.Kind.GAP, now, null, since), now);
                }
            } catch (RuntimeException ignored) {
                // 保存先にも書けなければ、次の起動で同じ確認をし直す。
            }
        }
    }

    private static void onProcessStartUnchecked(@NonNull Context ctx) {
        long now = System.currentTimeMillis();
        boolean reRegister;
        synchronized (PresenceEvidenceLog.class) {
            SharedPreferences sp = prefs(ctx);
            long lastAlive = sp.getLong(KEY_LAST_ALIVE, 0L);
            long lastExitSeen = sp.getLong(KEY_LAST_EXIT_SEEN, 0L);
            List<long[]> exits = Build.VERSION.SDK_INT >= 30 ? readExits(ctx) : new ArrayList<>();
            long newestExit = lastExitSeen;
            for (long[] exit : exits) newestExit = Math.max(newestExit, exit[0]);
            Long since = gapSince(lastAlive, now - SystemClock.elapsedRealtime(), exits, lastExitSeen);
            // 落ちている間に強制停止されると終了の理由は残らないが、通知の宛先は消える。
            if (since == null && lastAlive > 0 && sp.getBoolean(KEY_REGISTERED, false)
                    && !LocationGeofenceService.hasLivePendingIntent(ctx)) {
                since = lastAlive;
            }
            SharedPreferences.Editor edit = sp.edit().putLong(KEY_LAST_EXIT_SEEN, newestExit);
            if (since != null) {
                edit.putBoolean(KEY_REGISTERED, false);
            }
            edit.apply();
            if (since != null) {
                Log.d(TAG, "Monitoring may have stopped since " + since);
                appendLocked(sp, new PresenceEvidence.Entry(PresenceEvidence.Kind.GAP, now, null, since), now);
            } else {
                touchLocked(sp, now);
            }
            reRegister = since != null;
        }
        if (reRegister) LocationGeofenceService.startIfEnabled(ctx);
    }

    /**
     * 監視が続いていたと言えない区間の始まり。言えるなら null。
     *
     * @param exits 前の終了 ({時刻, 理由}) の一覧。
     */
    @VisibleForTesting
    @Nullable
    static Long gapSince(long lastAlive, long bootedAt, @NonNull List<long[]> exits, long lastExitSeen) {
        if (lastAlive <= 0) return null; // 初めての起動。つなぐ前の証拠が無い。
        Long since = null;
        // 再起動するとジオフェンスは消え、登録し直すまで監視されない。止まった時刻は分からないので、前に生きていた時刻から。
        if (bootedAt > lastAlive) since = lastAlive;
        for (long[] exit : exits) {
            if (exit[0] <= lastExitSeen || isHarmlessExit((int) exit[1])) continue;
            since = since == null ? exit[0] : Math.min(since, exit[0]);
        }
        return since;
    }

    /**
     * ジオフェンスが残る終了。メモリ不足やクラッシュで落ちても登録は Play 開発者サービスに残る。
     * 強制停止 (最近のアプリから消したときも同じ理由になる)・権限の変更・更新などは残ると言えないので含めない。
     */
    @VisibleForTesting
    static boolean isHarmlessExit(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF:
            case ApplicationExitInfo.REASON_SIGNALED:
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
                return true;
            default:
                return false;
        }
    }

    private static List<long[]> readExits(Context ctx) {
        List<long[]> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) return out;
        try {
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            if (am == null) return out;
            for (ApplicationExitInfo info : am.getHistoricalProcessExitReasons(null, 0, 0)) {
                out.add(new long[] {info.getTimestamp(), info.getReason()});
            }
        } catch (RuntimeException e) {
            // 読めなければ、終了の理由が分からない区間として扱う (欠席を付けない側に倒す)。
            Log.w(TAG, "Could not read exit reasons", e);
            out.add(new long[] {System.currentTimeMillis(), ApplicationExitInfo.REASON_UNKNOWN});
        }
        return out;
    }

    private static void touchLocked(SharedPreferences sp, long now) {
        sp.edit().putLong(KEY_LAST_ALIVE, now).apply();
    }

    private static void appendLocked(SharedPreferences sp, PresenceEvidence.Entry entry, long now) {
        List<PresenceEvidence.Entry> list = load(sp);
        long cutoff = now - RETENTION_MS;
        list.removeIf(e -> e.at < cutoff);
        // 同じ確認が続くときは、続いた区間の最初と最後だけを残す (前面に出るたびに増え続けないように)。
        // 最初を残すのは「授業の前から動いていた」、最後を残すのは「授業の後も動いていた」の根拠だから。
        int n = list.size();
        if (entry.isCheckpoint() && n >= 2 && list.get(n - 1).kind == entry.kind && list.get(n - 2).kind == entry.kind) {
            list.set(n - 1, entry);
        } else {
            list.add(entry);
        }
        sp.edit().putString(KEY_ENTRIES, gson.toJson(list)).putLong(KEY_LAST_ALIVE, now).apply();
    }

    private static List<PresenceEvidence.Entry> load(SharedPreferences sp) {
        String json = sp.getString(KEY_ENTRIES, null);
        if (json == null) return new ArrayList<>();
        Type type = new TypeToken<List<PresenceEvidence.Entry>>() {}.getType();
        try {
            List<PresenceEvidence.Entry> list = gson.fromJson(json, type);
            if (list == null) return new ArrayList<>();
            list.removeIf(e -> e == null || e.kind == null);
            return list;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read evidence", e);
            return new ArrayList<>();
        }
    }
}
