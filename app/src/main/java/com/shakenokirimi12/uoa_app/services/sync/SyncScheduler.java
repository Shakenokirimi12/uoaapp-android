package com.shakenokirimi12.uoa_app.services.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
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

    /**
     * 今すぐ 1 回だけ同期する。IdP チュートリアルを「今すぐログインする」で終えたときに使う
     * (ログインは同期の裏で完結するので、専用のログイン画面は出さない)。
     */
    public static void syncNow(@NonNull Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        // KEEP: 連打しても走っている同期を積み増さない。
        WorkManager.getInstance(context).enqueueUniqueWork("sync_now", ExistingWorkPolicy.KEEP, request);
    }

    public static void cancel(@NonNull Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}
