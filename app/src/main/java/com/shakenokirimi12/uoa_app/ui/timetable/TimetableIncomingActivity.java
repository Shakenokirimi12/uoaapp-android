package com.shakenokirimi12.uoa_app.ui.timetable;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.timetable.SharedTimetable;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableFriend;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareLink;
import com.shakenokirimi12.uoa_app.data.timetable.TimetableShareStore;
import com.shakenokirimi12.uoa_app.databinding.ActivityTimetableIncomingBinding;
import com.shakenokirimi12.uoa_app.services.TimetableShareApi;
import com.shakenokirimi12.uoa_app.ui.util.EdgeToEdge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 友達の共有を開く画面 (iOS の IncomingTimetableSheet)。標準のカメラで QR (uoa-app://timetable?id=...) を読む・
 * 案内ページの「アプリで開く」・一覧の「リンクを貼り付けて開く」から来る。
 * 友達の時間割を見せ、保存と同時に自分の時間割を送り返す (1 回の操作で交換が済む)。
 */
public class TimetableIncomingActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ActivityTimetableIncomingBinding binding;
    private TimetableShareStore store;
    @Nullable private String shareId;
    @Nullable private SharedTimetable incoming;

    @NonNull
    public static Intent intent(@NonNull Context ctx, @NonNull String shareId) {
        return new Intent(ctx, TimetableIncomingActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(TimetableShareLink.appUrl(shareId)));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTimetableIncomingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        EdgeToEdge.apply(this, binding.incomingRoot, null);
        store = TimetableShareStore.getInstance(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.buttonSaveAndSend.setOnClickListener(v -> accept(true));
        binding.buttonSaveOnly.setOnClickListener(v -> accept(false));
        binding.buttonSave.setOnClickListener(v -> accept(false));
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void handle(@Nullable Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        shareId = TimetableShareLink.shareId(data != null ? data.toString() : "");
        incoming = null;
        if (shareId == null) {
            result(false, "時間割の共有を開けませんでした", "QR コードかリンクが正しくありません。相手に、もう一度 QR コードを出してもらってください。", false);
            return;
        }
        progress(R.string.timetable_loading);
        String id = shareId;
        executor.execute(() -> {
            SharedTimetable t = null;
            String error = null;
            try {
                t = store.api().fetch(id);
            } catch (TimetableShareApi.Failure e) {
                error = e.getMessage();
            }
            SharedTimetable ft = t;
            String err = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || !id.equals(shareId)) return;
                if (ft != null) loaded(ft);
                else result(false, "受け取れませんでした", err, false);
            });
        });
    }

    @Nullable
    private SharedTimetable myTimetable() {
        return store.myTimetable(DataCache.getInstance(this).loadEvents(), System.currentTimeMillis());
    }

    private void progress(int textRes) {
        binding.groupProgress.setVisibility(View.VISIBLE);
        binding.textProgress.setText(textRes);
        binding.groupLoaded.setVisibility(View.GONE);
        binding.groupResult.setVisibility(View.GONE);
    }

    private void loaded(@NonNull SharedTimetable t) {
        incoming = t;
        binding.groupProgress.setVisibility(View.GONE);
        binding.groupResult.setVisibility(View.GONE);
        binding.groupLoaded.setVisibility(View.VISIBLE);
        binding.textName.setText(t.name.isEmpty() ? TimetableShareStore.UNNAMED : t.name + " さん");
        binding.textCount.setText("週 " + t.classes.size() + " コマ");
        binding.grid.setData(t.classes, null);
        boolean canSendBack = myTimetable() != null;
        binding.groupSendBack.setVisibility(canSendBack ? View.VISIBLE : View.GONE);
        binding.groupSaveOnly.setVisibility(canSendBack ? View.GONE : View.VISIBLE);
        binding.editName.setText(store.myName());
    }

    private void accept(boolean sendBack) {
        SharedTimetable t = incoming;
        String id = shareId;
        if (t == null || id == null) return;
        if (sendBack) store.setMyName(binding.editName.getText() != null ? binding.editName.getText().toString() : "");
        progress(R.string.timetable_saving);
        executor.execute(() -> {
            TimetableFriend friend = store.save(t, System.currentTimeMillis());
            boolean sent = false;
            String sendError = null;
            if (sendBack) {
                SharedTimetable mine = myTimetable();
                if (mine != null) {
                    try {
                        store.api().reply(id, mine);
                        sent = true;
                    } catch (TimetableShareApi.Failure e) {
                        sendError = e.getMessage();
                    }
                }
            }
            boolean s = sent;
            String err = sendError;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                String body = s ? "自分の時間割も送りました。相手の画面にも届きます。"
                        : err != null ? "自分の時間割は送れませんでした。" + err : null;
                result(true, friend.name + " さんの時間割を保存しました", body, err != null);
            });
        });
    }

    private void result(boolean ok, @NonNull String title, @Nullable String body, boolean bodyIsError) {
        binding.groupProgress.setVisibility(View.GONE);
        binding.groupLoaded.setVisibility(View.GONE);
        binding.groupResult.setVisibility(View.VISIBLE);
        binding.imageResult.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_alert);
        binding.imageResult.setImageTintList(ContextCompat.getColorStateList(this, ok ? R.color.success : R.color.warning));
        binding.textResultTitle.setText(title);
        binding.textResultBody.setVisibility(body != null ? View.VISIBLE : View.GONE);
        binding.textResultBody.setText(body);
        binding.textResultBody.setTextColor(bodyIsError
                ? ContextCompat.getColor(this, R.color.error)
                : com.google.android.material.color.MaterialColors.getColor(binding.textResultBody,
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
        binding.textResultHint.setVisibility(ok ? View.VISIBLE : View.GONE);
        binding.toolbar.setNavigationContentDescription(R.string.timetable_close);
    }
}
