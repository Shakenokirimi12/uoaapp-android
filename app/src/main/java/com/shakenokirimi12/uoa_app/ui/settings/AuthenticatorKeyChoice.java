package com.shakenokirimi12.uoa_app.ui.settings;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.ui.idp.OtpReregistrationHost;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 認証アプリ方式なのに、このアプリに使える鍵が無い (無い・止めている) 人が、前面のログインでコードを求められたときの選択
 * (iOS の OTPPromptCoordinator が手入力の前に出す選択と同じ)。以前は起動時に一度だけ登録し直しを案内していた
 * (AuthenticatorKeyPrompt、2026-10-31 に廃止) が、ログインのたびに手入力を求められるまま気づかれにくかった。
 * コードが要るその場で、取り込む・登録し直す・手入力する・あとで を選んでもらう。
 *
 * - 認証アプリから取り込む: OtpKeyImportFlow。保存できたら IMPORTED を返し、待っているログインは取り込んだ鍵で
 *   コードを作って続ける (何も打たせない)。手順を途中で閉じたら、この選択に戻る
 * - 認証アプリに登録し直す: 待っているログインを取り消し、登録し直しの画面を開く (OtpPromptCoordinator)。
 *   開けるのは OtpReregistrationHost の画面 (メイン画面と初期設定)。それ以外の画面では出さない
 * - コードを入力してログイン: 今までの手入力 (OtpPromptDialog)
 * - あとで・戻る・外側のタップ: 待っているログインを取り消す (手入力を取り消したときと同じ)
 *
 * 呼び出し元のスレッドは結果が返るまでブロックしているので、どの経路で終わっても listener を必ず 1 回呼ぶ。
 * 出した Activity が破棄されたら (回転を含む) 閉じて LATER を返す (OtpPromptDialog と同じ。同期は次にやり直す)。
 *
 * 取り込みの手順の途中で待ち受けが時間切れになったら (Handle.cancel)、ログインには LATER を返すが、手順は閉じずに
 * 続けてもらう (別のアプリで鍵を書き出している最中に閉じると、やり直しになる)。保存できたらホームで取り直す
 * (MainActivity.syncAfterKeyImport)。iOS の OTPPromptCoordinator も同じ。
 */
public final class AuthenticatorKeyChoice {
    public enum Outcome { MANUAL, IMPORTED, REREGISTER, LATER }

    /** 必ず 1 回だけ呼ばれる。 */
    public interface Listener { void onResult(@NonNull Outcome outcome); }

    /** 待つのをやめた (時間切れ) ときに、出している選択を閉じる。取り込みの手順の途中なら、手順だけは残す。 */
    public interface Handle { void cancel(); }

    /** 写真の選択の登録キーを、出すたびに変える (前の選択の結果を取り違えない)。 */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private AuthenticatorKeyChoice() {}

    @MainThread
    @NonNull
    public static Handle show(@NonNull Activity activity, @NonNull String uid, @NonNull Listener listener) {
        Session session = new Session(activity, uid, listener);
        session.start();
        return session::timedOut;
    }

    private static final class Session implements OtpKeyImportFlow.Host, Application.ActivityLifecycleCallbacks {
        private final Activity activity;
        private final String uid;
        private final Listener listener;
        private final PreferenceManager prefs;
        private boolean done;
        /** listener を呼んだ。時間切れのあとも取り込みを続けているときは、done より先に立つ。 */
        private boolean answered;
        /** 時間切れのあとも取り込みの手順を続けている。待っているログインはもう無い。 */
        private boolean detached;
        @Nullable private AlertDialog choice;
        @Nullable private OtpKeyImportFlow flow;
        @Nullable private ActivityResultLauncher<PickVisualMediaRequest> picker;

        Session(Activity activity, String uid, Listener listener) {
            this.activity = activity;
            this.uid = uid;
            this.listener = listener;
            this.prefs = PreferenceManager.getInstance(activity);
        }

        void start() {
            // 前面 Activity を掴んでから main へ post するまでの間に回転・終了が起きうる。
            if (!isAlive()) {
                finish(Outcome.LATER);
                return;
            }
            activity.getApplication().registerActivityLifecycleCallbacks(this);
            showChoice();
        }

