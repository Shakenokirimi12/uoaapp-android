package com.shakenokirimi12.uoa_app.ui.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
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
        Context ctx = holder.itemView.getContext();
        holder.textName.setText(facility.getName());
        holder.textStatusMessage.setText(facility.getStatusMessage());

        FacilityUsage.Status status = facility.getCurrentStatus();
        int statusColor = statusColor(ctx, holder.itemView, status);
        holder.textStatusBadge.setText(statusLabel(status));
        holder.textStatusBadge.setTextColor(statusColor);
        holder.textStatusBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(statusIcon(status), 0, 0, 0);
        TextViewCompat.setCompoundDrawableTintList(holder.textStatusBadge, ColorStateList.valueOf(statusColor));

        int availableColor = ContextCompat.getColor(ctx, R.color.success);
        int busyColor = MaterialColors.getColor(holder.itemView, androidx.appcompat.R.attr.colorError);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (FacilityUsage.ScheduleItem item : facility.getSchedule()) {
            if (sb.length() > 0) sb.append("\n");
            int start = sb.length();
            sb.append(item.getTimeRange());
            if (item.isAvailable()) {
                sb.append("  空き");
                sb.setSpan(new ForegroundColorSpan(availableColor),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append("  ").append(item.getEventName() != null ? item.getEventName() : "使用中");
                sb.setSpan(new ForegroundColorSpan(busyColor),
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

    @DrawableRes
    private int statusIcon(FacilityUsage.Status s) {
        switch (s) {
            case BUSY: return R.drawable.ic_x_circle;
            case OUTSIDE_HOURS: return R.drawable.ic_clock;
            default: return R.drawable.ic_check_circle;
        }
    }

    private int statusColor(Context ctx, View view, FacilityUsage.Status s) {
        switch (s) {
            case BUSY: return MaterialColors.getColor(view, androidx.appcompat.R.attr.colorError);
            case OUTSIDE_HOURS: return MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant);
            default: return ContextCompat.getColor(ctx, R.color.success);
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
