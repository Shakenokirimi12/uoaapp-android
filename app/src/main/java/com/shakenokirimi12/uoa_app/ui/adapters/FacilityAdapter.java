package com.shakenokirimi12.uoa_app.ui.adapters;

import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.FacilityUsage;

import java.util.ArrayList;
import java.util.List;

public class FacilityAdapter extends RecyclerView.Adapter<FacilityAdapter.ViewHolder> {
    private List<FacilityUsage> items = new ArrayList<>();

    public void setItems(List<FacilityUsage> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_facility, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FacilityUsage facility = items.get(position);
        holder.textName.setText(facility.getName());
        holder.textStatusMessage.setText(facility.getStatusMessage());

        FacilityUsage.Status status = facility.getCurrentStatus();
        holder.textStatusBadge.setText(statusLabel(status));
        GradientDrawable bg = (GradientDrawable) holder.textStatusBadge.getBackground().mutate();
        bg.setColor(statusBgColor(status));
        holder.textStatusBadge.setTextColor(statusTextColor(status));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (FacilityUsage.ScheduleItem item : facility.getSchedule()) {
            if (sb.length() > 0) sb.append("\n");
            int start = sb.length();
            sb.append(item.getTimeRange());
            if (item.isAvailable()) {
                sb.append("  空き");
                sb.setSpan(new ForegroundColorSpan(0xFF4CAF50),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append("  ").append(item.getEventName() != null ? item.getEventName() : "使用中");
                sb.setSpan(new ForegroundColorSpan(0xFFF44336),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        holder.textSchedule.setText(sb);
    }

    private String statusLabel(FacilityUsage.Status s) {
        switch (s) {
            case BUSY: return "利用中";
            case OUTSIDE_HOURS: return "時間外";
            default: return "利用可能";
        }
    }

    private int statusBgColor(FacilityUsage.Status s) {
        switch (s) {
            case BUSY: return 0x1AF44336;
            case OUTSIDE_HOURS: return 0x1A9E9E9E;
            default: return 0x1A4CAF50;
        }
    }

    private int statusTextColor(FacilityUsage.Status s) {
        switch (s) {
            case BUSY: return 0xFFF44336;
            case OUTSIDE_HOURS: return 0xFF9E9E9E;
            default: return 0xFF4CAF50;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textName;
        final TextView textStatusBadge;
        final TextView textStatusMessage;
        final TextView textSchedule;

        ViewHolder(View view) {
            super(view);
            textName = view.findViewById(R.id.text_facility_name);
            textStatusBadge = view.findViewById(R.id.text_status_badge);
            textStatusMessage = view.findViewById(R.id.text_status_message);
            textSchedule = view.findViewById(R.id.text_schedule);
        }
    }
}
