package com.shakenokirimi12.uoa_app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.AppConfig;
import com.shakenokirimi12.uoa_app.services.AppConfigService;
import com.shakenokirimi12.uoa_app.services.sync.SyncScheduler;
import com.shakenokirimi12.uoa_app.services.LocationGeofenceService;
import com.shakenokirimi12.uoa_app.services.PushNotificationService;
import com.shakenokirimi12.uoa_app.ui.onboarding.OnboardingActivity;
import com.shakenokirimi12.uoa_app.ui.util.EdgeToEdge;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PreferenceManager prefs = PreferenceManager.getInstance(this);
        if (!prefs.isOnboardingDone()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        NavController navController = navHostFragment.getNavController();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        // 下タブを設定どおりに組み、NavigationUI と結ぶ。この結線 (setupWithNavController) は
        // navController へ OnDestinationChangedListener を毎回追加する実装なので、起動時に 1 回だけ。
        // タブ構成を変えたときは refreshBottomTabs() でメニュー項目だけ差し替える (再結線しない)。
        com.shakenokirimi12.uoa_app.ui.util.MainTabs.applyTo(bottomNav, PreferenceManager.getInstance(this));
        NavigationUI.setupWithNavController(bottomNav, navController);
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        // 上バーの「戻る」はタブの根では出さない。どのタブが下に出ていても根として扱えるよう、
        // 取りうるタブの遷移先すべて (+その他) をトップレベルにしておく。こうすればタブ構成を
        // 変えても再結線が要らない。
        androidx.navigation.ui.AppBarConfiguration appBarConfiguration =
                new androidx.navigation.ui.AppBarConfiguration.Builder(
                        com.shakenokirimi12.uoa_app.ui.util.MainTabs.allRootDestinationIds()).build();
        NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);
        EdgeToEdge.apply(this, findViewById(R.id.container), bottomNav);

        // CampusSquare は 2026-09-15 以降 IdP 経由しか無い (旧フォーム消滅)。OTP メール自動取得の同意を
        // 含むチュートリアルを、この端末でまだ見せていなければリモート設定を待たずに初回起動で出す
        // (iOS の idPTutorialPresented と同じ)。回転などの再生成では出し直さない。
        if (savedInstanceState == null && !prefs.hasSeenIdPTutorial()) {
            navController.navigate(R.id.navigation_idp_tutorial);
        }

        // Register device for push notifications
        PushNotificationService pushService = PushNotificationService.shared(this);
        pushService.registerDevice(prefs.getFcmToken().isEmpty() ? null : prefs.getFcmToken());
        // FCM トークンを取り直して登録する。onNewToken は入れ替わったときしか呼ばれない。
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    if (token == null || token.equals(prefs.getFcmToken())) return;
                    prefs.setFcmToken(token);
                    pushService.registerDevice(token);
                });
        requestNotificationPermissionIfNeeded();

        // debug ビルド限定: adb から OTP 登録画面 (SSH トンネル経由) を直接開いて確認するための入口。
        //   adb shell am start -n com.shakenokirimi12.uoa_app/.MainActivity --ez open_otp_registration true
        // 本番では設定画面からの導線を持たず、ログイン時の OTP 未設定検知でしか開かない。
        if (BuildConfig.DEBUG && savedInstanceState == null
                && getIntent() != null && getIntent().getBooleanExtra("open_otp_registration", false)) {
            openOtpRegistration();
        }

        // Start geofencing if enabled
        if (prefs.isAutoAttendanceEnabled()) {
            LocationGeofenceService.startGeofencing(this, 37.5234, 139.9388, 200);
        }

        setupRemoteConfig();
        SyncScheduler.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 前面復帰のたびに取り直す。メンテナンス解除や killswitch の変更を、
        // アプリを起動し直さなくても拾えるようにする (iOS と同じタイミング)。
        AppConfigService.getInstance().refresh(this);
    }

    /** API 33 以降は許可が無いと通知が一切出ない。これまでどこでも求めていなかった。 */
    /**
     * 下タブを「タブの表示設定」どおりに組み直し、NavigationUI と結び直す。起動時と、設定画面で
     * タブを入れ替えたときに呼ぶ。トップバーの「戻る」を出さない画面 (= タブの根) も同時に更新する。
     */
    public void refreshBottomTabs() {
        // メニュー項目だけ差し替える。NavigationUI との結線は onCreate で済ませてあり、item id を
        // 遷移先 id に合わせているので、項目を入れ替えても既存のリスナーがそのまま遷移させる。
        // ここで setupWithNavController を呼ぶとリスナーが多重登録される (タブ編集のたびに増える)。
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        com.shakenokirimi12.uoa_app.ui.util.MainTabs.applyTo(bottomNav, PreferenceManager.getInstance(this));
    }

    /** ログイン中に OTP 未設定と分かったときに呼ばれる (OtpRegistrationLauncher)。既に開いていれば何もしない。 */
    public void openOtpRegistration() {
        if (isFinishing() || isDestroyed()) return;
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment == null) return;
        NavController navController = navHostFragment.getNavController();
        androidx.navigation.NavDestination current = navController.getCurrentDestination();
        if (current != null && current.getId() == R.id.navigation_idp_otp_registration) return;
        navController.navigate(R.id.navigation_idp_otp_registration);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        androidx.core.app.ActivityCompat.requestPermissions(
                this, new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
    }

    // ---- リモート設定 (メンテナンス / 強制更新 / お知らせ) ----

    private String dismissedNotice;

    private void setupRemoteConfig() {
        View overlay = findViewById(R.id.blocking_overlay);
        // 全面表示が出ている間は戻るキーで抜けさせない。
        OnBackPressedCallback swallowBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() { /* 何もしない */ }
        };
        getOnBackPressedDispatcher().addCallback(this, swallowBack);

        AppConfigService.getInstance().config().observe(this, config -> {
            applyBlockingState(config, overlay, swallowBack);
            applyStatusNotice(config);
        });
    }

    private void applyBlockingState(AppConfig config, View overlay, OnBackPressedCallback swallowBack) {
        AppConfigService svc = AppConfigService.getInstance();
        TextView title = findViewById(R.id.blocking_title);
        TextView message = findViewById(R.id.blocking_message);
        Button button = findViewById(R.id.blocking_button);

        // 強制更新を優先する。古いバージョンにメンテナンス解除後の画面を見せても意味がない。
        if (svc.isForceUpdateRequired()) {
            title.setText(R.string.force_update_title);
            String msg = config.forceUpdateMessage;
            message.setText(msg != null && !msg.isEmpty() ? msg : getString(R.string.force_update_default_message));
            button.setText(R.string.force_update_open_store);
            button.setEnabled(true);
            button.setOnClickListener(v -> {
                String url = config.forceUpdateURL;
                if (url == null || url.isEmpty()) {
                    url = "https://play.google.com/store/apps/details?id=" + getPackageName();
                }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            });
            overlay.setVisibility(View.VISIBLE);
            swallowBack.setEnabled(true);
            return;
        }

        if (svc.isFlagEnabled("maintenance_mode")) {
            title.setText(R.string.maintenance_title);
            String msg = svc.flagValue("maintenance_message");
            message.setText(msg != null && !msg.isEmpty() ? msg : getString(R.string.maintenance_default_message));
            button.setText(R.string.maintenance_recheck);
            button.setEnabled(true);
            button.setOnClickListener(v -> {
                button.setEnabled(false);
                button.setText(R.string.maintenance_rechecking);
                // 結果は LiveData 経由で applyBlockingState に戻ってくる。
                // 解除されていれば overlay が消え、まだならボタンが元に戻る。
                svc.refresh(this, ok -> {
                    button.setEnabled(true);
                    button.setText(R.string.maintenance_recheck);
                });
            });
            overlay.setVisibility(View.VISIBLE);
            swallowBack.setEnabled(true);
            return;
        }

        overlay.setVisibility(View.GONE);
        swallowBack.setEnabled(false);
    }

    private void applyStatusNotice(AppConfig config) {
        View banner = findViewById(R.id.status_notice);
        TextView text = findViewById(R.id.status_notice_text);
        String notice = AppConfigService.getInstance().flagValue("status_notice");
        // 一度閉じたら同じ文面は出さない。文面が変われば(別の告知なら)また出す。
        if (notice == null || notice.trim().isEmpty() || notice.equals(dismissedNotice)) {
            banner.setVisibility(View.GONE);
            return;
        }
        text.setText(notice);
        banner.setVisibility(View.VISIBLE);
        findViewById(R.id.status_notice_dismiss).setOnClickListener(v -> {
            dismissedNotice = notice;
            banner.setVisibility(View.GONE);
        });
    }
}
