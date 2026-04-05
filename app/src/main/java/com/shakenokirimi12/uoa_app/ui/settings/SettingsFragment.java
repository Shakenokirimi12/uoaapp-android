package com.shakenokirimi12.uoa_app.ui.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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
import com.google.android.material.materialswitch.MaterialSwitch;
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
        switchLunch.setOnCheckedChangeListener((v, checked) ->
                prefs.setLunchNotifyEnabled(checked));

        // Version
        TextView textVersion = view.findViewById(R.id.text_version);
        textVersion.setText(getString(R.string.settings_version) + " " + BuildConfig.VERSION_NAME);

        // Notification inbox & Peer sync
        view.findViewById(R.id.button_notifications).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_notifications));
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
