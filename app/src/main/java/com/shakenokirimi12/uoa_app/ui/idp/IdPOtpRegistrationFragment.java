package com.shakenokirimi12.uoa_app.ui.idp;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;

/**
 * ワンタイムパスワード未設定のアカウントに対する新規登録画面 (iOS の IdPOTPRegistrationView 相当)。
 * Authenticator 方式は、シークレットをこのアプリ自身が保持・計算するのではなく、otpauth:// URL を外部の
 * 認証アプリへそのまま渡す (「UoA 非公式アプリだけが二段階認証を持つ」状態になるリスクを避けるため)。
 * 登録操作自体は学内ネットワークからのアクセスでないと SECIOSS 側の仕様で通らない。
 */
public class IdPOtpRegistrationFragment extends Fragment {
    private IdPOtpRegistrationViewModel viewModel;

    private View groupProgress, groupStatus, groupMethod, groupEmail, groupConfirm, groupCode, groupAutoFetch, groupCodeInput;
    private TextView textProgress, textStatus, textExistingStatus, textCodePrompt;
    private ImageView imageStatus;
    private MaterialButton buttonCloseStatus, buttonOpenAuthenticator, buttonRegister;
    private EditText editEmail, editCode;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_idp_otp_registration, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(IdPOtpRegistrationViewModel.class);

        groupProgress = view.findViewById(R.id.group_progress);
        groupStatus = view.findViewById(R.id.group_status);
        groupMethod = view.findViewById(R.id.group_method);
        groupEmail = view.findViewById(R.id.group_email);
        groupConfirm = view.findViewById(R.id.group_confirm);
        groupCode = view.findViewById(R.id.group_code);
        groupAutoFetch = view.findViewById(R.id.group_auto_fetch);
        groupCodeInput = view.findViewById(R.id.group_code_input);
        textProgress = view.findViewById(R.id.text_progress);
        textStatus = view.findViewById(R.id.text_status);
        textExistingStatus = view.findViewById(R.id.text_existing_status);
        textCodePrompt = view.findViewById(R.id.text_code_prompt);
        imageStatus = view.findViewById(R.id.image_status);
        buttonCloseStatus = view.findViewById(R.id.button_close_status);
        buttonOpenAuthenticator = view.findViewById(R.id.button_open_authenticator);
        buttonRegister = view.findViewById(R.id.button_register);
        editEmail = view.findViewById(R.id.edit_email);
        editCode = view.findViewById(R.id.edit_code);

        buttonCloseStatus.setOnClickListener(v -> Navigation.findNavController(v).popBackStack());
        view.findViewById(R.id.card_method_email).setOnClickListener(v -> viewModel.chooseEmail());
        view.findViewById(R.id.card_method_authenticator).setOnClickListener(v -> {
            showProgress("認証アプリ用の情報を取得しています...");
            viewModel.chooseAuthenticator();
        });
        view.findViewById(R.id.button_email_next).setOnClickListener(v -> {
            showProgress("確認コードを送信しています...");
            viewModel.confirmEmailAddressAndProceed(editEmail.getText().toString());
        });
        view.findViewById(R.id.button_confirm_proceed).setOnClickListener(v -> viewModel.proceedAfterConfirmation());
        buttonOpenAuthenticator.setOnClickListener(v -> openAuthenticatorApp());
        editCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                buttonRegister.setEnabled(s.toString().trim().length() > 0);
            }
        });
        buttonRegister.setOnClickListener(v -> viewModel.submitCode(editCode.getText().toString()));

        viewModel.stage().observe(getViewLifecycleOwner(), this::render);
        viewModel.autoFetchingEmailCode().observe(getViewLifecycleOwner(), fetching -> {
            boolean f = Boolean.TRUE.equals(fetching);
            groupAutoFetch.setVisibility(f ? View.VISIBLE : View.GONE);
            groupCodeInput.setVisibility(f ? View.GONE : View.VISIBLE);
        });

        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        if (editEmail.getText().length() == 0) {
            editEmail.setText(prefs.getUsername().trim() + "@u-aizu.ac.jp");
        }
        viewModel.startOnce(prefs.getUsername(), prefs.getPassword());
    }

    private void showProgress(String message) {
        hideAll();
        textProgress.setText(message);
        groupProgress.setVisibility(View.VISIBLE);
    }

    private void hideAll() {
        for (View g : new View[] {groupProgress, groupStatus, groupMethod, groupEmail, groupConfirm, groupCode}) {
            g.setVisibility(View.GONE);
        }
    }

    private void showStatus(int iconRes, int tintAttr, String message) {
        hideAll();
        imageStatus.setImageResource(iconRes);
        imageStatus.setImageTintList(android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(imageStatus, tintAttr)));
        textStatus.setText(message);
        groupStatus.setVisibility(View.VISIBLE);
    }

    private void render(IdPOtpRegistrationViewModel.Stage stage) {
        switch (stage) {
            case CHECKING:
                showProgress("確認中...");
                break;
            case ALREADY_CONFIGURED:
                showStatus(R.drawable.ic_shield_check, androidx.appcompat.R.attr.colorPrimary,
                        "このアカウントは既にワンタイムパスワードが設定済みです。");
                break;
            case CHOOSING_METHOD:
                hideAll();
                groupMethod.setVisibility(View.VISIBLE);
                break;
            case ENTERING_EMAIL_ADDRESS:
                hideAll();
                groupEmail.setVisibility(View.VISIBLE);
                break;
            case CONFIRMING_REGISTRATION: {
                hideAll();
                String existing = viewModel.existingStatusLabel();
                textExistingStatus.setText(existing != null
                        ? "既に設定済みの方式があります: " + existing
                        : "このアカウントは新規登録になります");
                groupConfirm.setVisibility(View.VISIBLE);
                break;
            }
            case WAITING_FOR_CODE: {
                hideAll();
                boolean authenticator = viewModel.method() == IdPOtpRegistrationViewModel.Method.AUTHENTICATOR;
                textCodePrompt.setText(authenticator
                        ? "Authenticatorアプリで表示されたコードを入力してください"
                        : "メールに届いたコードを入力してください");
                buttonOpenAuthenticator.setVisibility(authenticator && viewModel.otpauthUrl() != null ? View.VISIBLE : View.GONE);
                editCode.setText("");
                groupCode.setVisibility(View.VISIBLE);
                break;
            }
            case COMPLETING:
                showProgress("登録しています...");
                break;
            case SUCCESS:
                showStatus(R.drawable.ic_check_circle, androidx.appcompat.R.attr.colorPrimary,
                        "ワンタイムパスワードの設定が完了しました。");
                break;
            case FAILURE:
                showStatus(R.drawable.ic_circle_alert, androidx.appcompat.R.attr.colorError,
                        viewModel.failureMessage().getValue());
                break;
        }
    }

    private void openAuthenticatorApp() {
        String url = viewModel.otpauthUrl();
        if (url == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            showNoAuthenticatorAppDialog();
        }
    }

    private void showNoAuthenticatorAppDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("認証アプリが見つかりません")
                .setMessage("Google Authenticator等の認証アプリをインストールしてからもう一度お試しください。シークレットをコピーして、認証アプリに手動で追加することもできます。")
                .setNegativeButton("閉じる", null);
        String secret = viewModel.authenticatorSecretFallback();
        if (secret != null) {
            builder.setPositiveButton("シークレットをコピー", (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("otp secret", secret));
                    Toast.makeText(requireContext(), "コピーしました", Toast.LENGTH_SHORT).show();
                }
            });
        }
        builder.show();
    }
}
