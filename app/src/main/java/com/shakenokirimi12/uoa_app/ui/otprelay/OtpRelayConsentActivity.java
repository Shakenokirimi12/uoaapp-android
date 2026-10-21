package com.shakenokirimi12.uoa_app.ui.otprelay;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 断った依頼の通知 (同意を記録する前に作った連携) を押したときに開く、確認ダイアログだけの透明な画面。
 * 外部から開かせないので非公開 (manifest)。
 */
public class OtpRelayConsentActivity extends AppCompatActivity {
    @NonNull
    public static Intent intent(@NonNull Context ctx) {
        return new Intent(ctx, OtpRelayConsentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ダイアログは作り直しで復元されない。作り直されたときは、空の透明画面を残さず閉じる。
        if (savedInstanceState != null) {
            finish();
            return;
        }
        // 別の画面で確認を出している途中なら、そちらに任せて閉じる。
        if (!OtpRelayConsentPrompt.show(this, this::finish)) finish();
    }
}
