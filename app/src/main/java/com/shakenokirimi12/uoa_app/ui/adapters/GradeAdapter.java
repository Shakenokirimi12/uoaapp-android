package com.shakenokirimi12.uoa_app.ui.adapters;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shakenokirimi12.uoa_app.R;
import com.shakenokirimi12.uoa_app.data.models.Grade;

import java.util.ArrayList;
import java.util.List;

public class GradeAdapter extends RecyclerView.Adapter<GradeAdapter.ViewHolder> {
    private List<Grade> items = new ArrayList<>();

    public void setItems(List<Grade> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_grade, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Grade g = items.get(position);
        holder.textCourseName.setText(g.getCourseName());

        StringBuilder detail = new StringBuilder();
        if (g.getCredits() != null && !g.getCredits().isEmpty()) {
            detail.append(g.getCredits()).append("単位");
        }
        if (g.getScore() != null && !g.getScore().isEmpty()) {
            if (detail.length() > 0) detail.append("  ");
            detail.append("点数: ").append(g.getScore());
        }
        holder.textCredits.setText(detail.toString());

        String grade = g.getGrade() != null ? g.getGrade() : "?";
        holder.textGradeChip.setText(grade.length() > 2 ? grade.substring(0, 2) : grade);

        GradientDrawable bg = (GradientDrawable) holder.textGradeChip.getBackground();
        bg.setColor(getGradeColor(grade));
    }

    private int getGradeColor(String grade) {
        if (grade == null) return 0xFF9E9E9E;
        switch (grade.toUpperCase().trim()) {
            case "AA": case "A":  return 0xFF008578;
            case "B":             return 0xFF4CAF50;
            case "C":             return 0xFFFFC107;
            case "D": case "F":   return 0xFFF44336;
            default:              return 0xFF9E9E9E;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textGradeChip;
        final TextView textCourseName;
        final TextView textCredits;

        ViewHolder(View view) {
            super(view);
            textGradeChip = view.findViewById(R.id.text_grade_chip);
            textCourseName = view.findViewById(R.id.text_course_name);
            textCredits = view.findViewById(R.id.text_credits);
        }
    }
}
