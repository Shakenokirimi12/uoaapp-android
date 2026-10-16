package com.shakenokirimi12.uoa_app.ui.otprelay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayLink;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairing;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairingService;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayRequest;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayStore;
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayWorker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PC 中継の確認ダイアログだけを出す透明な画面。
 * - 連携: 標準のカメラ / Google レンズで QR を読むと uoa-app://otp-pair#... で開かれる。設定画面の
 *   「リンクを貼り付けて連携」もここへ来る。確認してから中継サーバへ登録する。
 * - 依頼: 承認通知の本体を押したとき。通知のボタンが出ない端末でも承認・拒否できるように。
 * 文面は iOS (AuthPresentations の OTPRelayAlertsModifier) と同じ。
 */
public class OtpRelayActivity extends AppCompatActivity {
    private static final String EXTRA_MODE = "otp_relay_mode";
    private static final String MODE_REQUEST = "request";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 連携用のリンクを渡して開く (設定画面の貼り付けから)。 */
    @NonNull
    public static Intent pairIntent(@NonNull Context ctx, @NonNull String link) {
        return new Intent(ctx, OtpRelayActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(link));
    }

    /** 承認通知の本体から。 */
    @NonNull
    public static Intent requestIntent(@NonNull Context ctx, @NonNull OtpRelayRequest request) {
        Intent intent = new Intent(ctx, OtpRelayRequestActivity.class).putExtra(EXTRA_MODE, MODE_REQUEST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        OtpRelayWorker.putRequestExtras(intent, request);
        return intent;
    }

    /** 依頼の承認を受け付けるか。公開している (QR 用の) この画面では受け付けない。 */
    boolean acceptsRequests() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ダイアログは作り直しで復元されない。構成変更は manifest の configChanges で作り直さないように
        // してあるが、それでも作り直された (プロセスの再生成など) ときは、空の透明画面を残さず閉じる。
        if (savedInstanceState != null) {
            finish();
            return;
        }
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void handle(@Nullable Intent intent) {
        if (intent == null) { finish(); return; }
        if (MODE_REQUEST.equals(intent.getStringExtra(EXTRA_MODE))) {
            if (acceptsRequests()) showRequest(OtpRelayWorker.getRequest(intent));
            else finish();
            return;
        }
        Uri data = intent.getData();
        OtpRelayLink.Pending pending = OtpRelayLink.parse(data != null ? data.toString() : null);
        if (pending == null) {
            message("PC との連携", "連携用の QR を読み取れませんでした。PC 側で QR を出し直してください。");
            return;
        }
        confirmPairing(pending);
    }

    private void confirmPairing(OtpRelayLink.Pending pending) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("PC と連携しますか？")
                // 「連携する」がこの連携でメールを読むことへの同意になる (OtpRelayPairing.mailConsentAt)。
                // 何を読んでどこへ返すかを、ここで明示する。
                .setMessage("連携すると、「" + pending.browserName + "」で OTP の画面が出るたびに、"
                        + "このアプリが大学のメールを読んで OTP を取り出し、暗号化してその PC に返します。"
                        + "ID とパスワードは送りません。\n\n"
                        + "依頼のたびに通知で承認を求めます (毎回承認)。承認なしで自動で返すには、"
                        + "設定の「PC との連携 (OTP)」で連携ごとに切り替えてください。\n\n"
                        + "自分の PC の拡張で出した QR のときだけ連携してください。")
                .setPositiveButton("同意して連携する", (d, w) -> pair(pending))
                .setNegativeButton("キャンセル", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void pair(OtpRelayLink.Pending pending) {
        Context app = getApplicationContext();
        executor.execute(() -> {
            String title = "PC との連携";
            String text;
            try {
                OtpRelayPairingService.pair(app, pending);
                text = "「" + pending.browserName + "」と連携しました。PC で OTP の画面が出ると、この端末に通知が届きます。";
            } catch (OtpRelayPairingService.PairingException e) {
                text = e.getMessage();
            }
            String finalText = text;
            main.post(() -> {
                if (!isFinishing() && !isDestroyed()) message(title, finalText);
            });
        });
    }

    private void showRequest(@Nullable OtpRelayRequest request) {
        OtpRelayPairing pairing = request != null ? OtpRelayStore.find(request.pairingId) : null;
        if (request == null || pairing == null) { finish(); return; }
        if (request.expiresAt <= System.currentTimeMillis()) {
            message("PC からの OTP 依頼", "期限が切れました。PC でもう一度ログインしてください。");
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("PC からの OTP 依頼")
                .setMessage("「" + pairing.browserName + "」が OTP を求めています。心当たりが無ければ拒否してください。")
                .setPositiveButton("承認して送る", (d, w) -> {
                    OtpRelayWorker.approveFromUi(getApplicationContext(), request);
                    finish();
                })
                .setNegativeButton("拒否", (d, w) -> {
                    OtpRelayWorker.rejectFromUi(getApplicationContext(), request);
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void message(String title, String text) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("OK", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }
}
