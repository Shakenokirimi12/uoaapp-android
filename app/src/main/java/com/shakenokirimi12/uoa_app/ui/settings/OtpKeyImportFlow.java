package com.shakenokirimi12.uoa_app.ui.settings;

import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.OtpAuthImport;

import java.util.ArrayList;
import java.util.List;

/**
 * 認証アプリにすでにある大学のアカウントのセットアップキーを取り込む手順 (アプリの選択 → 出し方の案内 →
 * スクリーンショットか貼り付け → 候補の選択 → 保存)。ログインの設定画面と、鍵の無い人がログインでコードを求められたときの
 * 選択 (AuthenticatorKeyChoice) の両方から使う。終わり方 (保存した・閉じた・登録し直しを選んだ) は Host に返す。
 *
 * 写真の選択は呼び出し側が持つ ActivityResultLauncher で開き、結果を onImagePicked に渡す。Fragment は作成時に
 * 登録しないといけないが、ログイン中の選択は前面の Activity に後から登録するので、登録の仕方を呼び出し側に任せる。
 * 鍵も QR の中身もログに出さない。
 */
public final class OtpKeyImportFlow {
    public interface Host {
        /** 画面がまだ使えるか (Fragment が付いている、Activity が壊れていない)。 */
        boolean isAlive();
        /** 写真を選ぶ画面を開く。結果は onImagePicked で返す。 */
        void launchImagePicker();
        /** 鍵を保存した。 */
        void onSaved();
        /** 保存せずに手順を閉じた (キャンセル・戻る・外側のタップ・読めなかった案内を閉じた・写真を選ばなかった)。 */
        void onClosed();
        /** 「認証アプリに登録し直す」を出すか。出さない画面では、取り込めないアプリの案内に戻るボタンだけを出す。 */
        boolean offersReregistration();
        /** 「認証アプリに登録し直す」を押した。手順のダイアログは閉じてある。 */
        void reregister();
    }

    /** ダイアログを次の段に進めて閉じたか。進めずに閉じたら (戻る・外側のタップ・キャンセル) 手順を閉じたことにする。 */
    private static final class Step {
        boolean advanced;
    }

    private final Context context;
    private final PreferenceManager prefs;
    /** 鍵の持ち主。設定画面では保存しているユーザー名、ログイン中はログインしているユーザー名。 */
    private final String username;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private AlertDialog showing;
    /** dismiss() で閉じた。以後のダイアログの閉じは Host に返さない。 */
    private boolean silenced;

    public OtpKeyImportFlow(@NonNull Context context, @NonNull PreferenceManager prefs, @NonNull String username,
                            @NonNull Host host) {
        this.context = context;
        this.prefs = prefs;
        this.username = username;
        this.host = host;
    }

