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

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.shakenokirimi12.uoa_app.MainActivity;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;

/**
 * ワンタイムパスワード未設定のアカウントに対する新規登録画面 (iOS の IdPOTPRegistrationView 相当)。
 * 認証アプリ方式では、IdP から受け取ったシークレットを登録が済んだあとアプリが保持してコードを作る
 * (iOS と同じ。2026-10-24 の利用者の要望)。外部の認証アプリにも追加できるよう、キーを区切って出し、
 * otpauth:// を受けるアプリ (Google Authenticator) にも渡せる。Authy は otpauth:// を受けないので手で追加する。
 * 登録操作自体は学内ネットワークからのアクセスでないと SECIOSS 側の仕様で通らない。
 *
 * 設定画面からの方式の切り替えにも使う (引数 switching_to)。結果は閉じたときに RESULT_SWITCH で返す。
 */
public class IdPOtpRegistrationFragment extends Fragment {
    /** 切り替え先 (OtpMethod.value)。無ければ新規登録。 */
    public static final String ARG_SWITCHING_TO = "switching_to";
    /** 切り替えの画面を閉じたときに、設定画面へ返す結果 (FragmentManager の fragment result)。 */
    public static final String RESULT_SWITCH = "otp_method_switch";
    /** 結果の中身: 切り替え先 (OtpMethod.value)。 */
    public static final String RESULT_TARGET = "target";
    /** 結果の中身: OUTCOME_* のどれか。 */
    public static final String RESULT_OUTCOME = "outcome";
    public static final String OUTCOME_SUCCEEDED = "succeeded";
    public static final String OUTCOME_FAILED = "failed";
    public static final String OUTCOME_CANCELLED = "cancelled";