        private void showChoice() {
            if (done || !isAlive()) return;
            View view = activity.getLayoutInflater().inflate(R.layout.dialog_otp_no_key_choice, null);
            TextView message = view.findViewById(R.id.text_no_key_message);
            message.setText(prefs.isTotpSuspended(uid) ? R.string.otp_no_key_message_suspended : R.string.otp_no_key_message);
            View importButton = view.findViewById(R.id.button_no_key_import);
            View reregisterButton = view.findViewById(R.id.button_no_key_reregister);
            // 写真の選択には ActivityResultRegistry が要る (このアプリの画面はどれも ComponentActivity)。
            importButton.setVisibility(activity instanceof ComponentActivity ? View.VISIBLE : View.GONE);
            reregisterButton.setVisibility(offersReregistration() ? View.VISIBLE : View.GONE);

            // 次の段へ進めて閉じたか。進めずに閉じたら (あとで・戻る・外側のタップ) 取り消す。
            boolean[] advanced = {false};
            AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.otp_no_key_title)
                    .setView(view)
                    .setNegativeButton(R.string.otp_no_key_later, null)
                    .create();
            dialog.setOnDismissListener(d -> {
                if (choice == dialog) choice = null;
                if (!advanced[0]) finish(Outcome.LATER);
            });
            importButton.setOnClickListener(v -> {
                advanced[0] = true;
                dialog.dismiss();
                startImport();
            });
            reregisterButton.setOnClickListener(v -> {
                advanced[0] = true;
                dialog.dismiss();
                reregister();
            });
            view.findViewById(R.id.button_no_key_manual).setOnClickListener(v -> {
                advanced[0] = true;
                dialog.dismiss();
                finish(Outcome.MANUAL);
            });
            choice = dialog;
            try {
                dialog.show();
            } catch (RuntimeException e) {
                // 直前に Activity が壊れた場合の BadTokenException。落とさずに取り消す。
                android.util.Log.w("AuthenticatorKeyChoice", "ダイアログを表示できなかった: " + e);
                finish(Outcome.LATER);
            }
        }

        private void startImport() {
            if (done) return;
            if (picker == null && activity instanceof ComponentActivity) {
                // 後から登録するので、lifecycle を持たない register を使い、終わったら必ず外す (finish)。
                picker = ((ComponentActivity) activity).getActivityResultRegistry().register(
                        "otp-key-import-login-" + SEQUENCE.incrementAndGet(),
                        new ActivityResultContracts.PickVisualMedia(),
                        uri -> {
                            if (!done && flow != null) flow.onImagePicked(uri);
                        });
            }
            flow = new OtpKeyImportFlow(activity, prefs, uid, this);
            flow.start();
        }

        // ---- OtpKeyImportFlow.Host ----

        @Override public boolean isAlive() {
            return !done && !activity.isFinishing() && !activity.isDestroyed();
        }

        @Override public void launchImagePicker() {
            if (picker == null) {
                showChoice();
                return;
            }
            picker.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        }

        @Override public void onSaved() {
            Toast.makeText(activity, R.string.otp_import_done_title, Toast.LENGTH_SHORT).show();
            boolean resync = detached;
            finish(Outcome.IMPORTED);
            // 待っていたログインは時間切れで終わっている。取り込んだ鍵でログインし直して、データを読む。
            if (resync && activity instanceof OtpReregistrationHost) ((OtpReregistrationHost) activity).syncAfterKeyImport();
        }

        /** 取り込みをやめた。待っているログインは取り消さず、選択に戻す (手入力や登録し直しを選べるように)。 */
        @Override public void onClosed() {
            flow = null;
            if (detached) {
                finish(Outcome.LATER);
                return;
            }
            showChoice();
        }

        /** 登録し直しを開けるのは、メイン画面と初期設定 (OtpReregistrationHost)。 */
        @Override public boolean offersReregistration() {
            return activity instanceof OtpReregistrationHost;
        }

        @Override public void reregister() {
            if (done) return;
            // 始められないとき (ID / パスワードが無い・誤り) は設定画面と同じトーストを出し、選択に戻す。
            if (!offersReregistration() || !((OtpReregistrationHost) activity).canStartReregistration()) {
                if (!detached) showChoice();
                return;
            }
            boolean open = detached;
            finish(Outcome.REREGISTER);
            // 待っていたログインはもう無いので、OtpPromptCoordinator を通さずにここで開く。
            if (open) ((OtpReregistrationHost) activity).openOtpReregistration();
        }

        /** 待ち受けが時間切れになった。取り込みの手順の途中なら手順を残し、それ以外は閉じる。 */
        void timedOut() {
            if (done) return;
            if (flow == null) {
                finish(Outcome.LATER);
                return;
            }
            detached = true;
            answer(Outcome.LATER);
        }

        /** 待っているログインに 1 回だけ答える。 */
        private void answer(@NonNull Outcome outcome) {
            if (answered) return;
            answered = true;
            listener.onResult(outcome);
        }

        void finish(@NonNull Outcome outcome) {
            if (done) return;
            done = true;
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            if (picker != null) {
                picker.unregister();
                picker = null;
            }
            if (flow != null) {
                flow.dismiss();
                flow = null;
            }
            AlertDialog d = choice;
            choice = null;
            if (d != null && d.isShowing()) d.dismiss();
            answer(outcome);
        }

        // ---- Application.ActivityLifecycleCallbacks ----

        /** 出した Activity が破棄された (回転を含む)。放置すると WindowLeaked になり、呼び出し元が時間切れまで待ち続ける。 */
        @Override public void onActivityDestroyed(@NonNull Activity destroyed) {
            if (destroyed == activity) finish(Outcome.LATER);
        }

        @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity a) { }
        @Override public void onActivityResumed(@NonNull Activity a) { }
        @Override public void onActivityPaused(@NonNull Activity a) { }
        @Override public void onActivityStopped(@NonNull Activity a) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle out) { }
    }
}