    /** 使っている認証アプリを選んでもらう。選んだアプリでの鍵の出し方を showAppGuide で出す。 */
    @MainThread
    public void start() {
        if (username.isEmpty() || !host.isAlive()) return;
        OtpImportApp[] apps = OtpImportApp.values();
        String[] labels = new String[apps.length];
        for (int i = 0; i < apps.length; i++) {
            String name = context.getString(apps[i].name);
            labels[i] = apps[i].method == OtpImportApp.Method.NONE
                    ? context.getString(R.string.otp_import_app_unsupported, name) : name;
        }
        Step step = new Step();
        show(step, new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.otp_import_apps_title)
                .setItems(labels, (d, which) -> {
                    step.advanced = true;
                    showAppGuide(apps[which]);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    /** 出しているダイアログを閉じる (画面が壊れるとき)。Host には何も返さない。 */
    @MainThread
    public void dismiss() {
        silenced = true;
        AlertDialog d = showing;
        showing = null;
        if (d != null && d.isShowing()) d.dismiss();
    }

    /** 写真を選び終えた。選ばなかったら null。 */
    @MainThread
    public void onImagePicked(@Nullable Uri uri) {
        if (!host.isAlive()) return;
        if (uri == null) {
            host.onClosed();
            return;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            List<String> texts = OtpKeyImport.readQrs(app, uri);
            // 読んでいる間に画面が閉じられることがある。そのときは捨てる。
            main.post(() -> {
                if (host.isAlive()) handleQr(texts);
            });
        }, "otp-key-import").start();
    }

    /** そのアプリでの鍵の出し方と、読み取りの操作 (スクリーンショットか貼り付け)。 */
    private void showAppGuide(@NonNull OtpImportApp app) {
        if (!host.isAlive()) return;
        Step step = new Step();
        boolean reregister = host.offersReregistration();
        // 手順の文は「下の「認証アプリに登録し直す」」を案内している (取り込めないアプリと「その他」)。そのボタンを
        // 出せない画面 (初期設定など) では、無いボタンを探させないよう、できることを書き足す。
        CharSequence guide = context.getString(app.guide);
        if (!reregister && (app.method == OtpImportApp.Method.NONE || app == OtpImportApp.OTHER)) {
            guide = guide + "\n\n" + context.getString(R.string.otp_import_reregister_unavailable);
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(app.name)
                .setMessage(guide)
                .setNegativeButton(R.string.otp_import_back, (d, w) -> {
                    step.advanced = true;
                    start();
                });
        android.widget.EditText field = null;
        com.google.android.material.button.MaterialButton reregisterButton = null;
        if (app.readsPaste()) {
            field = new android.widget.EditText(context);
            field.setHint(R.string.otp_import_paste_hint);
            // 鍵なので、候補の学習や自動修正をさせない。
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            field.setTypeface(android.graphics.Typeface.MONOSPACE);
            field.setMaxLines(4);
            android.widget.FrameLayout box = new android.widget.FrameLayout(context);
            int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
            box.setPadding(pad, 0, pad, 0);
            // 「その他」は手順で登録し直しも案内している。ボタンは 3 つまでなので、欄の下に置く。
            if (app == OtpImportApp.OTHER && reregister) {
                android.widget.LinearLayout column = new android.widget.LinearLayout(context);
                column.setOrientation(android.widget.LinearLayout.VERTICAL);
                column.addView(field);
                reregisterButton = new com.google.android.material.button.MaterialButton(context, null,
                        androidx.appcompat.R.attr.borderlessButtonStyle);
                reregisterButton.setText(R.string.otp_reregister);
                column.addView(reregisterButton);
                box.addView(column);
            } else {
                box.addView(field);
            }
            builder.setView(box);
            // 押しても閉じないよう、リスナーは表示後に付け直す (読めなかったときに欄へエラーを出す)。
            builder.setPositiveButton(R.string.otp_import_paste_button, null);
            if (app.readsQr()) builder.setNeutralButton(R.string.otp_import_pick, (d, w) -> {
                step.advanced = true;
                host.launchImagePicker();
            });
        } else if (app.readsQr()) {
            builder.setPositiveButton(R.string.otp_import_pick, (d, w) -> {
                step.advanced = true;
                host.launchImagePicker();
            });
        } else if (reregister) {
            // 取り込めないアプリは、ここから登録し直す (手順でこのボタンを案内している)。
            builder.setPositiveButton(R.string.otp_reregister, (d, w) -> {
                step.advanced = true;
                host.reregister();
            });
        }
        AlertDialog dialog = show(step, builder);
        if (reregisterButton != null) {
            reregisterButton.setOnClickListener(v -> {
                step.advanced = true;
                dialog.dismiss();
                host.reregister();
            });
        }
        if (field == null) return;
        android.widget.EditText input = field;
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<OtpAuthImport.Account> accounts = OtpAuthImport.accountsFromPasted(input.getText().toString());
            if (accounts == null) {
                input.setError(context.getString(R.string.otp_import_error_paste_unreadable));
                return;
            }
            input.setText("");
            step.advanced = true;
            dialog.dismiss();
            showCandidates(accounts);
        });
    }

    /** 読めた QR の鍵をすべて候補にし、1 件でも選んでもらう (大学と関係のない鍵で、使えている鍵を上書きしないため)。 */
    private void handleQr(@NonNull List<String> texts) {
        if (texts.isEmpty()) {
            showError(R.string.otp_import_error_no_qr);
            return;
        }
        boolean readable = false;
        List<OtpAuthImport.Account> found = new ArrayList<>();
        for (String text : texts) {
            List<OtpAuthImport.Account> a = OtpAuthImport.accounts(text);
            if (a == null) continue;
            readable = true;
            found.addAll(a);
        }
        if (!readable) {
            showError(R.string.otp_import_error_unreadable);
            return;
        }
        showCandidates(found);
    }

    /** 候補を 1 件でも選んでもらう。 */
    private void showCandidates(@NonNull List<OtpAuthImport.Account> found) {
        if (!host.isAlive()) return;
        if (found.isEmpty()) {
            showError(R.string.otp_import_error_no_totp);
            return;
        }
        List<OtpAuthImport.Account> accounts = OtpAuthImport.ordered(found, username);
        String[] labels = new String[accounts.size()];
        for (int i = 0; i < labels.length; i++) {
            OtpAuthImport.Account a = accounts.get(i);
            if (a.name.isEmpty() && a.issuer.isEmpty()) labels[i] = context.getString(R.string.otp_import_pasted_name);
            else labels[i] = a.issuer.isEmpty() ? a.name : a.name.isEmpty() ? a.issuer : a.name + " (" + a.issuer + ")";
        }
        Step step = new Step();
        show(step, new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.otp_import_choose_title)
                .setItems(labels, (d, which) -> {
                    step.advanced = true;
                    save(accounts.get(which));
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void save(@NonNull OtpAuthImport.Account account) {
        // 候補のダイアログを出している間に画面が外れていることがある。
        if (!host.isAlive()) return;
        if (username.isEmpty() || !OtpKeyImport.save(prefs, account, username)) {
            showError(R.string.otp_import_error_save_failed);
            return;
        }
        host.onSaved();
    }

    private void showError(@StringRes int message) {
        if (!host.isAlive()) return;
        show(new Step(), new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.otp_import_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null));
    }

    /** 進めずに閉じたら (どの経路でも) Host に返す。 */
    @NonNull
    private AlertDialog show(@NonNull Step step, @NonNull MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            if (showing == dialog) showing = null;
            if (!step.advanced && !silenced && host.isAlive()) host.onClosed();
        });
        showing = dialog;
        dialog.show();
        return dialog;
    }
}
