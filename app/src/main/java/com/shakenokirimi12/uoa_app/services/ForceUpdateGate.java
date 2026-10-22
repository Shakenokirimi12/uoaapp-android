package com.shakenokirimi12.uoa_app.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.shakenokirimi12.uoa_app.BuildConfig;
import com.shakenokirimi12.uoa_app.data.models.AppConfig;

/**
 * 強制アップデートの状態。最後に取得できた条件 (最低バージョン・ストアの URL・文面) を端末に残し、
 * 起動直後 (取得の前) から、また取得に失敗している間も、その条件で止める。
 * 一度も取得できていない端末だけは止めない。判定の規則は ForceUpdatePolicy。
 *
 * 自分のバージョンは BuildConfig.VERSION_NAME (gradle.properties の uoaVersionName) だけを見る。
 * 止める画面は ForceUpdateActivity で、UoaApplication がどの Activity の上にも出す。
 */
public final class ForceUpdateGate {
    private static final String TAG = "ForceUpdateGate";
    // 秘密ではないので暗号化しない。バックアップから戻っても、入るのは公開済みの版なので
    // 戻した最低バージョンより古いことはなく、誤って止めることはない。
    static final String PREFS = "force_update";
    private static final String K_MIN = "min_version";
    private static final String K_URL = "store_url";
    private static final String K_MESSAGE = "message";

    private static ForceUpdateGate instance;

    private final SharedPreferences prefs;
    private final String ownVersion;
    @Nullable private ForceUpdatePolicy.Requirement requirement;
    private final MutableLiveData<Boolean> blocked = new MutableLiveData<>();
    private final Object saveLock = new Object();

    private ForceUpdateGate(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ownVersion = BuildConfig.VERSION_NAME;
        requirement = load();
        // 起動直後から、残っている条件で止める (取得を待たない)。
        blocked.setValue(ForceUpdatePolicy.isBlocked(ownVersion, requirement));
    }

    /** Application.onCreate (メインスレッド) で最初に呼ぶ。 */
    public static synchronized ForceUpdateGate getInstance(@NonNull Context context) {
        if (instance == null) instance = new ForceUpdateGate(context);
        return instance;
    }

    /** 止めるかどうか。メインスレッドで値が変わる。 */
    public LiveData<Boolean> blocked() { return blocked; }

    public synchronized boolean isBlocked() {
        return ForceUpdatePolicy.isBlocked(ownVersion, requirement);
    }

    @Nullable
    public synchronized String message() {
        return requirement != null ? requirement.message : null;
    }

    @NonNull
    public synchronized String storeUrl(@NonNull String packageName) {
        return ForceUpdatePolicy.storeUrl(requirement, packageName);
    }

    /**
     * 取得に成功した応答 (HTTP 2xx・success が true) を反映する。どのスレッドからでもよい。
     * 失敗したときは呼ばない (残っている条件をそのまま使う)。
     */
    public void onFetched(@NonNull AppConfig config) {
        boolean nowBlocked;
        boolean changed = false;
        synchronized (this) {
            ForceUpdatePolicy.Requirement next = ForceUpdatePolicy.afterSuccessfulFetch(
                    requirement, config.minRequiredVersion, config.forceUpdateURL, config.forceUpdateMessage);
            if (next == requirement && config.minRequiredVersion != null && !config.minRequiredVersion.trim().isEmpty()) {
                Log.w(TAG, "Ignoring unreadable minRequiredVersion: " + config.minRequiredVersion);
            }
            if (next == null ? requirement != null : !next.equals(requirement)) {
                requirement = next;
                changed = true;
            }
            nowBlocked = ForceUpdatePolicy.isBlocked(ownVersion, requirement);
        }
        // 書き込みはこのロックの外で行う。メインスレッドの isBlocked() をディスクの書き込みで待たせない。
        // 書き込みどうしは saveLock で順に並べ、そのときの最新の条件を書く (2 つの取得が同時に
        // 終わっても、古い方の条件が後から書かれて端末に残ることがないように)。
        if (changed) {
            synchronized (saveLock) {
                ForceUpdatePolicy.Requirement latest;
                synchronized (this) { latest = requirement; }
                save(latest);
            }
        }
        blocked.postValue(nowBlocked);
    }

    @Nullable
    private ForceUpdatePolicy.Requirement load() {
        String min = prefs.getString(K_MIN, null);
        if (min == null) return null;
        return new ForceUpdatePolicy.Requirement(min, prefs.getString(K_URL, null), prefs.getString(K_MESSAGE, null));
    }

    private void save(@Nullable ForceUpdatePolicy.Requirement r) {
        SharedPreferences.Editor e = prefs.edit();
        if (r == null) {
            e.remove(K_MIN).remove(K_URL).remove(K_MESSAGE);
        } else {
            e.putString(K_MIN, r.minVersion).putString(K_URL, r.storeUrl).putString(K_MESSAGE, r.message);
        }
        // 直後にプロセスが落ちても、次の起動で同じ条件で止められるよう同期で書く。
        // 書けなくても、このプロセスの間はメモリ上の条件で止める。
        if (!e.commit()) Log.w(TAG, "Could not store the force-update requirement");
    }
}
