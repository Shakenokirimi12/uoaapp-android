package com.shakenokirimi12.uoa_app.ui.idp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

/**
 * IdP (SECIOSS) ログインへの切り替えを、初めて迎えるユーザーに説明する画面 (iOS の IdPTutorialView 相当)。
 * iOS はページ送り形式だが、Android は 1 枚のスクロール画面にしている (カード単位で区切れば情報量は
 * 収まり、ViewPager2 とインジケータを増やす理由が無いため)。
 * 引数 next_action ("login" / "otp_registration") で、「今すぐ」を押したときに進む先を決める。
 */
public class IdPTutorialFragment extends Fragment {
    public static final String ARG_NEXT_ACTION = "next_action";
    public static final String ACTION_LOGIN = "login";
    public static final String ACTION_OTP_REGISTRATION = "otp_registration";

    private boolean otpAutoFetchChoice = false;
    private MaterialRadioButton radioAuto;
    private MaterialRadioButton radioManual;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_idp_tutorial, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String nextAction = getArguments() != null ? getArguments().getString(ARG_NEXT_ACTION, ACTION_LOGIN) : ACTION_LOGIN;

        radioAuto = view.findViewById(R.id.radio_otp_auto);
        radioManual = view.findViewById(R.id.radio_otp_manual);
        view.findViewById(R.id.row_otp_auto).setOnClickListener(v -> setChoice(true));
        view.findViewById(R.id.row_otp_manual).setOnClickListener(v -> setChoice(false));
        setChoice(false);

        MaterialButton startNow = view.findViewById(R.id.button_start_now);
        startNow.setText(ACTION_OTP_REGISTRATION.equals(nextAction) ? "今すぐ設定する" : "今すぐログインする");
        startNow.setOnClickListener(v -> finish(true, nextAction));
        view.findViewById(R.id.button_later).setOnClickListener(v -> finish(false, nextAction));
    }

    private void setChoice(boolean auto) {
        otpAutoFetchChoice = auto;
        radioAuto.setChecked(auto);
        radioManual.setChecked(!auto);
    }

    private void finish(boolean startNow, String nextAction) {
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        prefs.setOtpAutoFetchEnabled(otpAutoFetchChoice);
        prefs.setHasSeenIdPTutorial(true);
        NavController nav = Navigation.findNavController(requireView());
        if (!startNow) {
            nav.popBackStack();
            return;
        }
        // チュートリアル自身をスタックから外して次の画面へ (戻るで設定画面に戻れるように)。
        nav.navigate(ACTION_OTP_REGISTRATION.equals(nextAction)
                ? R.id.action_idp_tutorial_to_otp_registration
                : R.id.action_idp_tutorial_to_login);
    }
}
