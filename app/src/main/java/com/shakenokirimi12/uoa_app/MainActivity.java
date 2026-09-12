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
        NavigationUI.setupWithNavController(bottomNav, navController);
        // The top app bar follows the destination: tab labels on the four roots, a back
        // arrow plus the nav_graph label everywhere else. Screens used to have no title at all.
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        androidx.navigation.ui.AppBarConfiguration appBarConfiguration =
                new androidx.navigation.ui.AppBarConfiguration.Builder(bottomNav.getMenu()).build();
        NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);
        EdgeToEdge.apply(this, findViewById(R.id.container), bottomNav);

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

        // Start geofencing if enabled
        if (prefs.isAutoAttendanceEnabled()) {
            LocationGeofenceService.startGeofencing(this, 37.5234, 139.9388, 200);
        }

        setupRemoteConfig();
        SyncScheduler.schedule(this);
        // 自動出席のデバッグテスト中にプロセスが死んでいたら、その後始末
        com.shakenokirimi12.uoa_app.ui.debug.AutoAttendanceDebugRunner.cleanupIfStale(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 前面復帰のたびに取り直す。メンテナンス解除や killswitch の変更を、
        // アプリを起動し直さなくても拾えるようにする (iOS と同じタイミング)。
        AppConfigService.getInstance().refresh(this);
    }

    /** API 33 以降は許可が無いと通知が一切出ない。これまでどこでも求めていなかった。 */
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
