package com.shakenokirimi12.uoa_app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ClassDetailDialog {

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm", Locale.JAPAN);

    public static void show(Context context, GroupedClass cls) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_class_detail, null);

        TextView textPeriod = view.findViewById(R.id.text_period);
        TextView textName = view.findViewById(R.id.text_class_name);
        TextView textTime = view.findViewById(R.id.text_time);
        TextView textLocation = view.findViewById(R.id.text_location);
        TextView textDuration = view.findViewById(R.id.text_duration);
        TextView textStatus = view.findViewById(R.id.text_status);

        String periodStr = cls.formatPeriods();
        if (!periodStr.isEmpty()) {
            textPeriod.setText(periodStr);
            textPeriod.setVisibility(View.VISIBLE);
        }

        textName.setText(cls.getSummary() != null ? cls.getSummary() : "");
        String timeStr = "";
        if (cls.getDtstart() != null && cls.getDtend() != null) {
            timeStr = TIME_FMT.format(cls.getDtstart()) + " - " + TIME_FMT.format(cls.getDtend());
        }
        textTime.setText(timeStr);
        textLocation.setText(cls.getLocation().isEmpty() ? "未設定" : cls.getLocation());

        long durationMin = cls.getDurationMinutes();
        int eventCount = cls.getEventCount();
        String durationText = durationMin + "分";
        if (eventCount > 1) durationText += " (" + eventCount + "コマ)";
        textDuration.setText(durationText);

        Date now = new Date();
        if (cls.getDtstart() != null && cls.getDtend() != null) {
            if (cls.isActiveAt(now)) {
                long remainMin = Math.max(0, (cls.getDtend().getTime() - now.getTime()) / 60_000);
                textStatus.setText("授業中 — 残り " + remainMin + "分");
                textStatus.setVisibility(View.VISIBLE);
                GradientDrawable bg = (GradientDrawable) textStatus.getBackground().mutate();
                bg.setColor(0x1A008578);
                textStatus.setTextColor(0xFF008578);
            } else if (cls.getDtstart().after(now)) {
                long untilMin = (cls.getDtstart().getTime() - now.getTime()) / 60_000;
                if (untilMin <= 60) {
                    textStatus.setText(untilMin + "分後に開始");
                    textStatus.setVisibility(View.VISIBLE);
                    GradientDrawable bg = (GradientDrawable) textStatus.getBackground().mutate();
                    bg.setColor(0x1AFF9800);
                    textStatus.setTextColor(0xFFFF9800);
                }
            }
        }

        new MaterialAlertDialogBuilder(context)
                .setView(view)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
