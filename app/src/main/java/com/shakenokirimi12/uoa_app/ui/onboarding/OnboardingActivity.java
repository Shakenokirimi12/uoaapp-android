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
    private static final int TOTAL_PAGES = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

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
                } else {
                    buttonNext.setText(R.string.onboarding_agree);
                }
            }
        });

        buttonNext.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (current < TOTAL_PAGES - 1) {
                viewPager.setCurrentItem(current + 1);
            } else {
                attemptLogin();
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

        buttonNext.setEnabled(false);
        buttonNext.setText("ログイン中…");

        moodleService.login(username, password, new ServiceCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                prefs.setUsername(username);
                prefs.setPassword(password);
                prefs.setOnboardingDone(true);

                startActivity(new Intent(OnboardingActivity.this, MainActivity.class));
                finish();
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
