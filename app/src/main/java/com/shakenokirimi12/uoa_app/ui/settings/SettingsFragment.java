package com.shakenokirimi12.uoa_app.ui.settings;

import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
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

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.shakenokirimi12.uoa_app.BuildConfig;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.BackupService;
import com.shakenokirimi12.uoa_app.services.LocationGeofenceService;
import com.shakenokirimi12.uoa_app.services.MoodleService;
import com.shakenokirimi12.uoa_app.services.ServiceCallback;
import com.shakenokirimi12.uoa_app.ui.idp.IdPOtpRegistrationFragment;
import com.shakenokirimi12.uoa_app.ui.onboarding.OnboardingActivity;

public class SettingsFragment extends Fragment {

    private PreferenceManager prefs;
    private TextView textUsername;
    private MaterialSwitch switchAutoAttendance;
    private TextView textLocationWarning;

    // ---- 位置情報の許可 ----
    //
    // ジオフェンスはフォアグラウンド許可だけでも登録できるが、授業中にアプリを開いていることは
    // 無いので実際に発火するには「常に許可」が要る (iOS の Always と同じ)。
    // API 30 以降はバックグラウンド許可を直接ダイアログで出せず、設定画面に飛ばされる。

    private final ActivityResultLauncher<String[]> fineLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (!isAdded()) return;
                if (hasFineLocation()) {
                    switchAutoAttendance.setChecked(true);
                } else {
                    Toast.makeText(requireContext(), "位置情報の許可が無いと自動出席は使えません", Toast.LENGTH_LONG).show();
                    updateLocationWarning();
                }
            });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!isAdded()) return;
                if (granted) {
                    LocationGeofenceService.startCampusGeofence(requireContext());
                }
                updateLocationWarning();
            });

    private boolean hasFineLocation() {
        return androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void enableAutoAttendance() {
        prefs.setAutoAttendanceEnabled(true);
        LocationGeofenceService.startCampusGeofence(requireContext());
        if (!hasBackgroundLocation()) {
            requestBackgroundLocation();
        }
        updateLocationWarning();
    }

    private void requestBackgroundLocation() {
        if (!hasFineLocation()) {
            fineLocationLauncher.launch(new String[] {
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        if (hasBackgroundLocation()) return;
        String option = Build.VERSION.SDK_INT >= 30
                ? requireContext().getPackageManager().getBackgroundPermissionOptionLabel().toString()
                : "常に許可";
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("位置情報を「" + option + "」にしてください")
                .setMessage("自動出席はアプリを閉じている間にキャンパスへの到着を検知します。"
                        + "次の画面で位置情報を「" + option + "」に変更してください。")
                .setPositiveButton("設定へ", (d, w) ->
                        backgroundLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                .setNegativeButton("あとで", null)
                .show();
    }

    /** iOS の StatusNoticeBanner 相当。有効なのに「常に許可」でないときだけ出す。 */
    private void updateLocationWarning() {
        if (textLocationWarning == null) return;
        boolean show = prefs.isAutoAttendanceEnabled() && !(hasFineLocation() && hasBackgroundLocation());
        textLocationWarning.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getParentFragmentManager().setFragmentResultListener(IdPOtpRegistrationFragment.RESULT_SWITCH, this,
                (key, result) -> onOtpSwitchResult(result));
    }

    @Override
    public void onResume() {
        super.onResume();
        // 設定アプリから戻ってきたときに反映する。
        updateLocationWarning();
        // OTP の受け取り方は、登録の完了やログイン時の読み取り (OtpProviders) で裏で変わることがある。
        updateOtpMethodLabel();
    }

    private final ActivityResultLauncher<Intent> backupLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded()) return;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    try {
                        BackupService.exportTo(requireContext(), result.getData().getData());
                        Toast.makeText(requireContext(), "バックアップ完了", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "バックアップ失敗: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final ActivityResultLauncher<Intent> restoreLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded()) return;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    try {
                        BackupService.importFrom(requireContext(), result.getData().getData());
                        Toast.makeText(requireContext(), "リストア完了。アプリを再起動してください。", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "リストア失敗: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = PreferenceManager.getInstance(requireContext());

        // Account
        textUsername = view.findViewById(R.id.text_username);
        boolean loggedIn = !prefs.getUsername().isEmpty();
        textUsername.setText(loggedIn ? prefs.getUsername() : "未ログイン");
        view.findViewById(R.id.row_account).setOnClickListener(v -> showEditAccountDialog());

        otpMethodLabel = view.findViewById(R.id.text_otp_method);
        updateOtpMethodLabel();
        view.findViewById(R.id.row_otp_method).setOnClickListener(v -> showOtpMethodDialog());
        otpRelayRow = view.findViewById(R.id.row_otp_relay);
        otpRelayDivider = view.findViewById(R.id.divider_otp_relay);
        otpRelayRow.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_otp_relay));
        updateOtpMethodLabel();
        // A logout button next to "未ログイン" is contradictory; hide it until there is an account.
        view.findViewById(R.id.button_logout).setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.divider_logout).setVisibility(loggedIn ? View.VISIBLE : View.GONE);

        // IdP (SECIOSS) のログインや新規登録の手動導線は置かない (方式の切り替えだけは上の行から)。
        // ログインは同期の裏で完結し、OTP 未設定はログイン時に検知して自動で登録画面へ案内する
        // (OtpRegistrationLauncher、iOS と同じ)。

        // Academic navigation
        view.findViewById(R.id.button_grades).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_grades));
        view.findViewById(R.id.button_facilities).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_facilities));

        // Notification switches
        MaterialSwitch switchAssignment = view.findViewById(R.id.switch_assignment_notify);
        MaterialSwitch switchLunch = view.findViewById(R.id.switch_lunch_notify);

        switchAssignment.setChecked(prefs.isAssignmentNotifyEnabled());
        switchLunch.setChecked(prefs.isLunchNotifyEnabled());

        switchAssignment.setOnCheckedChangeListener((v, checked) ->
                {
                    prefs.setAssignmentNotifyEnabled(checked);
                    // OFF にしたらサーバー側の予約も消す。消さないと cron から届き続ける。
                    if (!checked) com.shakenokirimi12.uoa_app.services.push.AssignmentReminderSync.clear(requireContext());
                });

        // Lunch notify with time picker
        View layoutLunchTime = view.findViewById(R.id.layout_lunch_time);
        TextView textLunchTime = view.findViewById(R.id.text_lunch_time);
        textLunchTime.setText(prefs.getLunchNotifyTime());
        layoutLunchTime.setVisibility(prefs.isLunchNotifyEnabled() ? View.VISIBLE : View.GONE);

        switchLunch.setOnCheckedChangeListener((v, checked) -> {
            prefs.setLunchNotifyEnabled(checked);
            layoutLunchTime.setVisibility(checked ? View.VISIBLE : View.GONE);
            com.shakenokirimi12.uoa_app.services.notification.LunchMenuWorker.reschedule(requireContext());
        });

        textLunchTime.setOnClickListener(v -> showTimePicker(textLunchTime));

        // Notification timing settings
        view.findViewById(R.id.button_notify_times).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_notify_times));

        // Sync interval toggle
        MaterialButtonToggleGroup toggleInterval = view.findViewById(R.id.toggle_sync_interval);
        int interval = prefs.getSyncInterval();
        if (interval == 15) toggleInterval.check(R.id.btn_interval_15);
        else if (interval == 30) toggleInterval.check(R.id.btn_interval_30);
        else toggleInterval.check(R.id.btn_interval_60);

        toggleInterval.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_interval_15) prefs.setSyncInterval(15);
            else if (checkedId == R.id.btn_interval_30) prefs.setSyncInterval(30);
            else prefs.setSyncInterval(60);
            com.shakenokirimi12.uoa_app.services.sync.SyncScheduler.schedule(requireContext());
        });

        // Background sync notification toggles
        MaterialSwitch switchBgChange = view.findViewById(R.id.switch_bg_change);
        MaterialSwitch switchBgSuccess = view.findViewById(R.id.switch_bg_success);
        MaterialSwitch switchBgFailure = view.findViewById(R.id.switch_bg_failure);
        MaterialSwitch switchBgNoChange = view.findViewById(R.id.switch_bg_nochange);

        switchBgChange.setChecked(prefs.isBgNotifyChange());
        switchBgSuccess.setChecked(prefs.isBgNotifySuccess());
        switchBgFailure.setChecked(prefs.isBgNotifyFailure());
        switchBgNoChange.setChecked(prefs.isBgNotifyNoChange());

        switchBgChange.setOnCheckedChangeListener((v, c) -> prefs.setBgNotifyChange(c));
        switchBgSuccess.setOnCheckedChangeListener((v, c) -> prefs.setBgNotifySuccess(c));
        switchBgFailure.setOnCheckedChangeListener((v, c) -> prefs.setBgNotifyFailure(c));
        switchBgNoChange.setOnCheckedChangeListener((v, c) -> prefs.setBgNotifyNoChange(c));

        // Auto attendance. Nothing used to request location permission, so the switch
        // turned on, the geofence silently failed, and the feature never worked.
        switchAutoAttendance = view.findViewById(R.id.switch_auto_attendance);
        textLocationWarning = view.findViewById(R.id.text_location_warning);
        switchAutoAttendance.setChecked(prefs.isAutoAttendanceEnabled());
        switchAutoAttendance.setOnCheckedChangeListener((v, checked) -> {
            if (!checked) {
                prefs.setAutoAttendanceEnabled(false);
                LocationGeofenceService.stopGeofencing(requireContext());
                updateLocationWarning();
                return;
            }
            if (!hasFineLocation()) {
                // Keep the switch off until the permission result comes back.
                switchAutoAttendance.setChecked(false);
                fineLocationLauncher.launch(new String[] {
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION});
                return;
            }
            enableAutoAttendance();
        });
        textLocationWarning.setOnClickListener(v -> requestBackgroundLocation());
        updateLocationWarning();

        // Navigation customization
        view.findViewById(R.id.button_nav_settings).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_nav_settings));

        // Backup / Restore
        view.findViewById(R.id.button_backup).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "uoa_app_backup.json");
            backupLauncher.launch(intent);
        });

        view.findViewById(R.id.button_restore).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            restoreLauncher.launch(intent);
        });

        // Version
        TextView textVersion = view.findViewById(R.id.text_version);
        textVersion.setText(getString(R.string.settings_version) + " " + BuildConfig.VERSION_NAME);

        // Logout
        view.findViewById(R.id.button_logout).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_logout)
                        .setMessage("ログアウトしてもよろしいですか？")
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            // 先にサーバー側の予約を空にする。prefs.clearAll() は deviceId と
                            // 「サーバーが予約を持っている」記録の両方を消すので、後から呼ぶと
                            // 取り消しは早期 return し、古い deviceId の予約が二度と触れなくなる。
                            com.shakenokirimi12.uoa_app.services.push.AssignmentReminderSync.clear(requireContext());
                            // PC 中継の連携も中継サーバから消す (K は clearAll で消え、後から解除できなくなる)。
                            com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairingService.unpairAllInBackground(
                                    com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayStore.all());
                            prefs.clearAll();
                            DataCache.getInstance(requireContext()).clearAll();
                            // キャッシュを捨てたら、それを映しているウィジェットと通知も片付ける。
                            // 放置すると次の定期更新まで前のユーザーの時間割が出続ける。
                            com.shakenokirimi12.uoa_app.widget.ClassScheduleWidgetProvider.refresh(requireContext());
                            com.shakenokirimi12.uoa_app.services.notification.AssignmentCountdownNotifier.clearAll(requireContext());
                            // OkHttp の cookie と WebView の cookie は別の入れ物。片方だけ消しても、
                            // 次のユーザーがアプリ内ブラウザを開いたときに前のセッションが使われる。
                            com.shakenokirimi12.uoa_app.services.NetworkClient.clearCookies();
                            // 「10 分以内に検証済み」の記録も消す。残すと次のユーザーが空の cookie jar のまま
                            // ログイン済み扱いになる。
                            MoodleService.clearSession();
                            com.shakenokirimi12.uoa_app.services.CampusSquareService.clearSession();
                            com.shakenokirimi12.uoa_app.ui.browser.InAppBrowserActivity.clearWebSession(requireContext());
                            startActivity(new Intent(requireActivity(), OnboardingActivity.class));
                            requireActivity().finish();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show());
    }

    private void showTimePicker(TextView textLunchTime) {
        String current = prefs.getLunchNotifyTime();
        String[] parts = current.split(":");
        int hour = 11, minute = 30;
        if (parts.length == 2) {
            try {
                hour = Integer.parseInt(parts[0]);
                minute = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }

        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText("通知時間を選択")
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            String time = String.format(java.util.Locale.US, "%02d:%02d",
                    picker.getHour(), picker.getMinute());
            prefs.setLunchNotifyTime(time);
            textLunchTime.setText(time);
            com.shakenokirimi12.uoa_app.services.notification.LunchMenuWorker.reschedule(requireContext());
        });

        picker.show(getParentFragmentManager(), "lunch_time_picker");
    }

    private android.widget.TextView otpMethodLabel;
    private View otpRelayRow;
    private View otpRelayDivider;

    private void updateOtpMethodLabel() {
        if (otpMethodLabel == null) return;
        PreferenceManager p = PreferenceManager.getInstance();
        OtpMethod method = OtpMethod.from(p.getOtpMethod());
        boolean known = p.isOtpMethodKnown();
        // PC 中継はメールに届く OTP を読む機能なので、メール方式のときだけ出す (iOS と同じ)。
        int relayVis = known && method == OtpMethod.MAIL ? View.VISIBLE : View.GONE;
        if (otpRelayRow != null) otpRelayRow.setVisibility(relayVis);
        if (otpRelayDivider != null) otpRelayDivider.setVisibility(relayVis);
        // まだ決まっていない (新しく入れた・復元した・アカウントを変えた) ときは、次のログインで IdP から読む。
        if (!known) {
            otpMethodLabel.setText(R.string.settings_otp_method_unknown);
            return;
        }
        otpMethodLabel.setText(method == OtpMethod.AUTHENTICATOR
                ? R.string.settings_otp_method_authenticator
                : R.string.settings_otp_method_mail);
    }

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
                        : R.string.otp_switch_to_mail, (d, w) -> {
                    Bundle args = new Bundle();
                    args.putString(IdPOtpRegistrationFragment.ARG_SWITCHING_TO, other.value);
                    Navigation.findNavController(requireView())
                            .navigate(R.id.action_settings_to_idp_otp_registration, args);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setEnabled(!blocked);
    }

    /** 切り替えの画面を閉じたあと。受け取り方が変わっていなければ、その理由を出す。 */
    private void onOtpSwitchResult(@NonNull Bundle result) {
        if (!isAdded()) return;
        updateOtpMethodLabel();
        OtpMethod current = OtpMethod.from(PreferenceManager.getInstance().getOtpMethod());
        // 切り替え先になっていれば成功。結果の種類より設定の値を信じる。
        if (current.value.equals(result.getString(IdPOtpRegistrationFragment.RESULT_TARGET))) return;
        String name = getString(current == OtpMethod.AUTHENTICATOR
                ? R.string.otp_method_name_authenticator : R.string.otp_method_name_mail);
        boolean failed = IdPOtpRegistrationFragment.OUTCOME_FAILED.equals(
                result.getString(IdPOtpRegistrationFragment.RESULT_OUTCOME));
        Toast.makeText(requireContext(),
                getString(failed ? R.string.otp_switch_failed : R.string.otp_switch_cancelled, name),
                Toast.LENGTH_LONG).show();
    }

    private void showEditAccountDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_edit_account, null);
        EditText editUsername = dialogView.findViewById(R.id.edit_username);
        EditText editPassword = dialogView.findViewById(R.id.edit_password);
        editUsername.setText(prefs.getUsername());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("アカウント情報の変更")
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
                        textUsername.setText(newUser);
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
                                textUsername.setText(newUser);
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
