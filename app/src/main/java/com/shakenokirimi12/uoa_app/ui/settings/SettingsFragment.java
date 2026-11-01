package com.shakenokirimi12.uoa_app.ui.settings;

import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.shakenokirimi12.uoa_app.BuildConfig;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.BackupService;
import com.shakenokirimi12.uoa_app.services.LocationGeofenceService;
import com.shakenokirimi12.uoa_app.ui.onboarding.OnboardingActivity;

public class SettingsFragment extends Fragment {

    private PreferenceManager prefs;
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
                    Toast.makeText(requireContext(), R.string.settings_location_permission_required, Toast.LENGTH_LONG).show();
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
                : getString(R.string.settings_location_option_always);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.settings_location_dialog_title_format, option))
                .setMessage(getString(R.string.settings_location_dialog_message_format, option))
                .setPositiveButton(R.string.settings_location_dialog_open_settings, (d, w) ->
                        backgroundLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                .setNegativeButton(R.string.common_button_later, null)
                .show();
    }

    /** iOS の StatusNoticeBanner 相当。有効なのに「常に許可」でないときだけ出す。 */
    private void updateLocationWarning() {
        if (textLocationWarning == null) return;
        boolean show = prefs.isAutoAttendanceEnabled() && !(hasFineLocation() && hasBackgroundLocation());
        textLocationWarning.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 設定アプリから戻ってきたときに反映する。
        updateLocationWarning();
        // ユーザー名と受け取り方は、ログインの画面や登録の完了で変わる。
        updateLoginSummary();
    }

    private final ActivityResultLauncher<Intent> backupLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded()) return;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    try {
                        BackupService.exportTo(requireContext(), result.getData().getData());
                        Toast.makeText(requireContext(), R.string.settings_backup_done, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), getString(R.string.settings_backup_failed_format, e.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final ActivityResultLauncher<Intent> restoreLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded()) return;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    try {
                        BackupService.importFrom(requireContext(), result.getData().getData());
                        Toast.makeText(requireContext(), R.string.settings_restore_done, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), getString(R.string.settings_restore_failed_format, e.getMessage()), Toast.LENGTH_SHORT).show();
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
        loginSummary = view.findViewById(R.id.text_login_summary);
        updateLoginSummary();
        boolean loggedIn = !prefs.getUsername().isEmpty();
        view.findViewById(R.id.row_login).setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_settings_to_login_settings));
        // A logout button next to "未ログイン" is contradictory; hide it until there is an account.
        view.findViewById(R.id.button_logout).setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.divider_logout).setVisibility(loggedIn ? View.VISIBLE : View.GONE);



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

        // 授業の常駐通知 (iOS の Live Activity の設定に相当)。以前は止める手段が無かった。
        MaterialSwitch switchClassNotification = view.findViewById(R.id.switch_class_notification);
        switchClassNotification.setChecked(prefs.isClassNotificationEnabled());
        switchClassNotification.setOnCheckedChangeListener((v, checked) -> {
            prefs.setClassNotificationEnabled(checked);
            // OFF なら今出ている分を消す。ON なら手元の時間割で今日の分を出す (startForToday が判定する)。
            com.shakenokirimi12.uoa_app.services.notification.ClassNotificationService.startForToday(
                    requireContext(), DataCache.getInstance(requireContext()).loadEvents());
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

        // 利用状況と診断情報を送る (既定 ON)。OFF にすると、まだ送っていない回数と利用状況の ID を消し、OTP の診断の記録 (OtpDiagnostics) も送らない。
        MaterialSwitch switchUsageStats = view.findViewById(R.id.switch_usage_stats);
        switchUsageStats.setChecked(prefs.isUsageStatsEnabled());
        switchUsageStats.setOnCheckedChangeListener((v, checked) ->
                com.shakenokirimi12.uoa_app.services.usage.UsageStats.setEnabled(requireContext(), checked));

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

        // 問い合わせ ID。秘密ではないので、OTP のシークレットと違ってクリップボードのプレビューは止めない。
        TextView textSupportId = view.findViewById(R.id.text_support_id);
        String supportId = prefs.getSupportId();
        textSupportId.setText(supportId);
        view.findViewById(R.id.button_copy_support_id).setOnClickListener(v -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.settings_support_id), supportId));
            // Android 13 以降はシステムがコピーしたことを出すので、重ねて出さない。
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(requireContext(), R.string.settings_support_id_copied, Toast.LENGTH_SHORT).show();
            }
        });

        // Logout
        view.findViewById(R.id.button_logout).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_logout)
                        .setMessage(R.string.settings_logout_message)
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            // 先に世代を進める。ログアウトの前に始まった取得 (裏の同期など) が、このあと空にする
                            // キャッシュとリマインダーに前のユーザーのデータを書き戻さない。
                            com.shakenokirimi12.uoa_app.services.AccountSwitch.loggedOut();
                            // サーバーの削除が失敗しても端末内の削除は続け、失敗したことは 1 回だけ知らせる (SPEC.md「11」)。
                            // 画面はすぐ閉じるので、アプリの Context で出す。
                            Runnable serverDeleteFailed = logoutServerFailureNotice(requireContext().getApplicationContext());
                            // 先にサーバー側の予約を空にする。prefs.clearAll() は deviceId と
                            // 「サーバーが予約を持っている」記録の両方を消すので、後から呼ぶと
                            // 取り消しは早期 return し、古い deviceId の予約が二度と触れなくなる。
                            com.shakenokirimi12.uoa_app.services.push.AssignmentReminderSync.clear(requireContext(), serverDeleteFailed);
                            // サーバーの端末の行も消す (iOS の「全データを削除」と同じ)。これも今の ID で、clearAll の前に積む。
                            com.shakenokirimi12.uoa_app.services.PushNotificationService.shared(requireContext()).deleteDevice(serverDeleteFailed);
                            // PC 中継の連携も中継サーバから消す。解除が済むまで鍵は「解除待ち」に残し、
                            // 失敗しても送り直す (clearAll でも消えない)。
                            com.shakenokirimi12.uoa_app.services.otprelay.OtpRelayPairingService.unpairAllOnLogout(requireContext());
                            // 時間割共有: 受け取った友達の時間割と、出している共有を消す (共有はサーバーからも)。
                            // 継続共有の自分のフィード (積んであるものと全部の友達の分) も DELETE し、署名の鍵を消す
                            // (docs/timetable-share-v3.md の 10.7)。消せなかったものは serverDeleteFailed で知らせる。
                            // 返事を読むトークンは clearAll が消すので、その前に呼ぶ。
                            com.shakenokirimi12.uoa_app.data.timetable.TimetableShareStore.getInstance(requireContext()).clearAllOnLogout(serverDeleteFailed);
                            prefs.clearAll();
                            // キャッシュと、それを映しているウィジェット・通知を片付ける (アカウントの切り替えと共通)。
                            com.shakenokirimi12.uoa_app.services.AccountSwitch.clearAccountCaches(requireContext());
                            // 利用状況のまだ送っていない回数と ID も消す (次のユーザーの分と混ぜない)。
                            // ウィジェットの更新も数えるので、片付けのあとに消す。
                            com.shakenokirimi12.uoa_app.services.usage.UsageStats.clearLocal(requireContext());
                            // OkHttp の cookie と WebView の cookie は別の入れ物。片方だけ消しても、
                            // 次のユーザーがアプリ内ブラウザを開いたときに前のセッションが使われる。
                            com.shakenokirimi12.uoa_app.services.NetworkClient.clearCookies();
                            // 「10 分以内に検証済み」の記録も消す。残すと次のユーザーが空の cookie jar のまま
                            // ログイン済み扱いになる。
                            com.shakenokirimi12.uoa_app.services.MoodleService.clearSession();
                            com.shakenokirimi12.uoa_app.services.CampusSquareService.clearSession();
                            com.shakenokirimi12.uoa_app.ui.browser.InAppBrowserActivity.clearWebSession(requireContext());
                            startActivity(new Intent(requireActivity(), OnboardingActivity.class));
                            requireActivity().finish();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show());
    }

    /** ログアウトでサーバーのデータを消せなかったときの表示。何本失敗しても 1 回だけ出す。どのスレッドから呼んでもよい。 */
    @NonNull
    private static Runnable logoutServerFailureNotice(@NonNull android.content.Context app) {
        java.util.concurrent.atomic.AtomicBoolean shown = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!shown.compareAndSet(false, true)) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(app, R.string.settings_logout_server_failed, Toast.LENGTH_LONG).show());
        };
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
                .setTitleText(R.string.settings_lunch_time_picker_title)
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

    private TextView loginSummary;

    /** 「s1300000 ・ メールで受け取る」。 */
    private void updateLoginSummary() {
        if (loginSummary == null) return;
        String user = prefs.getUsername();
        String method = !prefs.isOtpMethodKnown() ? null
                : getString(OtpMethod.from(prefs.getOtpMethod()) == OtpMethod.AUTHENTICATOR
                        ? R.string.settings_otp_method_authenticator : R.string.settings_otp_method_mail);
        String name = user.isEmpty() ? getString(R.string.login_not_logged_in) : user;
        loginSummary.setText(method != null
                ? getString(R.string.settings_login_summary_format, name, method) : name);
    }
}
