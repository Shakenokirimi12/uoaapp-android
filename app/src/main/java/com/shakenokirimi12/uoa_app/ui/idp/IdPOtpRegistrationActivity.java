package com.shakenokirimi12.uoa_app.ui.idp;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import com.shakenokirimi12.uoa_app.ui.util.EdgeToEdge;

/**
 * 初期設定のログインで鍵が無いと分かり、選択で「認証アプリに登録し直す」を選んだときの登録し直しと、
 * 初期設定のログインで OTP 未設定と分かったときの登録 (OtpRegistrationLauncher)。
 * 登録の画面 (IdPOtpRegistrationFragment) はメイン画面の nav graph の中にあるので、初期設定からはこの画面に載せて開く。
 * ID / パスワードはまだ保存していないので、初期設定が確かめている最中の値 (OnboardingActivity.pendingCredentials) を使う。
 * Intent や Bundle にパスワードを載せない。登録が済んだら RESULT_OK を返し、初期設定がログインし直す。
 */
public class IdPOtpRegistrationActivity extends AppCompatActivity {
    private static final String EXTRA_NEW_REGISTRATION = "new_registration";

    @NonNull
    public static Intent reregistration(@NonNull Context context) {
        return new Intent(context, IdPOtpRegistrationActivity.class);
    }

    /** OTP 未設定のアカウントの登録。方式はこの画面で選ぶ (設定のメイン画面から開く登録と同じ)。 */
    @NonNull
    public static Intent registration(@NonNull Context context) {
        return reregistration(context).putExtra(EXTRA_NEW_REGISTRATION, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_idp_otp_registration);
        EdgeToEdge.apply(this, findViewById(R.id.idp_registration_container), null);
        if (savedInstanceState != null) return;
        Bundle args = new Bundle();
        // 切り替え先を渡さなければ新規登録 (IdPOtpRegistrationFragment.ARG_SWITCHING_TO)。
        if (!getIntent().getBooleanExtra(EXTRA_NEW_REGISTRATION, false)) {
            args.putString(IdPOtpRegistrationFragment.ARG_SWITCHING_TO, OtpMethod.AUTHENTICATOR.value);
        }
        args.putBoolean(IdPOtpRegistrationFragment.ARG_ONBOARDING_CREDENTIALS, true);
        IdPOtpRegistrationFragment fragment = new IdPOtpRegistrationFragment();
        fragment.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.idp_registration_container, fragment)
                .commit();
    }

    /** 登録が済んだ (IdPOtpRegistrationFragment)。閉じたら初期設定がログインし直す。 */
    void registered() {
        setResult(RESULT_OK);
    }
}
