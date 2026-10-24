package com.shakenokirimi12.uoa_app.services.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;


import java.util.concurrent.TimeUnit;

/** 定期同期の登録。起動時に呼ぶ。 */
public final class SyncScheduler {
    private static final String WORK_NAME = "periodic_sync";
    /**
     * 同期の間隔。以前は設定で 15/30/60 分を選べたが、実際の時刻は OS が決め、利用者が変える理由も
     * 無いので既定の 1 時間に固定した (iOS の fetch_interval と同じ値)。
     */
    static final long INTERVAL_MINUTES = 60;

    private SyncScheduler() {}

    public static void schedule(@NonNull Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SyncWorker.class, INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        // UPDATE: 以前の版で別の間隔にしていた端末も、1 時間の周期に置き換える。次回実行時刻は保たれる。
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancel(@NonNull Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}
