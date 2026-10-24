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
import com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairingService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PC 中継の確認ダイアログだけを出す透明な画面。
 * - 連携: 標準のカメラ / Google レンズで QR を読むと uoa-app://otp-pair#... で開かれる。設定画面の
 *   「リンクを貼り付けて連携」もここへ来る。確認してから中継サーバへ登録する。
 * 文面は iOS (AuthPresentations の OTPRelayAlertsModifier) と同じ。依頼ごとの承認は無い (2026-10-21 に廃止)。
 */
public class OtpRelayActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 連携用のリンクを渡して開く (設定画面の貼り付けから)。 */
    @NonNull
    public static Intent pairIntent(@NonNull Context ctx, @NonNull String link) {
        return new Intent(ctx, OtpRelayActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(link));
    }

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
                // 「連携する」がこの連携でメールを読み、コードを作って送ることへの同意になる (OtpRelayPairing.mailConsentAt)。
                // 何を読んでどこへ返すかを、ここで明示する。
                .setMessage("「" + pending.browserName + "」で OTP の入力画面が出ると、このアプリが OTP を用意し、"
                        + "暗号化してその PC に自動で送ります。メール方式なら大学のメールから読み取り (確認コードのメールは、"
                        + "古いものも含めて削除します)、認証アプリ方式ならこのアプリにあるセットアップキーでコードを作ります。"
                        + "ユーザー名・パスワード・セットアップキーは送りません。"
                        + "「同意して連携する」を押すと、そのためにメールを読み、コードを作って送ることに同意したことになります。\n\n"
                        + "自分の PC の拡張で出した QR を読み取ったのでなければ、「キャンセル」を押してください。")
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
                text = "「" + pending.browserName + "」と連携しました。PC で OTP の入力画面が出ると、この端末が OTP を"
                        + "自動で送ります。通信状況や通知の設定によっては、送れないことがあります。";
            } catch (OtpRelayPairingService.PairingException e) {
                text = e.getMessage();
            }
            String finalText = text;
            main.post(() -> {
                if (!isFinishing() && !isDestroyed()) message(title, finalText);
            });
        });
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
