package com.shakenokirimi12.uoa_app;

import android.content.Intent;
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
import com.shakenokirimi12.uoa_app.services.ForceUpdateGate;
import com.shakenokirimi12.uoa_app.ui.ForceUpdateActivity;
import com.shakenokirimi12.uoa_app.services.sync.SyncScheduler;
import com.shakenokirimi12.uoa_app.services.LocationGeofenceService;
import com.shakenokirimi12.uoa_app.services.PushNotificationService;
import com.shakenokirimi12.uoa_app.ui.onboarding.OnboardingActivity;
import com.shakenokirimi12.uoa_app.ui.util.EdgeToEdge;

public class MainActivity extends AppCompatActivity {
    /** 時間割共有の受け取り画面 (TimetableIncomingActivity) の「保存した時間割を見る」。値は友達の ID。 */
    public static final String EXTRA_OPEN_TIMETABLE_FRIEND = "open_timetable_friend";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 強制アップデートの対象なら、端末登録や同期の予約を始める前に全面表示へ差し替える。
        if (ForceUpdateGate.getInstance(this).isBlocked()) {
            ForceUpdateActivity.show(this);
            return;
        }

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
        // 選んでいるタブをもう一度押したら、そのタブの最初の画面へ戻す。リスナーが無いと NavigationUI が
        // 同じタブへ遷移し直すだけで、「その他」から開いた画面 (成績など) から「その他」へ戻れなかった。
        bottomNav.setOnItemReselectedListener(item -> {
            if (navigationLocked) return;
            navController.popBackStack(item.getItemId(), false);
        });
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        // 上バーの「戻る」はタブの根では出さない。どのタブが下に出ていても根として扱えるよう、
        // 取りうるタブの遷移先すべて (+その他) をトップレベルにしておく。こうすればタブ構成を
        // 変えても再結線が要らない。
        androidx.navigation.ui.AppBarConfiguration appBarConfiguration =
                new androidx.navigation.ui.AppBarConfiguration.Builder(
                        com.shakenokirimi12.uoa_app.ui.util.MainTabs.allRootDestinationIds()).build();
        NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);
        // ただし下タブに無い画面 (「その他」から開いた成績など) は、根でも「戻る」を出す。出さないと
        // 「その他」へ戻る手段が下タブの再選択しか無い。NavigationUI の後に登録して、そちらが外した
        // 矢印を付け直す。押したときは NavigationUI の navigateUp が前の画面 (その他) へ戻す。
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            if (navigationLocked || controller.getPreviousBackStackEntry() == null) return;
            if (!com.shakenokirimi12.uoa_app.ui.util.MainTabs.isHiddenTabRoot(
                    destination.getId(), PreferenceManager.getInstance(this))) return;
            androidx.appcompat.graphics.drawable.DrawerArrowDrawable arrow =
                    new androidx.appcompat.graphics.drawable.DrawerArrowDrawable(toolbar.getContext());
            arrow.setProgress(1f);
            toolbar.setNavigationIcon(arrow);
            toolbar.setNavigationContentDescription(androidx.navigation.ui.R.string.nav_app_bar_navigate_up_description);
        });
        EdgeToEdge.apply(this, findViewById(R.id.container), bottomNav);

        // CampusSquare は 2026-09-15 以降 IdP 経由しか無い (旧フォーム消滅)。ワンタイムパスワードの
        // 受け取り方の説明を含むチュートリアルを、この端末でまだ見せていなければリモート設定を待たずに初回起動で出す
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
        // 本番で新規登録として開くのは、ログイン時の OTP 未設定検知だけ (設定画面からは方式の切り替えとして開く)。
        if (BuildConfig.DEBUG && savedInstanceState == null
                && getIntent() != null && getIntent().getBooleanExtra("open_otp_registration", false)) {
            openOtpRegistration();
        }

        if (savedInstanceState == null) openTimetableFriend(getIntent());

        // Start geofencing if enabled
        LocationGeofenceService.startIfEnabled(this);

        setupRemoteConfig();
        SyncScheduler.schedule(this);
        com.shakenokirimi12.uoa_app.services.notification.LunchMenuWorker.ensureScheduled(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // リモート設定の取り直しは、アプリが前面に出るたびに UoaApplication が行う (どの画面から戻っても)。
        // 同意を記録する前に作った PC との連携があれば、起動して最初に前面に出たときに 1 回だけ確かめる。
        com.shakenokirimi12.uoa_app.ui.otprelay.OtpRelayConsentPrompt.showAtLaunch(this);
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

    private boolean navigationLocked;
    @androidx.annotation.Nullable private android.graphics.drawable.Drawable lockedNavigationIcon;

    /**
     * 上バーの「戻る」と下タブで画面を離れられないようにする / 戻す。OTP の登録の POST を送ったあと
     * (IdPOtpRegistrationFragment) に使う。システムの戻る操作は画面側の OnBackPressedCallback が止める。
     * 上バーの「戻る」と下タブは NavigationUI が直接 navigate するので、ここで塞ぐ。
     */
    public void setNavigationLocked(boolean locked) {
        if (locked == navigationLocked) return;
        navigationLocked = locked;
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        android.view.Menu menu = bottomNav.getMenu();
        for (int i = 0; i < menu.size(); i++) menu.getItem(i).setEnabled(!locked);
        if (locked) {
            lockedNavigationIcon = toolbar.getNavigationIcon();
            toolbar.setNavigationIcon(null);
        } else {
            // 塞いでいる間に遷移は起きないが、NavigationUI が付け直していたらそちらを優先する。
            if (toolbar.getNavigationIcon() == null && lockedNavigationIcon != null) {
                toolbar.setNavigationIcon(lockedNavigationIcon);
            }
            lockedNavigationIcon = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openTimetableFriend(intent);
    }

    /** 受け取った友達の時間割を開く。戻ると友達の一覧 (時間割共有) に戻る。手元に無い ID なら何もしない。 */
    private void openTimetableFriend(@androidx.annotation.Nullable Intent intent) {
        String id = intent != null ? intent.getStringExtra(EXTRA_OPEN_TIMETABLE_FRIEND) : null;
        if (id == null || navigationLocked || isFinishing() || isDestroyed()) return;
        intent.removeExtra(EXTRA_OPEN_TIMETABLE_FRIEND);
        if (com.shakenokirimi12.uoa_app.data.timetable.TimetableShareStore.getInstance(this).friend(id) == null) return;
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment == null) return;
        NavController navController = navHostFragment.getNavController();
        navController.navigate(R.id.navigation_timetable_friends);
        Bundle args = new Bundle();
        args.putString(com.shakenokirimi12.uoa_app.ui.timetable.FriendTimetableFragment.ARG_FRIEND_ID, id);
        navController.navigate(R.id.navigation_timetable_friend, args);
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
            applyBlockingState(overlay, swallowBack);
            applyStatusNotice(config);
        });
    }

    private void applyBlockingState(View overlay, OnBackPressedCallback swallowBack) {
        AppConfigService svc = AppConfigService.getInstance();
        TextView title = findViewById(R.id.blocking_title);
        TextView message = findViewById(R.id.blocking_message);
        Button button = findViewById(R.id.blocking_button);

        // 強制更新は ForceUpdateActivity が全画面で出す (UoaApplication がどの画面の上にも出す)。
        // ここではメンテナンスだけを扱う。
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