    private IdPOtpRegistrationViewModel viewModel;
    @Nullable private OtpMethod switchingTo;
    /** 今と同じ方式での登録し直し (認証アプリのキーを作り直す)。開いた時点の受け取り方で決める。 */
    private boolean reRegistering;
    /** 登録の POST を送ったあと (Stage.COMPLETING) は戻る操作を受け付けない。閉じると成功を受け取れない。 */
    private final OnBackPressedCallback blockBackWhileCompleting = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {}
    };
    private TextView textMailConsent, textConfirmNotice;

    private View groupProgress, groupStatus, groupMethod, groupEmail, groupConfirm, groupCode, groupAutoFetch, groupCodeInput;
    private TextView textProgress, textStatus, textExistingStatus, textCodePrompt;
    private ImageView imageStatus;
    private MaterialButton buttonCloseStatus, buttonOpenAuthenticator, buttonRegister;
    private View groupManualSecret;
    private TextView textManualSecret;
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
        String target = getArguments() != null ? getArguments().getString(ARG_SWITCHING_TO) : null;
        switchingTo = target == null || target.isEmpty() ? null : OtpMethod.from(target);
        if (savedInstanceState != null) {
            reRegistering = savedInstanceState.getBoolean(STATE_RE_REGISTERING);
        } else {
            PreferenceManager p = PreferenceManager.getInstance(requireContext());
            reRegistering = switchingTo != null && p.isOtpMethodKnown() && OtpMethod.from(p.getOtpMethod()) == switchingTo;
        }
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), blockBackWhileCompleting);

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
        textMailConsent = view.findViewById(R.id.text_mail_consent);
        textConfirmNotice = view.findViewById(R.id.text_confirm_notice);
        imageStatus = view.findViewById(R.id.image_status);
        buttonCloseStatus = view.findViewById(R.id.button_close_status);
        buttonOpenAuthenticator = view.findViewById(R.id.button_open_authenticator);
        buttonRegister = view.findViewById(R.id.button_register);
        editEmail = view.findViewById(R.id.edit_email);
        editCode = view.findViewById(R.id.edit_code);
        groupManualSecret = view.findViewById(R.id.group_manual_secret);
        textManualSecret = view.findViewById(R.id.text_manual_secret);
        view.findViewById(R.id.button_copy_secret).setOnClickListener(v -> copySecret());

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
        // 入力したコードがアプリの計算と合わなかった (登録は送っていない)。同じ画面で入力し直してもらう。
        viewModel.codeMismatch().observe(getViewLifecycleOwner(), mismatch -> {
            if (Boolean.TRUE.equals(mismatch)) textCodePrompt.setText(R.string.otp_code_mismatch);
        });
        viewModel.autoFetchingEmailCode().observe(getViewLifecycleOwner(), fetching -> {
            boolean f = Boolean.TRUE.equals(fetching);
            groupAutoFetch.setVisibility(f ? View.VISIBLE : View.GONE);
            groupCodeInput.setVisibility(f ? View.GONE : View.VISIBLE);
        });

        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        if (editEmail.getText().length() == 0) {
            editEmail.setText(prefs.getUsername().trim() + "@u-aizu.ac.jp");
        }
        viewModel.startOnce(prefs.getUsername(), prefs.getPassword(), switchingTo);
    }

    private static final String STATE_RE_REGISTERING = "re_registering";

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_RE_REGISTERING, reRegistering);
    }

    @Override
    public void onDestroyView() {
        setLeaveLocked(false);
        // 画面を離れた (戻る・下タブ)。登録の POST はこの画面が見えている間にしか送らないので、まだ送って
        // いなければ中断する。下タブで離れると戻る先として残り ViewModel も生きたままになるので、
        // 中断しないと画面の無いところで登録が進む。回転などの作り直しでは中断しない。
        // 認証アプリやメールのアプリへ移るだけなら、この画面の View は残るのでここは通らない。
        if (viewModel != null && !requireActivity().isChangingConfigurations()) viewModel.cancelBeforePost();
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        // 切り替えの画面を閉じた。設定画面が「なぜ変わっていないか」を出せるように結果を返す。
        IdPOtpRegistrationViewModel.Stage stage = viewModel != null ? viewModel.stage().getValue() : null;
        // COMPLETING のまま閉じた (Activity ごと終わった等) ときは結果がまだ分からないので返さない。
        // 受け取り方は登録が済めば OtpRegistrationCommit が書き換え、設定画面は表示のたびに読み直す。
        if (switchingTo != null && isRemoving() && stage != null
                && stage != IdPOtpRegistrationViewModel.Stage.COMPLETING) {
            Bundle result = new Bundle();
            result.putString(RESULT_TARGET, switchingTo.value);
            result.putString(RESULT_OUTCOME, stage == IdPOtpRegistrationViewModel.Stage.SUCCESS ? OUTCOME_SUCCEEDED
                    : stage == IdPOtpRegistrationViewModel.Stage.FAILURE ? OUTCOME_FAILED : OUTCOME_CANCELLED);
            getParentFragmentManager().setFragmentResult(RESULT_SWITCH, result);
        }
        super.onDestroy();
    }

    /** 戻る (システムの戻る・上バーの戻る・下タブ) を止める / 戻す。 */
    private void setLeaveLocked(boolean locked) {
        blockBackWhileCompleting.setEnabled(locked);
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).setNavigationLocked(locked);
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
        setLeaveLocked(stage == IdPOtpRegistrationViewModel.Stage.COMPLETING);
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
                textMailConsent.setVisibility(switchingTo == OtpMethod.MAIL ? View.VISIBLE : View.GONE);
                // 新規登録ではレイアウトの「一度登録すると、未設定の状態には戻せません。」のまま。
                if (reRegistering) textConfirmNotice.setText(R.string.otp_reregister_notice);
                else if (switchingTo != null) textConfirmNotice.setText(R.string.otp_switch_keeps_current);
                groupConfirm.setVisibility(View.VISIBLE);
                break;
            }
            case WAITING_FOR_CODE: {
                hideAll();
                boolean authenticator = viewModel.method() == IdPOtpRegistrationViewModel.Method.AUTHENTICATOR;
                textCodePrompt.setText(authenticator ? R.string.otp_code_from_app : R.string.otp_code_mail_prompt);
                buttonOpenAuthenticator.setVisibility(authenticator && viewModel.otpauthUrl() != null ? View.VISIBLE : View.GONE);
                String secret = authenticator ? viewModel.groupedSecret() : null;
                groupManualSecret.setVisibility(secret != null ? View.VISIBLE : View.GONE);
                textManualSecret.setText(secret != null ? secret : "");
                // アプリが作ったコードは入れておかない。外部の認証アプリのコードを打ってもらい、それがアプリの計算と
                // 合うことで、外部のアプリにも同じキーが入っていると確かめる (iOS と同じ)。
                editCode.setText("");
                groupCode.setVisibility(View.VISIBLE);
                break;
            }
            case COMPLETING:
                showProgress("登録しています...");
                break;
            case SUCCESS:
                showStatus(R.drawable.ic_check_circle, androidx.appcompat.R.attr.colorPrimary,
                        switchingTo == null ? "ワンタイムパスワードの設定が完了しました。"
                                : reRegistering ? getString(R.string.otp_reregister_done)
                                : getString(R.string.otp_switch_done));
                break;
            case CANCELLED:
                showStatus(R.drawable.ic_circle_alert, com.google.android.material.R.attr.colorOnSurfaceVariant,
                        getString(R.string.otp_registration_cancelled));
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
            // Authy などは otpauth:// を受けない。下のキーを手で追加してもらう。
            Toast.makeText(requireContext(), R.string.otp_no_otpauth_app, Toast.LENGTH_LONG).show();
        }
    }

    /** キーをクリップボードへ。Android 13 以降は機密として扱わせ、プレビューに出さない。 */
    private void copySecret() {
        String secret = viewModel.groupedSecret();
        if (secret == null) return;
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText("otp secret", secret.replace(" ", ""));
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(requireContext(), R.string.otp_secret_copied, Toast.LENGTH_SHORT).show();
    }
}
