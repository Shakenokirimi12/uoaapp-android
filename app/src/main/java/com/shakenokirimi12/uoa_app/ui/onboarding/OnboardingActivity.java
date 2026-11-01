package com.shakenokirimi12.uoa_app.ui.onboarding;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.shakenokirimi12.uoa_app.ui.util.EdgeToEdge;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.otprelay.PcRelaySetupPages;

public class OnboardingActivity extends AppCompatActivity
        implements com.shakenokirimi12.uoa_app.ui.idp.OtpReregistrationHost {

    private ViewPager2 viewPager;
    private MaterialButton buttonNext;
    private MaterialButton buttonSkipPc;
    private LinearLayout dots;
    private PreferenceManager prefs;
    private final MoodleService moodleService = new MoodleService();
    private final OnboardingPagerAdapter adapter = new OnboardingPagerAdapter();

    private static final int PAGE_WELCOME = 0;
    private static final int PAGE_LOGIN = 1;
    private static final int LOGIN_STEPS = 2;
    /**
     * 同意とログインの段のあとに、PC との連携の案内 (PcRelaySetupPages の 4 ページ、任意) を段として並べる。
     * 方式にかかわらず出す (decisions D-13、iOS と同じ並び)。
     */
    private static final int TOTAL_STEPS = LOGIN_STEPS + PcRelaySetupPages.COUNT;
    private static final String STATE_PC_PHASE = "pc_phase";
    private static final String STATE_PC_PAGE = "pc_page";
    private static final String EXTRA_CATCH_UP = "catch_up";

    /**
     * 初期設定を終えた人に、アップデートで増えた段だけを出す (OnboardingProgress)。今それだけで出せる段は PC との
     * 連携なので、その 4 ページだけを出す。閉じたらアプリ (下にある MainActivity) へ戻る。
     */
    @NonNull
    public static Intent catchUp(@NonNull Context context) {
        return new Intent(context, OnboardingActivity.class).putExtra(EXTRA_CATCH_UP, true);
    }

    /** 増えた段だけを出しているか。完了しても MainActivity を開き直さず、閉じるだけ。 */
    private boolean catchUp;

    /**
     * 今いる画面。ログインの途中で回転すると、結果は作り直す前の画面に返る。そのときは今の画面を連携の段へ進める
     * (同意の段に戻ってもう一度ログインさせ、パスワードを送り直さないように)。
     */
    @Nullable private static java.lang.ref.WeakReference<OnboardingActivity> current;
    /** ログインの結果を待っているか。作り直した画面でもう一度ログインを押させない。 */
    private static boolean loginInFlight;
    /**
     * 確かめている最中の ID / パスワード。ログインが済むまで保存しないので、その間に選択から登録し直す
     * (IdPOtpRegistrationActivity) ときはここから読む。ログインが済むか、初期設定を閉じたら消す。
     */
    @Nullable private static String[] pendingCredentials;

    /** 登録し直しが済んだら、同じ ID / パスワードでログインし直す (iOS も登録のあとで同期し直す)。 */
    private final androidx.activity.result.ActivityResultLauncher<Intent> reregistration = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            result -> {
                reregistrationOpen = false;
                // 登録の画面を開いている間に回転すると、結果は作り直した画面に、ページの View が付く前に届く。
                // そのまま呼ぶとログインの段が見つからず何もしないので、View が付いてから呼ぶ。
                if (result.getResultCode() == RESULT_OK) viewPager.post(this::retryLogin);
            });
    /**
     * 選択から登録し直しを開いた。そのログインは取り消しで終わるが、自分で選んだ操作なので失敗のトーストは出さない
     * (開くのは取り消しより先に main に積まれる。OtpPromptCoordinator)。
     */
    private boolean reregistrationOpen;

    /** 確かめている最中の ID / パスワード ([ID, パスワード])。無ければ null。 */
    @Nullable
    public static String[] pendingCredentials() {
        String[] pending = pendingCredentials;
        return pending != null ? pending.clone() : null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        // 背景色を持つルート自体に当てる。content フレームに当てるとバーの裏が白く抜ける。
        // 背景はテーマの surface なので、バーのアイコン色はライト/ダークに追従させる。
        EdgeToEdge.apply(this, findViewById(R.id.onboarding_root), null);

        prefs = PreferenceManager.getInstance(this);
        catchUp = getIntent().getBooleanExtra(EXTRA_CATCH_UP, false);
        viewPager = findViewById(R.id.view_pager);
        buttonNext = findViewById(R.id.button_next);
        buttonSkipPc = findViewById(R.id.button_skip_pc);
        dots = findViewById(R.id.dots);

        viewPager.setAdapter(adapter);
        // 同意とログインはボタンでしか進めない (スワイプで飛ばさせない)。PC との連携の段に入ったら送れるようにする。
        viewPager.setUserInputEnabled(false);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateControls(position);
            }
        });
        updateControls(PAGE_WELCOME);

        buttonNext.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (adapter.pcPhase) {
                if (current + 1 < PcRelaySetupPages.COUNT) {
                    viewPager.setCurrentItem(current + 1);
                } else {
                    finishOnboarding();
                }
            } else if (current == PAGE_WELCOME) {
                viewPager.setCurrentItem(PAGE_LOGIN);
            } else {
                attemptLogin();
            }
        });
        buttonSkipPc.setOnClickListener(v -> finishOnboarding());
        // 回転などで作り直されたら、ログインが済んでいれば PC との連携の段から続ける (ログインをやり直させない)。
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_PC_PHASE, false)) {
            showPcPages(savedInstanceState.getInt(STATE_PC_PAGE, 0));
        } else if (catchUp) {
            showPcPages(0);
        }
        current = new java.lang.ref.WeakReference<>(this);
        if (loginInFlight && !adapter.pcPhase) {
            buttonNext.setEnabled(false);
            buttonNext.setText("ログイン中…");
        }
    }

    @Override
    protected void onDestroy() {
        if (current != null && current.get() == this) current = null;
        // 回転での作り直しでは残す (登録し直しの画面や、作り直した画面が使う)。
        if (isFinishing() && !isChangingConfigurations()) pendingCredentials = null;
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PC_PHASE, adapter.pcPhase);
        outState.putInt(STATE_PC_PAGE, viewPager.getCurrentItem());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 連携は別の画面 (OtpRelayActivity) で増えるので、戻ってくるたびに最後のページの「連携しました」を直す。
        if (adapter.pcPhase) adapter.notifyItemChanged(PcRelaySetupPages.SCAN);
    }

    /** ボタンの文言・「スキップして始める」・段の印を、今の段に合わせる。 */
    private void updateControls(int position) {
        if (adapter.pcPhase) {
            boolean last = position + 1 >= PcRelaySetupPages.COUNT;
            buttonNext.setText(last ? R.string.onboarding_start : R.string.next);
            buttonSkipPc.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
            // 増えた段だけのときは、同意とログインの段を数えない。
            PcRelaySetupPages.renderDots(dots, catchUp ? PcRelaySetupPages.COUNT : TOTAL_STEPS,
                    (catchUp ? 0 : LOGIN_STEPS) + position);
        } else {
            buttonNext.setText(position == PAGE_LOGIN ? R.string.onboarding_login_button : R.string.onboarding_agree);
            buttonSkipPc.setVisibility(View.GONE);
            PcRelaySetupPages.renderDots(dots, TOTAL_STEPS, position);
        }
    }

    /**
     * ログインが済んだら、PC との連携の段だけにする。同意とログインの段は外す (スワイプで戻ってログインし直し、
     * もう一度パスワードを送ってしまわないように)。
     */
    private void showPcPages(int page) {
        adapter.pcPhase = true;
        adapter.notifyDataSetChanged();
        // 出した段は見たことにする。途中で閉じても、次の起動で増えた段としてまた出さない (iOS と同じ)。
        OnboardingProgress.markSeen(prefs, OnboardingProgress.PC_RELAY);
        int start = Math.max(0, Math.min(page, PcRelaySetupPages.COUNT - 1));
        viewPager.setCurrentItem(start, false);
        viewPager.setUserInputEnabled(true);
        // 回転で戻したページで onPageSelected が呼ばれなくても、ボタンと印を合わせる。
        updateControls(start);
    }

    // ---- OtpReregistrationHost (ログインで鍵が無いときの選択から) ----

    /** 登録し直しはパスワードでのログインから始まる。OTP の画面まで来たなら、確かめている最中のパスワードは通っている。 */
    @Override
    public boolean canStartReregistration() {
        return pendingCredentials != null;
    }

    @Override
    public void openOtpReregistration() {
        if (isFinishing() || isDestroyed() || pendingCredentials == null) return;
        reregistrationOpen = true;
        reregistration.launch(com.shakenokirimi12.uoa_app.ui.idp.IdPOtpRegistrationActivity.reregistration(this));
    }

    /**
     * 確かめているアカウントが OTP 未設定だった (OtpRegistrationLauncher)。登録の画面を上に開き、済んだら同じ
     * ID / パスワードでログインし直す。そのログインは未設定で失敗に終わるが、トーストは出さない (登録し直しと同じ)。
     */
    public void openOtpRegistration() {
        if (isFinishing() || isDestroyed() || pendingCredentials == null || reregistrationOpen) return;
        reregistrationOpen = true;
        reregistration.launch(com.shakenokirimi12.uoa_app.ui.idp.IdPOtpRegistrationActivity.registration(this));
    }

    @Override
    public void syncAfterKeyImport() {
        retryLogin();
    }

    /** ログインの段にいて、ログイン中でなければ、入力されている ID / パスワードでもう一度ログインする。 */
    private void retryLogin() {
        if (isFinishing() || isDestroyed() || loginInFlight || adapter.pcPhase) return;
        attemptLogin();
    }

    private void attemptLogin() {
        View loginPage = viewPager.findViewWithTag("page_login");
        if (loginPage == null) return;

        EditText editUsername = loginPage.findViewById(R.id.edit_username);
        EditText editPassword = loginPage.findViewById(R.id.edit_password);

        String username = editUsername.getText().toString().trim();
        String password = editPassword.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "ユーザー名とパスワードを入力してください",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 暗号化して保存できない端末では、ログインに成功しても資格情報を保存できない。
        // 平文には落とさないので、先に止めて理由を伝える。
        if (!prefs.isSecureStorageAvailable()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.secure_storage_unavailable_title)
                    .setMessage(R.string.secure_storage_unavailable_message)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }

        buttonNext.setEnabled(false);
        buttonNext.setText("ログイン中…");
        loginInFlight = true;

        // 前に別のアカウントを保存していたら、確認のログインの前にそのセッションを捨てる。後で捨てると、
        // このログインで得たセッションまで消える (AccountSwitch)。
        com.shakenokirimi12.uoa_app.services.AccountSwitch.prepare(prefs, username);
        pendingCredentials = new String[]{username, password};
        moodleService.login(username, password, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                pendingCredentials = null;
                if (prefs.setUsername(username)) {
                    com.shakenokirimi12.uoa_app.services.AccountSwitch.committed(OnboardingActivity.this, prefs, username);
                }
                prefs.setPassword(password);
                // ログインは済んだ。連携の段で閉じられても、次の起動でログインからやり直させない。
                prefs.setOnboardingDone(true);
                OnboardingProgress.markSeen(prefs, OnboardingProgress.DISCLAIMER, OnboardingProgress.ACCOUNT);
                loginInFlight = false;
                OnboardingActivity live = current != null ? current.get() : null;
                if (live == null || live.isFinishing()) return;
                live.buttonNext.setEnabled(true);
                if (!live.adapter.pcPhase) live.showPcPages(0);
            }

            @Override
            public void onError(String message) {
                loginInFlight = false;
                OnboardingActivity live = current != null ? current.get() : null;
                if (live == null || live.isFinishing()) return;
                live.buttonNext.setEnabled(true);
                live.updateControls(live.viewPager.getCurrentItem());
                if (!live.reregistrationOpen) Toast.makeText(live, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 初期設定を終えてアプリへ。 */
    private void finishOnboarding() {
        if (catchUp) {
            finish();
            return;
        }
        prefs.setOnboardingDone(true);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private static class OnboardingPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        /** PC との連携の段の type。同意・ログインの段 (0, 1) と重ならないようにずらす。 */
        private static final int TYPE_PC_BASE = 100;

        /** ログインが済み、PC との連携の段 (PcRelaySetupPages) だけを出しているか。 */
        boolean pcPhase = false;

        @Override
        public int getItemViewType(int position) {
            return pcPhase ? TYPE_PC_BASE + position : position;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType >= TYPE_PC_BASE) {
                int page = viewType - TYPE_PC_BASE;
                View view = PcRelaySetupPages.createPage(parent, page, page == PcRelaySetupPages.INTRO);
                return new RecyclerView.ViewHolder(view) {};
            } else if (viewType == PAGE_WELCOME) {
                View view = inflater.inflate(R.layout.page_onboarding_welcome, parent, false);
                return new RecyclerView.ViewHolder(view) {};
            } else {
                View view = inflater.inflate(R.layout.page_onboarding_login, parent, false);
                view.setTag("page_login");
                return new RecyclerView.ViewHolder(view) {};
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (pcPhase) PcRelaySetupPages.bindPage(holder.itemView, position);
        }

        @Override
        public int getItemCount() {
            return pcPhase ? PcRelaySetupPages.COUNT : LOGIN_STEPS;
        }
    }
}
