package com.shakenokirimi12.uoa_app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.GroupedClass;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ClassAdapter extends RecyclerView.Adapter<ClassAdapter.ViewHolder> {
    private List<GroupedClass> items = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.JAPAN);
    private OnClassClickListener listener;

    public interface OnClassClickListener {
        void onClassClick(GroupedClass cls);
    }

    public void setOnClassClickListener(OnClassClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<GroupedClass> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_class, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GroupedClass cls = items.get(position);
        holder.textClassName.setText(cls.getSummary() != null ? cls.getSummary() : "");
        holder.textClassRoom.setText(cls.getLocation());
        holder.textStartTime.setText(cls.getDtstart() != null ? timeFmt.format(cls.getDtstart()) : "");
        holder.textEndTime.setText(cls.getDtend() != null ? timeFmt.format(cls.getDtend()) : "");

        String periodStr = cls.formatPeriods();
        if (!periodStr.isEmpty()) {
            holder.textPeriod.setText(periodStr);
            holder.textPeriod.setVisibility(View.VISIBLE);
        } else {
            holder.textPeriod.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClassClick(cls);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textClassName;
        final TextView textClassRoom;
        final TextView textStartTime;
        final TextView textEndTime;
        final TextView textPeriod;

        ViewHolder(View view) {
            super(view);
            textClassName = view.findViewById(R.id.text_class_name);
            textClassRoom = view.findViewById(R.id.text_class_room);
            textStartTime = view.findViewById(R.id.text_start_time);
            textEndTime = view.findViewById(R.id.text_end_time);
            textPeriod = view.findViewById(R.id.text_period);
        }
    }
}
