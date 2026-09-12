package com.shakenokirimi12.uoa_app.services.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.shakenokirimi12.uoa_app.data.PreferenceManager;

import java.util.concurrent.TimeUnit;

/** 定期同期の登録。起動時と、設定画面で間隔を変えたときに呼ぶ。 */
public final class SyncScheduler {
    private static final String WORK_NAME = "periodic_sync";

    private SyncScheduler() {}

    public static void schedule(@NonNull Context context) {
        PreferenceManager prefs = PreferenceManager.getInstance(context);
        // WorkManager の下限は 15 分。設定の選択肢 (15/30/60) はそれに合わせてある。
        long minutes = Math.max(15, prefs.getSyncInterval());

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SyncWorker.class, minutes, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        // UPDATE: 間隔を変えたときに既存の周期を置き換える。次回実行時刻は保たれる。
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancel(@NonNull Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}
