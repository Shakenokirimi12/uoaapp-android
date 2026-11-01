package com.shakenokirimi12.uoa_app.ui.settings;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import com.shakenokirimi12.uoa_app.services.idp.Totp;
import com.shakenokirimi12.uoa_app.ui.idp.AuthenticatorKeyActions;
import com.shakenokirimi12.uoa_app.ui.idp.IdPOtpRegistrationFragment;

/**
 * ログインとワンタイムパスワードの設定。設定画面から 1 行で開く。
 *
 * 以前は設定画面に並べていたが、受け取り方の切り替え・認証アプリの登録し直し・キーの表示・PC 連携と
 * 項目が増えて読みにくくなった (2026-10-24 の利用者の指摘)。流れそのもの (IdPOtpRegistrationFragment、
 * OtpRelaySettingsFragment) は変えていない。
 */
public class LoginSettingsFragment extends Fragment {

    private PreferenceManager prefs;
    private TextView textUsername;
    private TextView otpMethodLabel;
    private TextView otpMethodDetail;
    private View groupRelay;
    private View rowShowSecret, dividerShowSecret, rowReregister, dividerReregister, rowImportKey, dividerImportKey;
    private View cardReregisterNeeded;
    private TextView textReregisterNeededTitle;
    private TextView textReregisterNeededText;
    private View cardCredentialsInvalid;

