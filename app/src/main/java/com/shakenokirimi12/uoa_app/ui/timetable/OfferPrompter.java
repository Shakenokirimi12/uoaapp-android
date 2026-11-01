package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.timetable.SharedTimetable;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableFriend;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareStore;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 「〇〇さんと今後も共有しますか？」(docs/timetable-share-v3.md の 10.5)。時間割共有の QR の画面と友達の一覧で、
 * 申し込み (offeredFeedId) のある友達に 1 人ずつ聞く。QR は誰でも見られるので、同意なしに時間割を置き続けない。
 */
final class OfferPrompter {
    private final Fragment fragment;
    private final ExecutorService executor;
    private final Runnable onChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** この画面で始められなかった友達。同じ画面で続けて聞き直さない (次に開いたときに聞く)。 */
    private final Set<String> skipped = new HashSet<>();
    private boolean busy;

    OfferPrompter(@NonNull Fragment fragment, @NonNull ExecutorService executor, @NonNull Runnable onChanged) {
        this.fragment = fragment;
        this.executor = executor;
        this.onChanged = onChanged;
    }

    void promptIfNeeded() {
        if (busy || !fragment.isAdded() || fragment.getView() == null) return;
        Context ctx = fragment.requireContext();
        TimetableShareStore store = TimetableShareStore.getInstance(ctx);
        TimetableFriend f = store.nextOffer(skipped);
        if (f == null) return;
        busy = true;
        Context app = ctx.getApplicationContext();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(ctx.getString(R.string.timetable_offer_title_format, f.name))
                .setMessage(R.string.timetable_offer_message)
                .setPositiveButton(R.string.timetable_offer_accept, (d, w) -> {
                    if (!fragment.isAdded() || executor.isShutdown()) { busy = false; return; }
                    executor.execute(() -> {
                        SharedTimetable mine = store.myTimetable(DataCache.getInstance(app).loadEvents(), System.currentTimeMillis());
                        boolean ok = store.acceptOffer(f.id, mine);
                        main.post(() -> {
                            busy = false;
                            if (!fragment.isAdded() || fragment.getView() == null) return;
                            onChanged.run();
                            if (ok) {
                                promptIfNeeded();
                                return;
                            }
                            skipped.add(f.id);
                            busy = true;
                            new MaterialAlertDialogBuilder(fragment.requireContext())
                                    .setMessage(R.string.timetable_offer_failed)
                                    .setPositiveButton(R.string.ok, null)
                                    .setOnDismissListener(x -> { busy = false; promptIfNeeded(); })
                                    .show();
                        });
                    });
                })
                .setNegativeButton(R.string.timetable_offer_decline, (d, w) -> {
                    store.declineOffer(f.id);
                    busy = false;
                    onChanged.run();
                    promptIfNeeded();
                })
                // 戻る・外側のタップでは閉じない。どちらかを選ぶまで答えを保留にしない (10.5)。
                .setCancelable(false)
                .show();
    }
}
