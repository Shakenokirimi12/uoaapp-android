package com.shakenokirimi12.uoa_app.ui.settings;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import com.shakenokirimi12.uoa_app.services.idp.Totp;
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
    private View rowShowSecret, dividerShowSecret, rowReregister, dividerReregister;

    /** 画面ロックの確認 (KeyguardManager)。androidx.biometric は依存に無いので使わない。 */
    private final ActivityResultLauncher<Intent> confirmCredential =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded()) return;
                if (result.getResultCode() == Activity.RESULT_OK) showSecret();
            });

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
        rowReregister.setOnClickListener(v -> openRegistration(OtpMethod.AUTHENTICATOR));

        groupRelay = view.findViewById(R.id.group_relay);
        view.findViewById(R.id.row_otp_relay).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_login_settings_to_otp_relay));

        render();
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
                    ? (hasSecret() ? R.string.login_authenticator_with_secret : R.string.login_authenticator_without_secret)
                    : R.string.settings_otp_method_mail_description);
            otpMethodDetail.setVisibility(View.VISIBLE);
        }
        boolean authenticator = known && method == OtpMethod.AUTHENTICATOR;
        setVisible(authenticator && hasSecret(), rowShowSecret, dividerShowSecret);
        setVisible(authenticator && prefs.hasCredentials() && !prefs.isCredentialsInvalid(), rowReregister, dividerReregister);
        // PC 連携はメールに届くコードを読む機能なので、メール方式のときだけ出す (iOS と同じ)。
        groupRelay.setVisibility(known && method == OtpMethod.MAIL ? View.VISIBLE : View.GONE);
    }

    private static void setVisible(boolean visible, View... views) {
        for (View v : views) v.setVisibility(visible ? View.VISIBLE : View.GONE);
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

    private void showSecret() {
        String grouped = Totp.groupedSecret(prefs.totpOtpauthFor(prefs.getUsername()));
        if (grouped == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.login_secret_title)
                .setMessage(getString(R.string.login_secret_message, grouped))
                .setPositiveButton(R.string.otp_copy_secret, (d, w) -> copySecret(grouped))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** Android 13 以降は機密として扱わせ、クリップボードのプレビューに出さない。 */
    private void copySecret(@NonNull String grouped) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText("otp secret", grouped.replace(" ", ""));
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(requireContext(), R.string.otp_secret_copied, Toast.LENGTH_SHORT).show();
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
                ? R.string.settings_otp_method_authenticator_description
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
        editUsername.setText(prefs.getUsername());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_account)
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String newUser = editUsername.getText().toString().trim();
                    String newPass = editPassword.getText().toString().trim();
                    if (newUser.isEmpty()) {
                        Toast.makeText(requireContext(), "ユーザー名を入力してください", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!prefs.isSecureStorageAvailable()) {
                        // 平文には保存しない。保存できない理由をそのまま伝える。
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.secure_storage_unavailable_title)
                                .setMessage(R.string.secure_storage_unavailable_message)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                        return;
                    }
                    if (newPass.isEmpty()) {
                        prefs.setUsername(newUser);
                        render();
                        Toast.makeText(requireContext(), "ユーザー名を更新しました", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    MoodleService moodleService = new MoodleService();
                    moodleService.login(newUser, newPass, new ServiceCallback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            if (!isAdded()) return;
                            if (result) {
                                prefs.setUsername(newUser);
                                prefs.setPassword(newPass);
                                // 別アカウントかもしれない。前のユーザーの CampusSquare セッションを引き継がない。
                                com.shakenokirimi12.uoa_app.services.CampusSquareService.clearSession();
                                render();
                                Toast.makeText(requireContext(), "ログイン情報を更新しました", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(requireContext(), "ログインに失敗しました", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onError(String message) {
                            if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
