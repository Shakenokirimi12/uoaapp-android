package com.shakenokirimi12.uoa_app.ui.idp;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.AuthErrors;

/**
 * IdP (SECIOSS) 経由のログインを試す画面 (iOS の IdPLoginView 相当)。ID/PW は保存済みのものを使う。
 * OTP が要求されたらその場でコード入力欄を出す。
 */
public class IdPLoginFragment extends Fragment {
    private IdPLoginViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_idp_login, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(IdPLoginViewModel.class);

        View groupRunning = view.findViewById(R.id.group_running);
        View groupOtp = view.findViewById(R.id.group_otp);
        View groupError = view.findViewById(R.id.group_error);
        EditText editOtp = view.findViewById(R.id.edit_otp);
        MaterialButton submit = view.findViewById(R.id.button_submit_otp);
        TextView textError = view.findViewById(R.id.text_error);

        editOtp.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                submit.setEnabled(s.toString().trim().length() > 0);
            }
        });
        submit.setOnClickListener(v -> {
            String code = editOtp.getText().toString().trim();
            editOtp.setText("");
            viewModel.submitOtp(code);
        });
        view.findViewById(R.id.button_close_error).setOnClickListener(v ->
                Navigation.findNavController(v).popBackStack());

        viewModel.state().observe(getViewLifecycleOwner(), state -> {
            groupRunning.setVisibility(state == IdPLoginViewModel.State.RUNNING ? View.VISIBLE : View.GONE);
            groupOtp.setVisibility(state == IdPLoginViewModel.State.OTP_PROMPT ? View.VISIBLE : View.GONE);
            groupError.setVisibility(state == IdPLoginViewModel.State.ERROR ? View.VISIBLE : View.GONE);
            if (state == IdPLoginViewModel.State.ERROR) {
                String message = viewModel.currentError();
                textError.setText(message);
                AuthErrors.markInvalidIfCredentialsError(requireContext(), message);
            } else if (state == IdPLoginViewModel.State.SUCCESS) {
                Toast.makeText(requireContext(), "IdP経由でログインできました。", Toast.LENGTH_SHORT).show();
                Navigation.findNavController(view).popBackStack();
            }
        });

        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        viewModel.startOnce(prefs.getUsername(), prefs.getPassword());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 画面を離れたら OTP 入力待ちを解放する (回転では ViewModel が生き残るので onCleared で処理しない)。
        if (isRemoving() && viewModel != null) viewModel.cancel();
    }
}
