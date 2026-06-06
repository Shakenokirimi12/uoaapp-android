package com.shakenokirimi12.uoa_app.ui.debug;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.DataCache;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.data.models.Assignment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotificationDebugFragment extends Fragment {

    private TextView textReport;
    private View buttonShare;
    private String lastReport = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_notification_debug, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        textReport = view.findViewById(R.id.text_report);
        buttonShare = view.findViewById(R.id.button_share_report);

        view.findViewById(R.id.button_generate_report).setOnClickListener(v -> generateReport());

        buttonShare.setOnClickListener(v -> {
            if (!lastReport.isEmpty()) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, lastReport);
                startActivity(Intent.createChooser(shareIntent, "レポートを共有"));
            }
        });
    }

    private void generateReport() {
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());
        DataCache cache = DataCache.getInstance(requireContext());
        List<Assignment> assignments = cache.loadAssignments();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 通知診断レポート ===\n\n");

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPANESE);
        sb.append("生成日時: ").append(fmt.format(new Date())).append("\n");
        sb.append("Android バージョン: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("デバイス: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n");

        // Notification permissions
        sb.append("--- 通知権限 ---\n");
        boolean notifEnabled = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled();
        sb.append("通知許可: ").append(notifEnabled ? "許可済み" : "未許可").append("\n");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) requireContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            var channels = nm.getNotificationChannels();
            sb.append("チャンネル数: ").append(channels.size()).append("\n");
            for (var ch : channels) {
                sb.append("  - ").append(ch.getId())
                        .append(" (重要度: ").append(ch.getImportance()).append(")\n");
            }
        }
        sb.append("\n");

        // Settings
        sb.append("--- 通知設定 ---\n");
        sb.append("課題通知: ").append(prefs.isAssignmentNotifyEnabled() ? "有効" : "無効").append("\n");
        sb.append("成績通知: ").append(prefs.isGradeNotifyEnabled() ? "有効" : "無効").append("\n");
        sb.append("学食通知: ").append(prefs.isLunchNotifyEnabled() ? "有効" : "無効").append("\n");
        sb.append("学食通知時刻: ").append(prefs.getLunchNotifyTime()).append("\n");
        sb.append("通知タイミング: ").append(prefs.getSelectedNotifyTimes()).append("\n");
        sb.append("同期間隔: ").append(prefs.getSyncInterval()).append("分\n");
        sb.append("BG通知(変更): ").append(prefs.isBgNotifyChange()).append("\n");
        sb.append("BG通知(成功): ").append(prefs.isBgNotifySuccess()).append("\n");
        sb.append("BG通知(失敗): ").append(prefs.isBgNotifyFailure()).append("\n");
        sb.append("BG通知(変更なし): ").append(prefs.isBgNotifyNoChange()).append("\n\n");

        // Assignments
        sb.append("--- 課題一覧 (").append(assignments.size()).append("件) ---\n");
        SimpleDateFormat dueFmt = new SimpleDateFormat("MM/dd HH:mm", Locale.JAPANESE);
        long nowSec = System.currentTimeMillis() / 1000;
        int upcoming = 0;
        for (Assignment a : assignments) {
            boolean future = a.getDueDate() > nowSec;
            if (future) upcoming++;
            sb.append(future ? "  [未来] " : "  [過去] ");
            sb.append(a.getName());
            if (a.getDueDate() > 0) {
                sb.append(" (〆切: ").append(dueFmt.format(new Date(a.getDueDate() * 1000))).append(")");
            }
            sb.append(a.isSubmitted() ? " ✓提出済" : " ✗未提出");
            sb.append("\n");
        }
        sb.append("\n未来の課題: ").append(upcoming).append("件\n");

        // Sync info
        sb.append("\n--- 同期情報 ---\n");
        long lastSync = prefs.getLastSync();
        if (lastSync > 0) {
            sb.append("最終同期: ").append(fmt.format(new Date(lastSync))).append("\n");
        } else {
            sb.append("最終同期: 未実行\n");
        }

        lastReport = sb.toString();
        textReport.setText(lastReport);
        buttonShare.setVisibility(View.VISIBLE);
    }
}
