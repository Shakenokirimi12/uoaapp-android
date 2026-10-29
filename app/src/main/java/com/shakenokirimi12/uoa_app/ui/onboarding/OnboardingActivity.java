package com.shakenokirimi12.uoa_app.ui.onboarding;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
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

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private MaterialButton buttonNext;
    private PreferenceManager prefs;
    private final MoodleService moodleService = new MoodleService();

    private static final int PAGE_WELCOME = 0;
    private static final int PAGE_LOGIN = 1;
    /** PC との連携 (任意)。ログインが済んでから出す。方式にかかわらず出す (decisions D-13、iOS と同じ並び)。 */
    private static final int PAGE_PC_RELAY = 2;
    private static final int TOTAL_PAGES = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        // 背景色を持つルート自体に当てる。content フレームに当てるとバーの裏が白く抜ける。
        // 背景はテーマの surface なので、バーのアイコン色はライト/ダークに追従させる。
        EdgeToEdge.apply(this, findViewById(R.id.onboarding_root), null);

        prefs = PreferenceManager.getInstance(this);
        viewPager = findViewById(R.id.view_pager);
        buttonNext = findViewById(R.id.button_next);

        viewPager.setAdapter(new OnboardingPagerAdapter());
        viewPager.setUserInputEnabled(false);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position == PAGE_LOGIN) {
                    buttonNext.setText(R.string.onboarding_login_button);
                } else if (position == PAGE_PC_RELAY) {
                    buttonNext.setText(R.string.onboarding_pc_relay_later);
                } else {
                    buttonNext.setText(R.string.onboarding_agree);
                }
            }
        });

        buttonNext.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (current == PAGE_WELCOME) {
                viewPager.setCurrentItem(PAGE_LOGIN);
            } else if (current == PAGE_LOGIN) {
                attemptLogin();
            } else {
                finishOnboarding(false);
            }
        });
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

        moodleService.login(username, password, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                prefs.setUsername(username);
                prefs.setPassword(password);
                // ログインは済んだ。連携の段で閉じられても、次の起動でログインからやり直させない。
                prefs.setOnboardingDone(true);
                buttonNext.setEnabled(true);
                viewPager.setCurrentItem(PAGE_PC_RELAY);
            }

            @Override
            public void onError(String message) {
                buttonNext.setEnabled(true);
                buttonNext.setText(R.string.onboarding_login_button);
                Toast.makeText(OnboardingActivity.this, message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 初期設定を終えてアプリへ。openPcRelay なら「PC との連携」の画面を開く。 */
    private void finishOnboarding(boolean openPcRelay) {
        prefs.setOnboardingDone(true);
        Intent intent = new Intent(this, MainActivity.class);
        if (openPcRelay) intent.putExtra(MainActivity.EXTRA_OPEN_OTP_RELAY, true);
        startActivity(intent);
        finish();
    }

    private class OnboardingPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == PAGE_WELCOME) {
                View view = inflater.inflate(R.layout.page_onboarding_welcome, parent, false);
                return new RecyclerView.ViewHolder(view) {};
            } else if (viewType == PAGE_PC_RELAY) {
                View view = inflater.inflate(R.layout.page_onboarding_pc_relay, parent, false);
                view.findViewById(R.id.button_onboarding_pc_relay).setOnClickListener(v -> finishOnboarding(true));
                return new RecyclerView.ViewHolder(view) {};
            } else {
                View view = inflater.inflate(R.layout.page_onboarding_login, parent, false);
                view.setTag("page_login");
                return new RecyclerView.ViewHolder(view) {};
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}

        @Override
        public int getItemCount() {
            return TOTAL_PAGES;
        }
    }
}
