package com.shakenokirimi12.uoa_app.ui.settings;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import androidx.core.app.NotificationCompat;
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
import com.shakenokirimi12.uoa_app.ui.onboarding.OnboardingActivity;

public class SettingsFragment extends Fragment {

    private PreferenceManager prefs;
    private TextView textUsername;
    private int versionTapCount = 0;

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
        textUsername.setText(prefs.getUsername().isEmpty() ? "未ログイン" : prefs.getUsername());
        view.findViewById(R.id.text_username).setOnClickListener(v -> showEditAccountDialog());

        // Academic navigation
        view.findViewById(R.id.button_grades).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_grades));
        view.findViewById(R.id.button_facilities).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_facilities));
        view.findViewById(R.id.button_notifications).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_notifications));
        view.findViewById(R.id.button_campus_map).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_campus_map));

        // Notification switches
        MaterialSwitch switchAssignment = view.findViewById(R.id.switch_assignment_notify);
        MaterialSwitch switchGrade = view.findViewById(R.id.switch_grade_notify);
        MaterialSwitch switchLunch = view.findViewById(R.id.switch_lunch_notify);

        switchAssignment.setChecked(prefs.isAssignmentNotifyEnabled());
        switchGrade.setChecked(prefs.isGradeNotifyEnabled());
        switchLunch.setChecked(prefs.isLunchNotifyEnabled());

        switchAssignment.setOnCheckedChangeListener((v, checked) ->
                prefs.setAssignmentNotifyEnabled(checked));
        switchGrade.setOnCheckedChangeListener((v, checked) ->
                prefs.setGradeNotifyEnabled(checked));

        // Lunch notify with time picker
        View layoutLunchTime = view.findViewById(R.id.layout_lunch_time);
        TextView textLunchTime = view.findViewById(R.id.text_lunch_time);
        textLunchTime.setText(prefs.getLunchNotifyTime());
        layoutLunchTime.setVisibility(prefs.isLunchNotifyEnabled() ? View.VISIBLE : View.GONE);

        switchLunch.setOnCheckedChangeListener((v, checked) -> {
            prefs.setLunchNotifyEnabled(checked);
            layoutLunchTime.setVisibility(checked ? View.VISIBLE : View.GONE);
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

        // Auto attendance
        MaterialSwitch switchAutoAttendance = view.findViewById(R.id.switch_auto_attendance);
        switchAutoAttendance.setChecked(prefs.isAutoAttendanceEnabled());
        switchAutoAttendance.setOnCheckedChangeListener((v, checked) -> {
            prefs.setAutoAttendanceEnabled(checked);
            if (checked) {
                LocationGeofenceService.startGeofencing(requireContext(), 37.5234, 139.9388, 200);
            } else {
                LocationGeofenceService.stopGeofencing(requireContext());
            }
        });

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

        // Debug section
        View sectionDebug = view.findViewById(R.id.section_debug);
        if (prefs.isDebugMode()) {
            sectionDebug.setVisibility(View.VISIBLE);
        }

        view.findViewById(R.id.button_test_notification).setOnClickListener(v -> {
            sendTestNotification();
            Toast.makeText(requireContext(), "テスト通知を送信しました", Toast.LENGTH_SHORT).show();
        });

        view.findViewById(R.id.button_force_sync).setOnClickListener(v ->
                Toast.makeText(requireContext(), "バックグラウンド同期を開始しました", Toast.LENGTH_SHORT).show());

        view.findViewById(R.id.button_notification_debug).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_notification_debug));

        // Version with hidden debug mode toggle
        TextView textVersion = view.findViewById(R.id.text_version);
        textVersion.setText(getString(R.string.settings_version) + " " + BuildConfig.VERSION_NAME);
        textVersion.setOnClickListener(v -> {
            versionTapCount++;
            if (versionTapCount >= 10) {
                boolean newState = !prefs.isDebugMode();
                prefs.setDebugMode(newState);
                sectionDebug.setVisibility(newState ? View.VISIBLE : View.GONE);
                Toast.makeText(requireContext(),
                        newState ? "デバッグモードを有効にしました" : "デバッグモードを無効にしました",
                        Toast.LENGTH_SHORT).show();
                versionTapCount = 0;
            }
        });

        // Logout
        view.findViewById(R.id.button_logout).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_logout)
                        .setMessage("ログアウトしてもよろしいですか？")
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            prefs.clearAll();
                            DataCache.getInstance(requireContext()).clearAll();
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
        });

        picker.show(getParentFragmentManager(), "lunch_time_picker");
    }

    private void sendTestNotification() {
        NotificationManager nm = (NotificationManager) requireContext()
                .getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        String channelId = "debug_test";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "デバッグテスト", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("テスト通知")
                .setContentText("この通知が見えていれば、通知機能は正常です。")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        nm.notify(9999, builder.build());
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
