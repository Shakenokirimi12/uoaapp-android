package com.shakenokirimi12.uoa_app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.Assignment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AssignmentAdapter extends RecyclerView.Adapter<AssignmentAdapter.ViewHolder> {
    private List<Assignment> items = new ArrayList<>();
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("M/d HH:mm", Locale.JAPAN);
    private OnAssignmentClickListener clickListener;

    public interface OnAssignmentClickListener {
        void onAssignmentClick(Assignment assignment);
    }

    public void setOnAssignmentClickListener(OnAssignmentClickListener listener) {
        this.clickListener = listener;
    }

    public void setItems(List<Assignment> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_assignment, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Assignment a = items.get(position);
        holder.textName.setText(a.getName());
        holder.textCourse.setText(a.getCourseName());
        if (a.getDueDate() > 0) {
            Date due = new Date(a.getDueDate() * 1000);
            holder.textDue.setText("締切: " + dateFmt.format(due));
            holder.textDue.setTextColor(a.isDuePast()
                    ? holder.itemView.getContext().getColor(R.color.text_secondary)
                    : holder.itemView.getContext().getColor(R.color.error));
        } else {
            holder.textDue.setText("");
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onAssignmentClick(a);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textName;
        final TextView textCourse;
        final TextView textDue;

        ViewHolder(View view) {
            super(view);
            textName = view.findViewById(R.id.text_assignment_name);
            textCourse = view.findViewById(R.id.text_course_name);
            textDue = view.findViewById(R.id.text_due_date);
        }
    }
}