    /** 画面ロックの確認 (KeyguardManager)。androidx.biometric は依存に無いので使わない。 */
    private final ActivityResultLauncher<Intent> confirmCredential =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded()) return;
                if (result.getResultCode() == Activity.RESULT_OK) showSecret();
            });

    /** 認証アプリにある鍵の取り込み (OtpKeyImport)。写真の選択は権限が要らない (Photo Picker)。 */
    private final ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickQrImage =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (isAdded() && prefs != null) importFlow().onImagePicked(uri);
            });
    /** 取り込みの手順。始めるか写真の結果が来たときに作る (ユーザー名はそのときの値)。 */
    @Nullable private OtpKeyImportFlow importFlow;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getParentFragmentManager().setFragmentResultListener(IdPOtpRegistrationFragment.RESULT_SWITCH, this,
                (key, result) -> onOtpSwitchResult(result));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_login_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = PreferenceManager.getInstance(requireContext());

        textUsername = view.findViewById(R.id.text_username);
        view.findViewById(R.id.row_account).setOnClickListener(v -> showEditAccountDialog());

        otpMethodLabel = view.findViewById(R.id.text_otp_method);
        otpMethodDetail = view.findViewById(R.id.text_otp_method_detail);
        view.findViewById(R.id.row_otp_method).setOnClickListener(v -> showOtpMethodDialog());

        rowShowSecret = view.findViewById(R.id.row_show_secret);
        dividerShowSecret = view.findViewById(R.id.divider_show_secret);
        rowShowSecret.setOnClickListener(v -> confirmThenShowSecret());

        rowReregister = view.findViewById(R.id.row_reregister);
        dividerReregister = view.findViewById(R.id.divider_reregister);
        // 認証アプリ → 認証アプリ。方式の切り替えと同じ登録の流れを使う (学内ゲートウェイ経由でパスワードだけで
        // 登録画面に入るので、手元に通るコードが無くても登録し直せる)。
        rowReregister.setOnClickListener(v -> reregister());
        cardReregisterNeeded = view.findViewById(R.id.card_reregister_needed);
        textReregisterNeededTitle = view.findViewById(R.id.text_reregister_needed_title);
        textReregisterNeededText = view.findViewById(R.id.text_reregister_needed_text);
        cardCredentialsInvalid = view.findViewById(R.id.card_credentials_invalid);
        view.findViewById(R.id.button_credentials_invalid).setOnClickListener(v -> showEditAccountDialog());
        view.findViewById(R.id.button_reregister_needed).setOnClickListener(v -> reregister());
        // 認証アプリにはもう大学のアカウントがある人は、登録し直さずに鍵を取り込める。
        rowImportKey = view.findViewById(R.id.row_import_key);
        dividerImportKey = view.findViewById(R.id.divider_import_key);
        rowImportKey.setOnClickListener(v -> showImportGuide());
        view.findViewById(R.id.button_import_key_needed).setOnClickListener(v -> showImportGuide());

        groupRelay = view.findViewById(R.id.group_relay);
        view.findViewById(R.id.row_otp_relay).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_login_settings_to_otp_relay));

        render();
    }

    @Override
    public void onDestroyView() {
        // 回転などで画面が作り直される。出している手順のダイアログを閉じる (閉じないと前の Activity に残って
        // WindowLeaked になる)。写真を選んでいる間の結果は、作り直した画面の pickQrImage が新しい手順で読む。
        if (importFlow != null) {
            importFlow.dismiss();
            importFlow = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 受け取り方は、登録の完了やログイン時の読み取り (OtpProviders) で裏で変わることがある。
        if (getView() != null) render();
    }

    private void render() {
        String username = prefs.getUsername();
        textUsername.setText(username.isEmpty() ? getString(R.string.login_not_logged_in) : username);

        boolean known = prefs.isOtpMethodKnown();
        OtpMethod method = OtpMethod.from(prefs.getOtpMethod());
        if (!known) {
            otpMethodLabel.setText(R.string.settings_otp_method_unknown);
            otpMethodDetail.setVisibility(View.GONE);
        } else {
            otpMethodLabel.setText(method == OtpMethod.AUTHENTICATOR
                    ? R.string.settings_otp_method_authenticator : R.string.settings_otp_method_mail);
            otpMethodDetail.setText(method == OtpMethod.AUTHENTICATOR
                    ? authenticatorDetail()
                    : R.string.settings_otp_method_mail_description);
            otpMethodDetail.setVisibility(View.VISIBLE);
        }
        boolean authenticator = known && method == OtpMethod.AUTHENTICATOR;
        setVisible(authenticator && hasSecret(), rowShowSecret, dividerShowSecret);
        setVisible(authenticator, rowReregister, dividerReregister);
        // 方式が分からないまま (otp_type が読めない) でも、認証アプリにある鍵は取り込める。メール方式の人には出さない。
        setVisible((authenticator || !known) && !username.isEmpty(), rowImportKey, dividerImportKey);
        // キーが無い・止めているのは普通の状態ではない (登録し直しが要る)。上に案内を出す。
        boolean suspended = authenticator && prefs.isTotpSuspended(username);
        cardReregisterNeeded.setVisibility(authenticator && (!hasSecret() || suspended) ? View.VISIBLE : View.GONE);
        textReregisterNeededTitle.setText(suspended ? R.string.auth_key_suspended_title : R.string.login_reregister_needed_title);
        textReregisterNeededText.setText(suspended ? R.string.auth_key_suspended_text : R.string.login_reregister_needed_text);
        cardCredentialsInvalid.setVisibility(prefs.isCredentialsInvalid() ? View.VISIBLE : View.GONE);
        // PC 連携は、メール方式ならメールから読み、認証アプリ方式ならキーで作って送る。方式にかかわらず出す。
        groupRelay.setVisibility(View.VISIBLE);
    }

    /** 使っている認証アプリを選んでもらい、鍵を取り込む (OtpKeyImportFlow)。 */
    private void showImportGuide() {
        // 前の手順の途中のもの (ユーザー名を含む) を持ち越さない。
        importFlow = null;
        importFlow().start();
    }

    /**
     * 取り込みの手順。Fragment が作り直されたら (回転など) 作り直す。写真を選んでいる間に作り直されても、
     * 結果は新しい Fragment の pickQrImage に返り、新しい手順で読む。
     */
    @NonNull
    private OtpKeyImportFlow importFlow() {
        if (importFlow == null) {
            importFlow = new OtpKeyImportFlow(requireContext(), prefs, prefs.getUsername(), new OtpKeyImportFlow.Host() {
                @Override public boolean isAlive() { return isAdded(); }

                @Override public void launchImagePicker() {
                    pickQrImage.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                            .build());
                }

                @Override public void onSaved() {
                    importFlow = null;
                    render();
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.otp_import_done_title)
                            .setMessage(R.string.otp_import_done_text)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }

                @Override public void onClosed() { importFlow = null; }

                @Override public boolean offersReregistration() { return true; }

                @Override public void reregister() {
                    importFlow = null;
                    LoginSettingsFragment.this.reregister();
                }
            });
        }
        return importFlow;
    }

    private void reregister() {
        if (!canStartReregistration(requireContext(), prefs)) return;
        openRegistration(OtpMethod.AUTHENTICATOR);
    }

    /**
     * 登録し直しを始めてよいか。登録はパスワードでのログインから始まる。無い・誤りと分かっているときは始めず、
     * 理由をトーストで出す (誤ったパスワードで試し続けると大学側でロックされうる)。ログイン中の選択
     * (AuthenticatorKeyChoice) も同じ判断を使う。
     */
    public static boolean canStartReregistration(@NonNull Context context, @NonNull PreferenceManager prefs) {
        if (prefs.hasCredentials() && !prefs.isCredentialsInvalid()) return true;
        Toast.makeText(context, prefs.hasCredentials()
                ? R.string.otp_switch_blocked_credentials : R.string.otp_switch_blocked_no_account,
                Toast.LENGTH_LONG).show();
        return false;
    }

    private static void setVisible(boolean visible, View... views) {
        for (View v : views) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** 認証アプリ方式の説明。鍵を止めている間は「自動で再ログインします」と言わない。 */
    private int authenticatorDetail() {
        if (!hasSecret()) return R.string.login_authenticator_without_secret;
        return Totp.parse(prefs.usableTotpOtpauthFor(prefs.getUsername())) != null
                ? R.string.login_authenticator_with_secret : R.string.login_authenticator_key_suspended;
    }

    private boolean hasSecret() {
        return Totp.parse(prefs.totpOtpauthFor(prefs.getUsername())) != null;
    }

    private void openRegistration(@NonNull OtpMethod target) {
        Bundle args = new Bundle();
        args.putString(IdPOtpRegistrationFragment.ARG_SWITCHING_TO, target.value);
        Navigation.findNavController(requireView()).navigate(R.id.action_login_settings_to_idp_otp_registration, args);
    }

    // ---- キーの表示 ----

    /** キーは 2 段目の認証そのものなので、画面ロックで本人か確かめてから出す。 */
    private void confirmThenShowSecret() {
        KeyguardManager km = (KeyguardManager) requireContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.login_secret_title)
                    .setMessage(R.string.login_secret_needs_lock)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        @SuppressWarnings("deprecation")
        Intent intent = km.createConfirmDeviceCredentialIntent(
                getString(R.string.login_secret_confirm_title), getString(R.string.login_secret_confirm_description));
        if (intent == null) return;
        confirmCredential.launch(intent);
    }

    /** QR・キー・コピー・認証アプリで開く、をまとめて出す。どれもキーを端末の外へ出せるので、本人確認のあとだけ。 */
    private void showSecret() {
        String url = prefs.totpOtpauthFor(prefs.getUsername());
        String grouped = Totp.groupedSecret(url);
        if (url == null || grouped == null) return;
        AuthenticatorKeyActions.showKeyDialog(this, url, grouped);
    }

    // ---- 受け取り方の切り替え (設定画面から移しただけで、流れは変えていない) ----

    /**
     * ワンタイムパスワードの受け取り方を切り替える。方式を変えるには大学側 (IdP) の登録をやり直す。
     * ここで設定だけを書き換えると、IdP は前の方式のままなのでログインできなくなる。受け取り方は
     * 登録が済んだときにだけ書き換わる (OtpRegistrationCommit)。中断・失敗したら理由を出す
     * (onOtpSwitchResult)。
     */
    private void showOtpMethodDialog() {
        OtpMethod current = OtpMethod.from(prefs.getOtpMethod());
        OtpMethod other = current == OtpMethod.MAIL ? OtpMethod.AUTHENTICATOR : OtpMethod.MAIL;
        // ID / パスワードの誤りが確定している間は、登録のためのログインも始めない (同じ誤ったパスワードで
        // 何度も試すと、大学側でアカウントがロックされる)。
        boolean noAccount = prefs.getUsername().isEmpty() || prefs.getPassword().isEmpty();
        // 今の方式が分からないうちは、どちらへ切り替えるのかも決められない。ログインで読めるのを待つ。
        boolean unknown = !prefs.isOtpMethodKnown();
        boolean blocked = noAccount || unknown || prefs.isCredentialsInvalid();
        String message = (unknown ? "" : getString(current == OtpMethod.AUTHENTICATOR
                ? authenticatorDetail()
                : R.string.settings_otp_method_mail_description) + "\n\n")
                + getString(noAccount ? R.string.otp_switch_blocked_no_account
                : unknown ? R.string.otp_switch_blocked_unknown
                : prefs.isCredentialsInvalid() ? R.string.otp_switch_blocked_credentials
                : R.string.otp_switch_note);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(unknown ? R.string.settings_otp_method
                        : current == OtpMethod.AUTHENTICATOR
                        ? R.string.settings_otp_method_authenticator
                        : R.string.settings_otp_method_mail)
                .setMessage(message)
                .setPositiveButton(other == OtpMethod.AUTHENTICATOR
                        ? R.string.otp_switch_to_authenticator
                        : R.string.otp_switch_to_mail, (d, w) -> openRegistration(other))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setEnabled(!blocked);
    }

    /** 切り替えの画面を閉じたあと。受け取り方が変わっていなければ、その理由を出す。 */
    private void onOtpSwitchResult(@NonNull Bundle result) {
        if (!isAdded()) return;
        if (getView() != null) render();
        OtpMethod current = OtpMethod.from(PreferenceManager.getInstance().getOtpMethod());
        // 切り替え先になっていれば成功。結果の種類より設定の値を信じる。登録し直し (同じ方式) の結果は
        // 登録の画面が出している。
        if (current.value.equals(result.getString(IdPOtpRegistrationFragment.RESULT_TARGET))) return;
        String name = getString(current == OtpMethod.AUTHENTICATOR
                ? R.string.otp_method_name_authenticator : R.string.otp_method_name_mail);
        boolean failed = IdPOtpRegistrationFragment.OUTCOME_FAILED.equals(
                result.getString(IdPOtpRegistrationFragment.RESULT_OUTCOME));
        Toast.makeText(requireContext(),
                getString(failed ? R.string.otp_switch_failed : R.string.otp_switch_cancelled, name),
                Toast.LENGTH_LONG).show();
    }

    // ---- アカウント (設定画面から移しただけ) ----

    private void showEditAccountDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_account, null);
        EditText editUsername = dialogView.findViewById(R.id.edit_username);
        EditText editPassword = dialogView.findViewById(R.id.edit_password);
        TextInputLayout layoutPassword = dialogView.findViewById(R.id.layout_password);
        // エラーのアイコンがパスワードの表示切り替えを隠さないようにする。
        layoutPassword.setErrorIconDrawable(null);
        editUsername.setText(prefs.getUsername());
        // 打ち直したらエラーを外す (補足の文に戻る)。
        android.text.TextWatcher clearError = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { layoutPassword.setError(null); }
        };
        editUsername.addTextChangedListener(clearError);
        editPassword.addTextChangedListener(clearError);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_account)
                .setView(dialogView)
                .setPositiveButton(R.string.login_account_save, null)
                .setNegativeButton(R.string.cancel, null)
                .show();
        // 入力が足りないときは閉じない。閉じると、打ったユーザー名が消えて最初から入れ直しになる。
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newUser = editUsername.getText().toString().trim();
            String newPass = editPassword.getText().toString().trim();
            if (newUser.isEmpty()) {
                Toast.makeText(requireContext(), R.string.login_account_username_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (newPass.isEmpty()) {
                if (newUser.equals(prefs.getUsername())) {
                    // 何も変えていない。
                    dialog.dismiss();
                    return;
                }
                // 新しいユーザー名と、保存しているパスワード (前のアカウントのもの) を組み合わせない。組み合わせると
                // 次のログインで前のアカウントのパスワードを別のアカウントとして大学の認証システムに送り、失敗して
                // (ロックの回数に数えられうる) 誤りの印まで立つ。ユーザー名を変えるときはパスワードも入れてもらう。
                layoutPassword.setError(getString(R.string.login_account_password_required));
                editPassword.requestFocus();
                return;
            }
            dialog.dismiss();
            if (!prefs.isSecureStorageAvailable()) {
                // 平文には保存しない。保存できない理由をそのまま伝える。
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.secure_storage_unavailable_title)
                        .setMessage(R.string.secure_storage_unavailable_message)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return;
            }
            // ユーザー名とパスワードは、ログインで確かめてから組で保存する。別のアカウントなら、前のアカウントの
            // SSO・セッションを確認のログインの前に捨てる。残すと SSO だけで通ってパスワードを確かめないまま保存し、
            // 後で捨てると確認のログインで得たセッションまで消える (AccountSwitch)。
            com.shakenokirimi12.uoa_app.services.AccountSwitch.prepare(prefs, newUser);
            MoodleService moodleService = new MoodleService();
            moodleService.login(newUser, newPass, new ServiceCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    if (!isAdded()) return;
                    if (result) {
                        // 前のアカウントの SSO・セッションは確認のログインの前に捨ててある。ここで捨てると、確認の
                        // ログインで得た SSO とセッションまで消える (S-8)。
                        // 別のアカウントに決まったら、確認の最中に前のアカウントで始まった取得の結果も捨てる。
                        if (prefs.setUsername(newUser)) {
                            com.shakenokirimi12.uoa_app.services.AccountSwitch.committed(requireContext(), prefs, newUser);
                        }
                        prefs.setPassword(newPass);
                        render();
                        Toast.makeText(requireContext(), R.string.login_account_saved, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), R.string.login_account_login_failed, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(String message) {
                    if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}
