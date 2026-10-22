package com.shakenokirimi12.uoa_app.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.services.ForceUpdateGate;
import com.shakenokirimi12.uoa_app.services.ForceUpdatePolicy;
import com.shakenokirimi12.uoa_app.ui.util.EdgeToEdge;

/**
 * 強制アップデートの全面表示。戻るキーでは閉じず、ほかの画面へも進ませない。
 * どの画面 (初期設定・アプリ内ブラウザ・通知から開いた画面) の上にも出るよう、
 * UoaApplication が Activity の再開のたびに ForceUpdateGate を見てここへ差し替える。
 * サーバーが最低バージョンを下げて止める必要が無くなったら、自分で閉じてアプリを開き直す。
 */
public class ForceUpdateActivity extends AppCompatActivity {
    private static final String TAG = "ForceUpdateActivity";

    /** 今のタスクを片付けてこの画面だけにする。戻るキーで元の画面へ戻れないように。 */
    public static void show(@NonNull Activity from) {
        Intent intent = new Intent(from, ForceUpdateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        from.startActivity(intent);
        // 別のタスク (taskAffinity="" の画面) から来た場合も、元の画面を残さない。
        from.finish();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.view_blocking_overlay);
        View root = findViewById(R.id.blocking_overlay);
        root.setVisibility(View.VISIBLE);
        EdgeToEdge.apply(this, root, null);

        TextView title = findViewById(R.id.blocking_title);
        TextView message = findViewById(R.id.blocking_message);
        Button button = findViewById(R.id.blocking_button);

        ForceUpdateGate gate = ForceUpdateGate.getInstance(this);
        title.setText(R.string.force_update_title);
        String msg = gate.message();
        message.setText(msg != null && !msg.trim().isEmpty() ? msg : getString(R.string.force_update_default_message));
        button.setText(R.string.force_update_open_store);
        button.setOnClickListener(v -> openStore(gate));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { /* 閉じさせない */ }
        });

        // 止める必要が無くなった (サーバーが最低バージョンを下げた) ら、アプリを開き直す。
        // LiveData の値ではなく今の判定を見る (postValue が届く前の古い値で開き直さないように)。
        gate.blocked().observe(this, blocked -> {
            if (!gate.isBlocked()) {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
            }
        });
    }

    private void openStore(@NonNull ForceUpdateGate gate) {
        String url = gate.storeUrl(getPackageName());
        if (tryOpen(url)) return;
        // market:// を開けるアプリが無い端末など。ブラウザで開ける Google Play のページへ。
        String fallback = ForceUpdatePolicy.playStoreUrl(getPackageName());
        if (!fallback.equals(url) && tryOpen(fallback)) return;
        Log.w(TAG, "No activity can open the store URL");
    }

    private boolean tryOpen(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
