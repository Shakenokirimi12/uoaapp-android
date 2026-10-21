package com.shakenokirimi12.uoa_app.ui.idp;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;

/**
 * ワンタイムパスワードが要求されたときだけ自動で出る入力ダイアログ。iOS の OTPPromptSheet の移植。
 * 設定画面から開く導線は持たない (OtpPromptCoordinator 参照)。
 *
 * 呼び出し元 (サービス層のワーカースレッド) は結果が返るまでブロックしているので、どの経路で
 * 閉じても listener を必ず 1 回呼ぶこと。画面回転で Activity が作り直されると、ここが掴んでいる
 * Activity は破棄済みになる。その場合はダイアログを出さずに (出していれば閉じて) null を返し、
 * 呼び出し側を解放する (同期は次回やり直す)。
 */
public final class OtpPromptDialog {
    /** 入力されたコード。キャンセル・閉じられた場合は null。必ず 1 回だけ呼ばれる。 */
    public interface Listener {
        void onResult(@Nullable String code);
    }

    private OtpPromptDialog() {}

    /** @param message 案内の文。メール方式なら自動取得に失敗したこと、認証アプリ方式なら入力の案内。 */
    @MainThread
    public static void show(@NonNull Activity activity, @NonNull String message, @NonNull Listener listener) {
        // 送信・キャンセル・破棄のどれで終わっても、待っているスレッドを必ず 1 回だけ解放する。
        final boolean[] delivered = {false};
        Listener once = code -> {
            if (delivered[0]) return;
            delivered[0] = true;
            listener.onResult(code);
        };

        // 前面 Activity を掴んでから main へ post するまでの間に回転・終了が起きうる。
        if (activity.isFinishing() || activity.isDestroyed()) {
            once.onResult(null);
            return;
        }

        View view = activity.getLayoutInflater().inflate(R.layout.dialog_otp_prompt, null);
        TextView textMessage = view.findViewById(R.id.text_otp_message);
        EditText editCode = view.findViewById(R.id.edit_otp_code);
        textMessage.setText(message);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle("ワンタイムパスワード")
                .setView(view)
                .setPositiveButton("送信", (d, which) -> once.onResult(editCode.getText().toString().trim()))
                .setNegativeButton("キャンセル", (d, which) -> once.onResult(null));

        androidx.appcompat.app.AlertDialog dialog = builder.create();

        Application.ActivityLifecycleCallbacks watcher = new DismissOnDestroy(activity, dialog);
        activity.getApplication().registerActivityLifecycleCallbacks(watcher);
        // 外側タップ・戻るキー・Activity 破棄、どの経路で閉じても解放し、監視も外す。
        dialog.setOnDismissListener(d -> {
            activity.getApplication().unregisterActivityLifecycleCallbacks(watcher);
            once.onResult(null);
        });

        try {
            dialog.show();
        } catch (RuntimeException e) {
            // 直前に Activity が壊れた場合の BadTokenException。ここで落とさず手入力を諦める。
            android.util.Log.w("OtpPromptDialog", "ダイアログを表示できなかった: " + e);
            activity.getApplication().unregisterActivityLifecycleCallbacks(watcher);
            once.onResult(null);
        }
    }

    /**
     * ダイアログを出した Activity が破棄されたら閉じる。放置すると WindowLeaked になり、
     * dismiss も走らないので呼び出し元がタイムアウトまでブロックし続ける。
     */
    private static final class DismissOnDestroy implements Application.ActivityLifecycleCallbacks {
        private final Activity owner;
        private final androidx.appcompat.app.AlertDialog dialog;

        DismissOnDestroy(Activity owner, androidx.appcompat.app.AlertDialog dialog) {
            this.owner = owner;
            this.dialog = dialog;
        }

        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            if (activity != owner) return;
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            if (dialog.isShowing()) dialog.dismiss();
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityResumed(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle out) { }
    }
}
