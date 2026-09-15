package com.shakenokirimi12.uoa_app.ui.adapters;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
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

        // 科目コードは学生には意味が無いので出さない (2026-09-15 のフィードバック)。
        StringBuilder meta = new StringBuilder();
        if (g.getYear() != null && !g.getYear().isEmpty()) {
            meta.append(g.getYear());
            if (g.getSemester() != null && !g.getSemester().isEmpty()) {
                meta.append(" ").append(g.getSemester());
            }
        } else if (g.getSemester() != null && !g.getSemester().isEmpty()) {
            meta.append(g.getSemester());
        }
        holder.textMeta.setText(meta.toString());
        holder.textMeta.setVisibility(meta.length() > 0 ? View.VISIBLE : View.GONE);

        StringBuilder detail = new StringBuilder();
        if (g.getCredits() != null && !g.getCredits().isEmpty()) {
            detail.append(g.getCredits()).append("単位");
        }
        // CampusSquare は履修中の科目の点数欄に「履修中」と入れてくる。数値でなければ点数としては
        // 出さない (右のチップに状態として出るので重複する)。
        if (g.getScore() != null && g.getScore().matches("\\d+(\\.\\d+)?")) {
            if (detail.length() > 0) detail.append("  ");
            detail.append("点数: ").append(g.getScore());
        }
        holder.textCredits.setText(detail.toString());
        holder.textCredits.setVisibility(detail.length() > 0 ? View.VISIBLE : View.GONE);

        // 2 文字で切ると「履修中」が「履修」になる。評価は元々 2 文字以内なので切らずに出す。
        String grade = g.getGrade() != null ? g.getGrade() : "?";
        holder.textGradeChip.setText(grade);

        Context ctx = holder.itemView.getContext();
        // mutate(): the shape drawable's constant state is shared across rows.
        GradientDrawable bg = (GradientDrawable) holder.textGradeChip.getBackground().mutate();
        bg.setColor(gradeContainerColor(ctx, holder.textGradeChip, grade));
        holder.textGradeChip.setTextColor(gradeOnContainerColor(ctx, holder.textGradeChip, grade));
    }

    private int gradeContainerColor(Context ctx, View view, String grade) {
        switch (grade.toUpperCase().trim()) {
            case "AA": case "A":
                return MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimaryContainer);
            case "B":
                return ContextCompat.getColor(ctx, R.color.success_container);
            case "C":
                return ContextCompat.getColor(ctx, R.color.warning_container);
            case "D": case "F":
                return MaterialColors.getColor(view, com.google.android.material.R.attr.colorErrorContainer);
            default:
                return MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        }
    }

    private int gradeOnContainerColor(Context ctx, View view, String grade) {
        switch (grade.toUpperCase().trim()) {
            case "AA": case "A":
                return MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimaryContainer);
            case "B":
                return ContextCompat.getColor(ctx, R.color.success);
            case "C":
                return ContextCompat.getColor(ctx, R.color.on_warning_container);
            case "D": case "F":
                return MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnErrorContainer);
            default:
                return MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textGradeChip;
        final TextView textCourseName;
        final TextView textMeta;
        final TextView textCredits;

        ViewHolder(View view) {
            super(view);
            textGradeChip = view.findViewById(R.id.text_grade_chip);
            textCourseName = view.findViewById(R.id.text_course_name);
            textMeta = view.findViewById(R.id.text_meta);
            textCredits = view.findViewById(R.id.text_credits);
        }
    }
}
